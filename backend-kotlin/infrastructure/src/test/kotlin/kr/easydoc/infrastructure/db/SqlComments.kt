package kr.easydoc.infrastructure.db

/**
 * 소스에서 뽑은 SQL 조각에서 **PostgreSQL 이 무시하는 것만** 지운다.
 *
 * ## 왜 있는가 — 하나의 정규화를 두 방향에 같이 쓰면 한쪽이 뒤집힌다
 *
 * 소스 전수에서 SQL 조각을 뽑아 정규식으로 판정하는 가드는 두 방향을 함께 쓴다.
 *
 * | 방향 | 주석 | fail 방향 |
 * |---|---|---|
 * | **문장 발견(분모)** — 「이 문장이 검사 대상인가」 | **그대로 둔다.** Kotlin 주석·KDoc 이 품은 SQL 도 센다 | 분모를 넓히는 쪽이라 **fail-closed** |
 * | **방어 존재 판정** — 「이 문장에 방어가 **있는가**」 | **걷어낸 뒤 판정한다** (이 객체) | 죽은 방어를 산 것으로 세면 **fail-open** |
 *
 * 아래 칸을 원시 조각에 그대로 걸면 `WHERE c.id = :id -- AND d.user_id = :ownerId` 가
 * **방어가 있는 것으로 통과**한다(실측 — [OwnershipPredicateGuardTest] 가 그 상태였다).
 * PostgreSQL 은 `--` 뒤와 블록 주석 안을 무시하므로 실제 질의에는 그 조건이 없다.
 * 소유권 우회를 잡으라고 세운 장치가 소유권 우회를 승인하게 된다 — 프로젝트 규칙 2 의
 * 「잘못된 근거를 만드는 도구」다.
 *
 * **위 칸의 근거(분모는 넓을수록 안전하다)를 아래 칸에 옮겨 적지 마라.** 아래 칸에서는
 * 넓은 쪽이 위험한 쪽이다. 그 옮겨 적기가 이 결함의 기제였다.
 *
 * ## 쓰는 자리 (「소스 SQL 조각에 정규식으로 **방어의 존재**를 판정하는」 자리 전부)
 *
 * - [OwnershipPredicateGuardTest] — 소유 술어가 **있으면** 통과
 * - [EnvelopeColumnWriteGuardTest] — 봉투 두 열을 **쓰면** 통과
 *
 * 같은 모양의 판정을 새로 만들면 여기를 거쳐라. 분모 쪽에는 쓰지 마라.
 */
internal object SqlComments {
    /**
     * 주석을 걷어낸 사본. **방어 존재 판정 전용이다** — 분모 계산에 쓰면 방향이 뒤집힌다.
     *
     * 지우는 것은 둘이다. `--` 는 줄 끝까지, 블록 주석은 블록 전체이며 **중첩 깊이를 센다.**
     * PostgreSQL 의 블록 주석은 중첩하는데, 단순 비탐욕 정규식은 `/* a /* b */ c */` 를
     * **첫 닫힘에서** 끊어 실제로는 주석 안인 ` c ` 를 밖으로 남긴다. 거기 방어 표식이 있으면
     * 그대로 fail-open 잔여다 — 그래서 정규식이 아니라 깊이를 세는 훑개다.
     *
     * **지운 자리에는 공백을 남긴다.** 주석은 PostgreSQL 에서 공백이라, 지우고 붙여 버리면
     * `user_i` + 빈 블록 주석 + `d = :x` 가 `user_id = :x` 로 **붙어 없던 방어를 만든다** —
     * 걷어내기가 스스로 fail-open 을 새로 만드는 형태다. 줄 주석의 개행도 남긴다.
     *
     * 닫히지 않은 블록 여는 표시는 조각 끝까지 지운다. 조각 경계가 인용부호라 KDoc 이 중간에서
     * 잘릴 수 있는데, 그때 남는 쪽을 살려 두면 죽은 방어가 되살아난다. 지우는 쪽이 과잉 탐지
     * = fail-closed 다.
     *
     * **막지 못하는 것** (정직하게 적는다): Kotlin `//` 줄 주석은 지우지 않는다 — SQL 주석이
     * 아니기 때문이다. `//` 까지 지우면 문자열 리터럴 안의 URL 이 살아 있는 방어를 잘라내
     * 반대 방향으로 뒤집힌다. 이 비대칭은 알고 남긴 것이다.
     *
     * 주: 이 KDoc 이 블록 주석 표시를 **짝으로만** 적는 것은 멋이 아니다 — Kotlin 블록 주석은
     * 중첩하므로 짝이 맞지 않는 조각 하나가 이 주석을 그 자리에서 끊는다(실측: 홀로 선 닫힘
     * 표시 하나에 `Expecting member declaration` 이 쏟아졌다).
     */
    fun strip(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            index =
                when {
                    startsWith(sql, index, '/', '*') -> {
                        skipBlockComment(sql, index, out)
                    }

                    startsWith(sql, index, '-', '-') -> {
                        skipLineComment(sql, index, out)
                    }

                    else -> {
                        out.append(sql[index])
                        index + 1
                    }
                }
        }
        return out.toString()
    }

    private fun startsWith(
        sql: String,
        index: Int,
        first: Char,
        second: Char,
    ): Boolean = sql[index] == first && index + 1 < sql.length && sql[index + 1] == second

    /**
     * 블록 주석을 **중첩 깊이를 세며** 건너뛴다. 닫히지 않으면 조각 끝까지 간다.
     *
     * 안의 개행은 [out] 에 남긴다 — 줄 구조를 지우면 서로 다른 줄의 토큰이 이어 붙는다.
     * 여는 자리에 공백 하나를 남기는 이유는 KDoc 에 있다(주석은 PostgreSQL 에서 공백이다).
     */
    private fun skipBlockComment(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start + 2
        var depth = 1
        while (index < sql.length && depth > 0) {
            when {
                startsWith(sql, index, '/', '*') -> {
                    depth++
                    index += 2
                }

                startsWith(sql, index, '*', '/') -> {
                    depth--
                    index += 2
                }

                else -> {
                    if (sql[index] == '\n') out.append('\n')
                    index++
                }
            }
        }
        return index
    }

    /** 줄 끝까지 건너뛴다. 개행 자체는 남긴다 — 다음 줄과 붙지 않게. */
    private fun skipLineComment(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start
        while (index < sql.length && sql[index] != '\n') index++
        return index
    }
}

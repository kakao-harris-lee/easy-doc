package kr.easydoc.infrastructure.db

/**
 * 소스에서 뽑은 SQL 조각에서 **PostgreSQL 이 술어로 평가하지 않는 부분**을 지운다.
 *
 * 지우는 것이 둘이다 — **주석**(무시된다)과 **문자열 리터럴의 내용**(데이터로 평가된다).
 * 둘은 이유가 다르지만 판정에 미치는 영향이 같다: 거기 적힌 것은 **조건이 아니다.**
 *
 * ## 왜 있는가 — 하나의 정규화를 두 방향에 같이 쓰면 한쪽이 뒤집힌다
 *
 * 소스 전수에서 SQL 조각을 뽑아 정규식으로 판정하는 가드는 두 방향을 함께 쓴다.
 *
 * | 방향 | 주석·리터럴 | fail 방향 |
 * |---|---|---|
 * | **문장 발견(분모)** — 「이 문장이 검사 대상인가」 | **그대로 둔다.** 주석·리터럴이 품은 SQL 도 센다 | 분모를 넓히는 쪽이라 **fail-closed** |
 * | **방어 존재 판정** — 「이 문장에 방어가 **있는가**」 | **걷어낸 뒤 판정한다** (이 객체) | 죽은 방어를 산 것으로 세면 **fail-open** |
 *
 * ## 이 객체가 두 번 고쳐진 이력 — 같은 형태의 결함 둘
 *
 * **⑴ 주석**(2026-08-20, 커밋 `d1ce78e`). 아래 칸을 원시 조각에 그대로 걸어서
 * `WHERE c.id = :id -- AND d.user_id = :ownerId` 가 **방어가 있는 것으로 통과**했다(실측).
 * PostgreSQL 은 `--` 뒤와 블록 주석 안을 무시하므로 실제 질의에는 그 조건이 없다.
 *
 * **⑵ 문자열 리터럴**(2026-08-21, 게이트 28 P-7). 같은 자리에 같은 형태가 하나 더 있었다.
 *
 * - 소유 가드: `SELECT 'user_id = :ownerId', c.easy_text_encrypted FROM conversions …` 가
 *   `hasOwnerPredicate = true` 로 통과한다. 실제 질의에는 소유 조건이 **없다.**
 * - 봉투 가드: `SET source_text_encrypted = :cipher,
 *   title = 'encryption_scheme = :s, key_version = :v' WHERE …` 가 `setsEnvelope = true` 로
 *   통과한다. PostgreSQL 은 봉투 열을 하나도 대입하지 않으므로 그 행은 세대가 오르지 않은
 *   암호문을 갖고 **영원히 열리지 않는다**(AAD 에 세대가 실린다).
 *
 * ⑵ 가 ⑴ 보다 무거운 이유는 **선언이 없었다**는 점이다. 소유 가드는 「막지 못하는 것」에
 * 결속 대상·별칭·`OR` 갈래를 이미 적어 두었지만(정직한 좁은 선언), 봉투 가드의 리터럴
 * 갈래는 **어느 목록에도 없었고** 결과가 복구 불가 행이다(리더 판정 P-7).
 *
 * **위 칸의 근거(분모는 넓을수록 안전하다)를 아래 칸에 옮겨 적지 마라.** 아래 칸에서는
 * 넓은 쪽이 위험한 쪽이다. 그 옮겨 적기가 두 결함의 공통 기제였다.
 *
 * ## 이름이 `SqlComments` 가 아닌 이유
 *
 * 종전 이름과 그 KDoc 은 범위를 *"PostgreSQL 이 **무시하는 것만** 지운다"* 로 선언했다.
 * 문자열 리터럴은 무시되지 않는다 — 값으로 평가된다. 기능만 늘리고 이름·선언을 두면 그
 * 선언이 **거짓**이 되고, 그것이 이 회차가 고치는 결함(문면이 사실과 갈린다)과 같은 형태다.
 * 그래서 이름을 바꿨다. 갈아탄 자리는 아래 「쓰는 자리」 둘이며, 옛 이름이 어딘가에 남아
 * 있으면 `kr.easydoc.api.NamedReferenceGuardTest` 축 A 가 그것을 짚는다.
 *
 * ## 쓰는 자리 (「소스 SQL 조각에 정규식으로 **방어의 존재**를 판정하는」 자리 전부)
 *
 * - [OwnershipPredicateGuardTest] — 소유 술어가 **있으면** 통과
 * - [EnvelopeColumnWriteGuardTest] — 봉투 두 열을 **쓰면** 통과
 *
 * 같은 모양의 판정을 새로 만들면 여기를 거쳐라. 분모 쪽에는 쓰지 마라.
 */
internal object LiveSql {
    /**
     * 판정에 쓰는 「살아 있는 SQL」. **방어 존재 판정 전용이다** — 분모 계산에 쓰면 방향이 뒤집힌다.
     *
     * **순서가 규칙이다**: 주석을 먼저 지우고 그 다음 리터럴을 지운다. 반대로 하면 주석 속
     * 아포스트로피(`-- don't`)가 유령 리터럴을 열어 조각의 나머지를 삼킨다.
     */
    fun of(sql: String): String = redactLiterals(stripComments(sql))

    /**
     * 주석을 걷어낸 사본.
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
    fun stripComments(sql: String): String {
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

    /**
     * 문자열 리터럴의 **내용**을 공백으로 바꾼 사본. 인용부호 자체도 공백이 된다.
     *
     * 내용을 지우고 **길이·줄 구조는 남긴다** — 주석 걷어내기와 같은 이유다. 리터럴을 통째로
     * 없애 양쪽을 붙이면 `user_i` + `''` + `d = :x` 가 없던 방어를 만든다.
     *
     * 다루는 형태 둘.
     *
     * ⑴ **작은따옴표 리터럴** `'…'`. 안의 `''` 는 escaped quote 라 리터럴을 닫지 않는다.
     *    `\'` 도 닫지 않는 것으로 본다 — `standard_conforming_strings` 가 켜진 보통 리터럴에서
     *    역슬래시는 escape 가 아니지만 `E'…'` 에서는 escape 다. 두 해석 중 **리터럴을 더 길게
     *    보는 쪽**을 고른다: 짧게 보면 실제로는 리터럴 안인 텍스트가 술어로 읽혀 fail-open 이
     *    되고, 길게 보면 실제 술어가 지워져 「방어 없음」으로 핀에 올라오는 fail-closed 다.
     *
     * ⑵ **달러 인용** `$tag$…$tag$`(태그 없는 `$$…$$` 포함). 여는 표시는 달러 + 식별자 +
     *    달러가 **연속**일 때만 인정한다 — Kotlin 문자열 템플릿은 달러 뒤에 중괄호나 이름이
     *    오고 닫는 달러가 없으므로 그 모양이 아니다. 그래서 SQL 조각을 담은 raw string 의
     *    보간이 리터럴로 오인되지 않는다(실측으로 확인한 자리: 두 가드의 핀 상수가 달러 +
     *    상수 이름 + 슬래시 형태로 이어진다).
     *
     * 닫히지 않은 리터럴은 조각 끝까지 지운다. 조각 경계가 인용부호라 잘릴 수 있고, 남기는
     * 쪽이 fail-open 이다.
     *
     * **막지 못하는 것** (정직하게 적는다): 유니코드 이스케이프 리터럴 `U&'…'` 의 접두는
     * 인식하지 않는다 — 뒤따르는 `'` 를 ⑴ 이 그대로 잡으므로 결과는 같지만, 접두 자체를
     * 아는 것은 아니다. 그리고 **리터럴이 아닌 문자열 조립**(Kotlin `+` 나 보간으로 만든
     * SQL)은 여전히 이 객체 밖이다 — 두 가드 KDoc 의 첫 항목이 같은 한계를 적어 두었다.
     */
    fun redactLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val dollarTag = dollarTagAt(sql, index)
            index =
                when {
                    dollarTag != null -> {
                        skipDollarQuoted(sql, index, dollarTag, out)
                    }

                    sql[index] == '\'' -> {
                        skipQuoted(sql, index, out)
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

    /** 작은따옴표 리터럴을 건너뛴다. `''` 와 `\'` 는 닫지 않는다(KDoc ⑴). */
    private fun skipQuoted(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start + 1
        while (index < sql.length) {
            val current = sql[index]
            when {
                current == '\'' && index + 1 < sql.length && sql[index + 1] == '\'' -> {
                    out.append("  ")
                    index += 2
                }

                current == '\\' && index + 1 < sql.length -> {
                    out.append("  ")
                    index += 2
                }

                current == '\'' -> {
                    out.append(' ')
                    return index + 1
                }

                else -> {
                    out.append(if (current == '\n') '\n' else ' ')
                    index++
                }
            }
        }
        return index
    }

    /**
     * 이 자리가 달러 인용의 **여는 표시**면 그 태그(`$$` 나 `$tag$` 전체), 아니면 `null`.
     *
     * 태그와 닫는 `$` 가 **연속**해야 한다 — Kotlin 템플릿 보간과 가르는 조건이 그것이다.
     */
    private fun dollarTagAt(
        sql: String,
        index: Int,
    ): String? {
        if (sql[index] != '$') return null
        var cursor = index + 1
        while (cursor < sql.length && (sql[cursor].isLetterOrDigit() || sql[cursor] == '_')) cursor++
        val closed = cursor < sql.length && sql[cursor] == '$'
        return if (closed) sql.substring(index, cursor + 1) else null
    }

    /** 달러 인용 본문을 건너뛴다. 같은 태그로 닫히지 않으면 조각 끝까지 간다. */
    private fun skipDollarQuoted(
        sql: String,
        start: Int,
        tag: String,
        out: StringBuilder,
    ): Int {
        repeat(tag.length) { out.append(' ') }
        var index = start + tag.length
        while (index < sql.length) {
            if (sql.startsWith(tag, index)) {
                repeat(tag.length) { out.append(' ') }
                return index + tag.length
            }
            out.append(if (sql[index] == '\n') '\n' else ' ')
            index++
        }
        return index
    }
}

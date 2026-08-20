package kr.easydoc.infrastructure.db

import kr.easydoc.core.crypto.EncryptedField
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * **암호문 열을 쓰는 UPDATE 는 봉투 두 값도 같은 문장에서 써야 한다** — 게이트 27 지적 ②.
 *
 * ## 왜 산문으로는 부족했나
 *
 * `ConversionRepository`·`DocumentRepository` KDoc 은 *"열 하나짜리 갱신 메서드를 만들지
 * 않는다"* 를 단일 UPDATE 강제의 근거로 들었다. 그런데 그것을 지키던 것은 **「지금 그런
 * 메서드가 없다」는 사실뿐이고 탐지기가 0개였다.** Phase 5 워커가 `updateEasyText(id, bytes)`
 * 를 더하는 순간 세대 v1 로 라벨된 행에 v2 암호문이 들어가고, 그 행은 **영원히 열리지
 * 않는다** — AAD 에 세대가 실리므로 태그 검증부터 실패한다.
 *
 * 프로젝트 규칙 4 가 정한 처방은 「금지 선언」을 넓히는 것이 아니라 **탐지형으로 갈아타는
 * 것**이다. 그래서 이 파일은 소스 전수에서 SQL 을 뽑아 **깨지면 빨개지는** 단언을 건다.
 *
 * ## 분모를 열거하지 않는다
 *
 * 감시 대상 테이블·열을 손으로 적지 않는다 — [EncryptedField] 의 `wireName`(`테이블.컬럼`)
 * 에서 **파생**한다. 열거하면 다섯 번째 암호문 열이 생겼을 때 그 열만 영영 탐지 밖에
 * 남는다(`ProvenanceCreationSitesTest` 가 감시 대상을 소스 선언과 대조하는 것과 같은 구조).
 *
 * ## 빈 분모는 통과가 아니다
 *
 * 대상 문장을 하나도 못 찾으면 **빨강**이다(규칙 4 ⑶). 저장소의 parity 게이트가 「선언
 * 도메인 0개에서 exit 0」이었던 것이 정확히 이 결함이고, 같은 자리를 다시 만들지 않는다.
 * [Scanner.requireNonEmpty] 가 그 판정이고, [`빈 분모는 통과가 아니다`] 가 그것을 실행으로
 * 확인한다.
 *
 * ## 이 장치가 **막지 못하는 것** (정직하게 적는다)
 *
 * - 문자열을 조립해 만든 SQL(`"UPDATE ${'$'}table SET …"`). 스캐너가 테이블 이름을
 *   못 읽으므로 대상에서 빠진다. **이 파일의 합성 probe 가 바로 그 형태**이고, 그래서
 *   probe 가 실제 스캔의 분모를 오염시키지 않는다 — 우회 통로이자 이 파일이 쓰는 통로다.
 *   막으려면 SQL 조립을 금지해야 하는데 그 금지의 강제자가 또 없다. 한 칸 더 옮기지 않는다.
 * - 저장 프로시저·마이그레이션 밖의 손 SQL·DB 클라이언트에서 직접 친 UPDATE.
 * - 이 파일 자신의 삭제. 최종 방어선은 `tests/test_kotlin_gate_reach.py` 의 선언 대조다.
 *
 * ## 주석은 **방향에 따라 다르게** 다룬다
 *
 * 하나의 정규화를 두 방향에 같이 쓰면 한쪽은 반드시 뒤집힌다. 실제로 뒤집혀 있었다 —
 * 초판은 **원시 SET 절에 봉투 열 정규식을 그대로** 걸어서
 * `SET 암호문열 = :value -- , encryption_scheme = :s, key_version = :v` 를 **준수로 통과**
 * 시켰다(실측). PostgreSQL 은 `--` 뒤와 블록 주석 안을 무시하므로 실제로는 암호문만
 * 바뀌고 세대는 그대로다 — 이 파일이 막으라고 세워진 바로 그 행, **영원히 열리지 않는 행**을
 * 가드가 승인한 것이다. 형제 장치 [OwnershipPredicateGuardTest] 가 같은 결함을 같은 날
 * 같은 기제로 갖고 있었고(소유 술어 쪽), 처방도 같다.
 *
 * **⑴ 문장 발견(분모)** — 이 UPDATE 가 암호문 쓰기인가. 주석을 **그대로 둔다**: Kotlin
 * 주석·KDoc 이 품은 SQL 도 센다. 분모를 넓히는 쪽이라 **fail-closed** 다. 문자열 리터럴만
 * 골라내는 렉서를 쓰지 않는 이유도 여기다 — 렉서 자신이 조용히 놓치는 표면이 된다. 예시를
 * 적고 싶으면 규약을 지키는 예시를 적거나 테이블 이름을 조립해 쓰라.
 *
 * **⑵ 준수 판정** — 이 문장이 봉투 두 열을 **썼는가**. **SQL 주석을 걷어낸 뒤** 판정한다
 * ([SqlComments.strip]). 죽은 대입을 「썼다」로 세면 **fail-open** 이다. 걷어내면
 * fail-closed — 대입이 사라진 문장은 위반으로 올라온다.
 *
 * **이 둘을 하나로 합치지 마라.** ⑴ 의 근거(**분모는 넓을수록 안전하다**)를 ⑵ 에 옮겨
 * 적는 순간 이 결함이 그대로 되살아난다 — ⑵ 에서는 넓은 쪽이 **위험한 쪽**이다.
 *
 * ## 이 갈라치기가 **남기는 것** (정직하게 적는다)
 *
 * [Scanner.setClauseOf] 의 경계 찾기(`SET`·`WHERE` 키워드)는 여전히 **원시** 문장을 본다.
 * 주석에 든 `WHERE` 가 절을 일찍 끊으면 뒤의 진짜 봉투 대입이 잘려 **위반으로** 올라오고
 * (과잉 탐지 = fail-closed), 주석에 든 `SET` 은 절을 넓게 잡지만 넓어진 부분은 준수 판정
 * 직전에 걷어내기가 지운다. 둘 다 fail-open 이 아니라 그대로 둔다 — 경계 찾기를 걷어낸
 * 사본으로 옮기면 인덱스가 원문과 어긋나 [Scanner.CiphertextWrite.setClause] 가 실패
 * 메시지에서 엉뚱한 조각을 가리킨다.
 *
 * Kotlin 블록 주석·KDoc 은 문법이 블록 주석이라 걷어내기가 함께 지운다 — KDoc 이 품은
 * 예시 SQL 은 분모에 남고 봉투 대입은 잃어 **위반으로** 올라온다. 과잉 탐지 방향이다.
 */
class EnvelopeColumnWriteGuardTest {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("암호문 열을 SET 하는 모든 UPDATE 가 `encryption_scheme`·`key_version` 도 함께 SET 한다")
    fun `암호문 쓰기는 봉투를 함께 쓴다`() {
        val writes = Scanner.scan(sourceRoot())
        Scanner.requireNonEmpty(writes)

        val offenders = writes.filterNot { it.setsEnvelope }

        assertThat(offenders)
            .withFailMessage {
                "암호문 열을 쓰면서 봉투 두 값을 함께 쓰지 않는 UPDATE 가 있다:\n" +
                    offenders.joinToString("\n") { "  - ${it.file}\n      ${it.setClause.trim()}" } +
                    "\n  행당 키 세대가 하나라, 암호문만 바꾸면 「세대는 v1 인데 암호문은 v2」인 행이 남고\n" +
                    "  그 행은 영원히 열리지 않는다(AAD 에 세대가 실린다). 같은 문장에서\n" +
                    "  encryption_scheme 과 key_version 도 SET 하라."
            }.isEmpty()
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 대상 SQL 을 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        // 실제 스캔의 분모부터 확인한다. 여기가 0이면 위 케이스는 「위반 0건」으로 초록인데
        // 실제로는 아무것도 재지 않은 것이다.
        val writes = Scanner.scan(sourceRoot())

        assertThat(writes.map { it.file }.distinct())
            .describedAs("암호문 열을 쓰는 SQL 이 사는 파일")
            .isEqualTo(EXPECTED_FILES)
        assertThat(writes)
            .describedAs("암호문 쓰기 문장 수 — 늘거나 줄면 그 diff 가 리뷰에 올라가야 한다")
            .hasSize(EXPECTED_STATEMENTS)

        // 그리고 「0건이면 실패한다」는 판정 자체를 실행으로 확인한다.
        assertThatThrownBy { Scanner.requireNonEmpty(emptyList()) }
            .hasMessageContaining("한 건도 찾지 못했다")
    }

    @Test
    @DisplayName("스캐너 음성 대조 — 봉투를 빠뜨린 SQL 을 심으면 잡고, 지키는 SQL 은 통과시킨다")
    fun `스캐너가 위반과 준수를 가른다`() {
        val violating = probe("violating", "UPDATE $target SET $column = :value WHERE id = :id")
        val compliant =
            probe(
                "compliant",
                "UPDATE $target SET $column = :value, encryption_scheme = :s, key_version = :v WHERE id = :id",
            )

        assertThat(violating.single().setsEnvelope).isFalse()
        assertThat(compliant.single().setsEnvelope).isTrue()
    }

    @Test
    @DisplayName("암호문 열이 `WHERE` 절에만 나오는 UPDATE 는 암호문 쓰기가 아니다 — 과잉 탐지 0")
    fun `조건절의 암호문 열은 쓰기가 아니다`() {
        assertThat(probe("where-only", "UPDATE $target SET status = :status WHERE $column IS NULL")).isEmpty()
    }

    @Test
    @DisplayName("소문자 SQL 과 대상 아닌 테이블을 가른다 — 대소문자로 빠져나갈 수 없다")
    fun `대소문자와 대상 테이블을 가른다`() {
        val lower = probe("lower", "update $target set $column = :v where id = :id")
        val other = probe("other", "UPDATE workspaces SET name = :name WHERE id = :id")

        assertThat(lower.single().setsEnvelope).isFalse()
        assertThat(other).isEmpty()
    }

    @Test
    @DisplayName("**주석에 든 봉투 열은 쓴 것이 아니다** — `--` 로 죽은 대입을 준수로 세지 않는다")
    fun `줄 주석에 든 봉투 대입은 준수가 아니다`() {
        val commented =
            probe(
                "line-commented",
                "UPDATE $target SET $column = :value -- , encryption_scheme = :s, key_version = :v\n" +
                    "WHERE id = :id",
            )

        assertThat(commented.single().setsEnvelope)
            .describedAs(
                "`--` 뒤는 PostgreSQL 이 무시한다. 실제 UPDATE 는 암호문만 바꾸고 세대는 그대로라 " +
                    "그 행은 영원히 열리지 않는데, 가드가 준수로 읽으면 그것을 승인한다 — fail-open 이다",
            ).isFalse()
    }

    @Test
    @DisplayName("**블록 주석에 든 봉투 열도 쓴 것이 아니다** — 중첩까지 끝까지 걷어낸다")
    fun `블록 주석에 든 봉투 대입은 준수가 아니다`() {
        val commented =
            probe(
                "block-commented",
                "UPDATE $target SET $column = :value, /* 보류 /* 사유 */ encryption_scheme = :s, " +
                    "key_version = :v */ status = :status WHERE id = :id",
            )

        assertThat(commented.single().setsEnvelope)
            .describedAs("블록 주석 안도 PostgreSQL 이 무시한다 — 중첩이라 첫 닫힘에서 끊으면 잔여가 남는다")
            .isFalse()
    }

    @Test
    @DisplayName("주석 제거가 **참인 대입을 깨뜨리지 않는다** — 대입 뒤에 붙은 설명 주석은 무해하다")
    fun `살아 있는 봉투 대입 뒤의 주석은 대입을 죽이지 않는다`() {
        val compliant =
            probe(
                "trailing-comment",
                "UPDATE $target SET $column = :value, encryption_scheme = :s, key_version = :v " +
                    "-- 세대를 함께 올린다\nWHERE id = :id",
            )

        assertThat(compliant.single().setsEnvelope)
            .describedAs("주석 제거는 죽은 대입만 지워야 한다 — 살아 있는 대입까지 지우면 과잉 탐지로 뒤집힌다")
            .isTrue()
    }

    @Test
    @DisplayName("SET 절이 없는 UPDATE 는 조용히 넘기지 않고 끊는다")
    fun `해석할 수 없는 UPDATE 는 끊는다`() {
        assertThatThrownBy { probe("broken", "UPDATE $target WHERE id = :id") }
            .hasMessageContaining("SET")
    }

    /**
     * probe 가 쓰는 테이블·열 이름. **리터럴로 적지 않고 [EncryptedField] 에서 조립한다.**
     *
     * 조립하는 이유는 두 가지다. ⑴ 이름의 근거를 한 곳(enum)에 둔다. ⑵ 조립한 문자열은
     * 스캐너가 읽지 못하므로 **이 파일의 probe 가 실제 스캔의 분모를 오염시키지 않는다** —
     * 위반 SQL 을 리터럴로 적었더니 실제 스캔이 그것을 위반으로 잡았다(실측).
     */
    private val target: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringBefore('.')
    private val column: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringAfter('.')

    /**
     * 합성 소스 하나를 스캐너에 먹인다.
     *
     * **probe 마다 독립 디렉터리**를 준다. 같은 디렉터리에 쌓으면 뒤 probe 의 결과에 앞
     * probe 의 문장이 섞여, 「대상 아닌 테이블은 잡히지 않는다」 같은 단언이 앞 케이스 때문에
     * 빨개진다(실측으로 밟았다).
     */
    private fun probe(
        name: String,
        sql: String,
    ): List<Scanner.CiphertextWrite> {
        val directory = File(temp, name).apply { mkdirs() }
        File(directory, "probe.kt").writeText("package probe\n\nval sql = \"\"\"\n$sql\n\"\"\"\n")
        return Scanner.scan(directory.toPath())
    }

    private fun sourceRoot(): Path {
        val configured =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $SOURCE_ROOT_PROPERTY 가 없다. 이 가드는 소스 전수를 훑어야 " +
                        "의미가 있는데, 경로를 못 찾으면 0개 파일을 훑고 통과한다 — 그것은 통과가 아니라 미검사다.",
                )
        val root = Paths.get(configured)
        check(Files.isDirectory(root)) { "소스 루트가 디렉터리가 아니다: $root" }
        return root
    }

    /**
     * `documents`·`conversions` 를 UPDATE 하는 SQL 중 **암호문 열을 SET 하는 것**을 뽑는다.
     *
     * 파서가 아니라 훑개다. 문자열 리터럴만 골라내는 렉서를 두지 않는 이유는 KDoc 에 있다 —
     * 과잉 탐지 방향이라 fail-closed 이고, 렉서는 그 자체가 조용히 놓치는 표면이 된다.
     */
    private object Scanner {
        /** 암호문 열 한 곳을 쓰는 UPDATE 문 하나. */
        data class CiphertextWrite(
            val file: String,
            val setClause: String,
            val setsEnvelope: Boolean,
        )

        /** 봉투 두 열. 이름이 스키마와 갈리면 `EncryptionSchemeSchemaTest` 가 먼저 빨개진다. */
        private val ENVELOPE_COLUMNS = listOf("encryption_scheme", "key_version")

        /** 감시 대상 테이블·열. **열거가 아니라 [EncryptedField] 에서 파생한다.** */
        private val TABLES: Set<String> = EncryptedField.entries.map { it.wireName.substringBefore('.') }.toSet()
        private val CIPHERTEXT_COLUMNS: Set<String> =
            EncryptedField.entries.map { it.wireName.substringAfter('.') }.toSet()

        private val UPDATE_TARGET =
            Regex("""\bUPDATE\s+(${TABLES.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
        private val SET_KEYWORD = Regex("""\bSET\b""", RegexOption.IGNORE_CASE)
        private val WHERE_KEYWORD = Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE)

        /** 문장이 끝났다고 볼 자리. 이 저장소의 SQL 은 전부 Kotlin 문자열 안에 산다. */
        private val STATEMENT_END = Regex("""(;|\"\"\"|\")""")

        fun scan(root: Path): List<CiphertextWrite> =
            kotlinSources(root).flatMap { file ->
                val relative = root.relativize(file).joinToString("/")
                writesIn(relative, file.readText())
            }

        /**
         * **한 건도 없으면 끊는다.** 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(writes: List<CiphertextWrite>) {
            check(writes.isNotEmpty()) {
                "암호문 열을 쓰는 UPDATE 를 한 건도 찾지 못했다 — 검사 대상 0건은 통과가 아니라 실패다. " +
                    "스캐너가 소스를 못 읽었거나(경로·확장자), 저장 경로가 통째로 사라졌다."
            }
        }

        private fun writesIn(
            file: String,
            text: String,
        ): List<CiphertextWrite> =
            UPDATE_TARGET
                .findAll(text)
                .mapNotNull { match ->
                    val setClause = setClauseOf(file, text, match.range.last + 1)
                    // 분모는 **원시** SET 절로 판정한다 — 주석에 든 암호문 대입까지 대상에 넣는 쪽이
                    // 넓은 쪽이고, 넓은 쪽이 fail-closed 다.
                    if (CIPHERTEXT_COLUMNS.none { assignsColumn(setClause, it) }) {
                        null
                    } else {
                        CiphertextWrite(
                            file = file,
                            setClause = setClause,
                            // 준수 판정은 **주석을 걷어낸** SET 절로 한다. 여기서는 넓은 쪽이 위험한
                            // 쪽이다 — 주석에 든 봉투 대입을 「썼다」로 세면 세대가 안 오른 행을
                            // 준수로 승인한다. 클래스 KDoc 「주석은 방향에 따라 다르게 다룬다」.
                            setsEnvelope =
                                SqlComments.strip(setClause).let { live ->
                                    ENVELOPE_COLUMNS.all { assignsColumn(live, it) }
                                },
                        )
                    }
                }.toList()

        /** `SET` 과 `WHERE`(또는 문장 끝) 사이. 조건절을 섞으면 `WHERE key_version = …` 이 오탐한다. */
        private fun setClauseOf(
            file: String,
            text: String,
            from: Int,
        ): String {
            val end = STATEMENT_END.find(text, from)?.range?.first ?: text.length
            val statement = text.substring(from, end)
            val set =
                SET_KEYWORD.find(statement)
                    ?: error("$file 의 UPDATE 문에서 SET 절을 찾지 못했다 — 해석할 수 없는 문장을 조용히 넘기지 않는다: $statement")
            val body = statement.substring(set.range.last + 1)
            return WHERE_KEYWORD.find(body)?.let { body.substring(0, it.range.first) } ?: body
        }

        private fun assignsColumn(
            setClause: String,
            column: String,
        ): Boolean = Regex("""(?<![A-Za-z0-9_])$column\s*=""").containsMatchIn(setClause)

        /** Gradle 산출물은 소스가 아니다 — 넣으면 같은 파일을 두 번 센다. */
        private fun kotlinSources(root: Path): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { root.relativize(it).none { part -> part.toString() == "build" } }
                    .sorted()
                    .toList()
            }
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /**
         * 암호문 열을 쓰는 SQL 이 사는 파일. **면제 목록이 아니라 인구조사다** — 여기 없는
         * 파일이 봐주는 것이 아니라, 목록이 바뀌면 그 diff 가 리뷰에 올라온다.
         */
        val EXPECTED_FILES =
            listOf(
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionRepository.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcDocumentRepository.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/EnvelopeRotationConcurrencyTest.kt",
            )

        /** 문장 수. 파일 목록만 보면 **같은 파일 안에 한 문장을 더 넣는 편집**이 조용하다. */
        const val EXPECTED_STATEMENTS = 5
    }
}

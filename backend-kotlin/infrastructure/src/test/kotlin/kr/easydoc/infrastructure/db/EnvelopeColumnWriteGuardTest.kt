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

/** 암호문 열을 쓰는 UPDATE 는 봉투 두 값도 같은 문장에서 써야 한다 — 게이트 27 지적 ②. */
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
        val writes = Scanner.scan(sourceRoot())

        assertThat(writes.map { it.file }.distinct())
            .describedAs("암호문 열을 쓰는 SQL 이 사는 파일")
            .isEqualTo(EXPECTED_FILES)
        assertThat(writes)
            .describedAs("암호문 쓰기 문장 수 — 늘거나 줄면 그 diff 가 리뷰에 올라가야 한다")
            .hasSize(EXPECTED_STATEMENTS)

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
    @DisplayName("**문자열 리터럴에 든 봉투 열은 쓴 것이 아니다** — 게이트 28 P-7 #5 (미선언 fail-open)")
    fun `문자열 리터럴에 든 봉투 대입은 준수가 아니다`() {
        val literal =
            probe(
                "literal",
                "UPDATE $target SET $column = :value, " +
                    "status = 'encryption_scheme = :s, key_version = :v' WHERE id = :id",
            )

        assertThat(literal.single().setsEnvelope)
            .describedAs(
                "작은따옴표 안은 **값**이다 — PostgreSQL 은 봉투 열을 하나도 대입하지 않는다. " +
                    "가드가 준수로 읽으면 세대가 오르지 않은 암호문을 승인하고, AAD 에 세대가 실리므로 " +
                    "그 행은 영원히 열리지 않는다. 이 갈래는 아래 「막지 못하는 것」에 적혀 있지 않았다",
            ).isFalse()
    }

    @Test
    @DisplayName("리터럴 걷어내기가 **참인 대입을 깨뜨리지 않는다** — 리터럴과 살아 있는 대입이 한 문장에 있어도")
    fun `리터럴 뒤의 살아 있는 봉투 대입은 살아 남는다`() {
        val compliant =
            probe(
                "literal-then-envelope",
                "UPDATE $target SET status = 'done', $column = :value, " +
                    "encryption_scheme = :s, key_version = :v WHERE id = :id",
            )

        assertThat(compliant.single().setsEnvelope)
            .describedAs("리터럴 걷어내기가 리터럴 **밖**을 지우면 과잉 탐지로 뒤집힌다 — 이 방향을 고정한다")
            .isTrue()
    }

    @Test
    @DisplayName("리터럴 안의 `''` 를 리터럴 종료로 읽지 않는다 — 짧게 읽으면 뒤가 술어로 되살아난다")
    fun `escaped quote 는 리터럴을 닫지 않는다`() {
        val literal =
            probe(
                "escaped-quote",
                "UPDATE $target SET $column = :value, " +
                    "status = 'a''b encryption_scheme = :s, key_version = :v' WHERE id = :id",
            )

        assertThat(literal.single().setsEnvelope)
            .describedAs("`''` 는 escaped quote 라 리터럴이 계속된다 — 여기서 끊으면 뒤의 텍스트가 대입으로 읽힌다")
            .isFalse()
    }

    @Test
    @DisplayName("SET 절이 없는 UPDATE 는 조용히 넘기지 않고 끊는다")
    fun `해석할 수 없는 UPDATE 는 끊는다`() {
        assertThatThrownBy { probe("broken", "UPDATE $target WHERE id = :id") }
            .hasMessageContaining("SET")
    }

    /** probe 가 쓰는 테이블·열 이름. 리터럴로 적지 않고 [EncryptedField] 에서 조립한다. */
    private val target: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringBefore('.')
    private val column: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringAfter('.')

    /** 합성 소스 하나를 스캐너에 먹인다. */
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

    /** `documents`·`conversions` 를 UPDATE 하는 SQL 중 암호문 열을 SET 하는 것을 뽑는다. */
    private object Scanner {
        /** 암호문 열 한 곳을 쓰는 UPDATE 문 하나. */
        data class CiphertextWrite(
            val file: String,
            val setClause: String,
            val setsEnvelope: Boolean,
        )

        /** 봉투 두 열. 이름이 스키마와 갈리면 `EncryptionSchemeSchemaTest` 가 먼저 빨개진다. */
        private val ENVELOPE_COLUMNS = listOf("encryption_scheme", "key_version")

        /** 감시 대상 테이블·열. 열거가 아니라 [EncryptedField] 에서 파생한다. */
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
         * 한 건도 없으면 끊는다. 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
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

                    if (CIPHERTEXT_COLUMNS.none { assignsColumn(setClause, it) }) {
                        null
                    } else {
                        CiphertextWrite(
                            file = file,
                            setClause = setClause,
                            setsEnvelope =
                                LiveSql.of(setClause).let { live ->
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
         * 암호문 열을 쓰는 SQL 이 사는 파일. 면제 목록이 아니라 인구조사다 — 여기 없는
         * 파일이 봐주는 것이 아니라, 목록이 바뀌면 그 diff 가 리뷰에 올라온다.
         */
        val EXPECTED_FILES =
            listOf(
                "api/src/test/kotlin/kr/easydoc/api/ConversionExportReachTest.kt",
                // 피드백 실경로 테스트도 완료 상태를 SQL 로 심는다 — 그 문장이 봉투를 함께 쓴다.
                "api/src/test/kotlin/kr/easydoc/api/ConversionFeedbackReachTest.kt",
                "api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTest.kt",
                "api/src/test/kotlin/kr/easydoc/api/ConversionReviewReachTest.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionRepository.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionWorkStore.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcDocumentRepository.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/ConversionReviewStorageTest.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/EnvelopeRotationConcurrencyTest.kt",
            )

        /** 문장 수. 파일 목록만 보면 같은 파일 안에 한 문장을 더 넣는 편집이 조용하다. */
        const val EXPECTED_STATEMENTS = 12
    }
}

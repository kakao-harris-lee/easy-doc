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
 * 문서·변환 행에 닿는 제품 SQL 중 소유 매개변수가 걸리지 않은 문장을 전수에서 뽑는다 —
 * 게이트 27 M-3 (`privacy-gate` 판정 §4.2 후보 A).
 */
class OwnershipPredicateGuardTest {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("문서·변환에 닿는 제품 SQL 중 소유 술어가 없는 것은 정확 열거 핀 안에만 있다")
    fun `소유 술어 없는 질의는 핀 안에만 있다`() {
        val accesses = Scanner.scan(sourceRoot())
        Scanner.requireNonEmpty(accesses)

        val unguarded = accesses.filterNot { it.hasOwnerPredicate }

        assertThat(unguarded.map { it.pin })
            .withFailMessage { unguardedFailure(unguarded) }
            .isEqualTo(EXPECTED_UNGUARDED)
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 대상 문장을 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        assertThat(Scanner.scan(sourceRoot()).map { it.pin })
            .describedAs("문서·변환에 닿는 제품 SQL 인구조사 — 늘거나 줄면 그 diff 가 리뷰에 올라가야 한다")
            .isEqualTo(EXPECTED_STATEMENTS)

        assertThatThrownBy { Scanner.requireNonEmpty(emptyList()) }
            .hasMessageContaining("한 건도 찾지 못했다")
        assertThatThrownBy { Scanner.requireNonEmpty(Scanner.scan(emptyRoot())) }
            .hasMessageContaining("한 건도 찾지 못했다")
    }

    @Test
    @DisplayName("`settings.gradle.kts` 가 선언한 모듈이 전부 분모에 기여한다 — 모듈이 통째로 빠지지 않는다")
    fun `선언된 모듈이 전부 분모에 들어 있다`() {
        val root = sourceRoot()
        val scanned = Scanner.productSources(root).map { root.relativize(it).first().toString() }.toSet()

        assertThat(Scanner.declaredModules(root))
            .describedAs("선언 모듈 전부가 제품 소스 스캔에 기여해야 한다 — 하나가 빠지면 그 모듈의 SQL 이 통째로 탐지 밖이다")
            .isNotEmpty()
            .allSatisfy { module -> assertThat(scanned).contains(module) }
    }

    @Test
    @DisplayName("스캐너 음성 대조 — 소유 술어 없는 질의는 잡고, 조인으로 소유를 좁힌 질의는 통과시킨다")
    fun `스캐너가 소유 술어의 유무를 가른다`() {
        val unguarded = probe("unguarded", "SELECT $column FROM $conversions WHERE id = :id")
        val guarded =
            probe(
                "guarded",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id AND d.user_id = :ownerId",
            )

        assertThat(unguarded.single().hasOwnerPredicate).isFalse()
        assertThat(guarded.single().hasOwnerPredicate).isTrue()
    }

    @Test
    @DisplayName("**주석에 든 소유 술어는 방어가 아니다** — `--` 로 죽은 술어를 방어로 세지 않는다")
    fun `줄 주석에 든 소유 술어는 방어가 아니다`() {
        val commented =
            probe(
                "line-commented",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id -- AND d.user_id = :ownerId",
            )

        assertThat(commented.single().hasOwnerPredicate)
            .describedAs(
                "`--` 뒤는 PostgreSQL 이 무시한다. 실제 질의에 소유 조건이 없는데 방어가 있다고 읽으면 " +
                    "이 가드가 소유권 우회를 승인한다 — fail-open 이다",
            ).isFalse()
    }

    @Test
    @DisplayName("**블록 주석에 든 소유 술어도 방어가 아니다** — `/* */` 안은 질의가 아니다")
    fun `블록 주석에 든 소유 술어는 방어가 아니다`() {
        val commented =
            probe(
                "block-commented",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id /* AND d.user_id = :ownerId */",
            )

        assertThat(commented.single().hasOwnerPredicate)
            .describedAs("`/* */` 안도 PostgreSQL 이 무시한다 — 줄 주석과 같은 fail-open 이다")
            .isFalse()
    }

    @Test
    @DisplayName("**중첩 블록 주석**도 끝까지 걷어낸다 — 첫 닫힘에서 끊으면 죽은 술어가 되살아난다")
    fun `중첩 블록 주석에 든 소유 술어는 방어가 아니다`() {
        val nested =
            probe(
                "nested-comment",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id /* 보류 /* 사유 */ AND d.user_id = :ownerId */",
            )

        assertThat(nested.single().hasOwnerPredicate)
            .describedAs(
                "PostgreSQL 블록 주석은 중첩한다 — 첫 닫힘에서 끊는 비탐욕 정규식이라면 " +
                    "`AND d.user_id = :ownerId` 를 주석 밖으로 남겨 fail-open 이 된다",
            ).isFalse()
    }

    @Test
    @DisplayName("주석 제거가 **참인 술어를 깨뜨리지 않는다** — 술어 뒤에 붙은 설명 주석은 무해하다")
    fun `살아 있는 술어 뒤의 주석은 술어를 죽이지 않는다`() {
        val guarded =
            probe(
                "trailing-comment",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id AND d.user_id = :ownerId -- 소유자로 좁힌다",
            )

        assertThat(guarded.single().hasOwnerPredicate)
            .describedAs("주석 제거는 죽은 술어만 지워야 한다 — 살아 있는 술어까지 지우면 과잉 탐지로 뒤집힌다")
            .isTrue()
    }

    @Test
    @DisplayName("**문자열 리터럴에 든 소유 술어는 방어가 아니다** — 게이트 28 P-7 #3 의 미선언 갈래")
    fun `문자열 리터럴에 든 소유 술어는 방어가 아니다`() {
        val literal =
            probe(
                "literal",
                "SELECT 'user_id = :ownerId' AS note, c.$column FROM $conversions c WHERE c.id = :id",
            )

        assertThat(literal.single().hasOwnerPredicate)
            .describedAs(
                "작은따옴표 안은 **값**이다 — 실제 질의에 소유 조건이 없다. 반례 5종 중 넷은 이미 " +
                    "「막지 못하는 것」에 선언돼 있었고 이 갈래만 미선언이었다(리더 판정 P-7)",
            ).isFalse()
    }

    @Test
    @DisplayName("리터럴 걷어내기가 **참인 술어를 깨뜨리지 않는다** — 리터럴과 술어가 한 문장에 있어도")
    fun `리터럴 뒤의 살아 있는 술어는 살아 남는다`() {
        val guarded =
            probe(
                "literal-then-predicate",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.status = 'done' AND d.user_id = :ownerId",
            )

        assertThat(guarded.single().hasOwnerPredicate)
            .describedAs("리터럴 걷어내기가 리터럴 **밖**을 지우면 과잉 탐지로 뒤집힌다 — 이 방향을 고정한다")
            .isTrue()
    }

    @Test
    @DisplayName("소유 열을 매개변수가 아닌 것과 묶은 비교는 소유 술어가 아니다 — 과소 탐지 0")
    fun `컬럼끼리의 비교는 소유 술어가 아니다`() {
        val joined = probe("joined", "SELECT 1 FROM $documents d JOIN owners o ON d.user_id = o.user_id")

        assertThat(joined.single().hasOwnerPredicate).isFalse()
    }

    @Test
    @DisplayName("소문자 SQL 과 감시 대상 아닌 테이블을 가른다 — 대소문자로 빠져나갈 수 없다")
    fun `대소문자와 대상 테이블을 가른다`() {
        val lower = probe("lower", "select id from $documents where id = :id")
        val other = probe("other", "SELECT id FROM workspaces WHERE id = :id")

        assertThat(lower.single().hasOwnerPredicate).isFalse()
        assertThat(other).isEmpty()
    }

    @Test
    @DisplayName("SQL 동사를 찾을 수 없는 문장은 조용히 넘기지 않고 끊는다")
    fun `해석할 수 없는 문장은 끊는다`() {
        assertThatThrownBy { probe("verbless", "잘린 조각 FROM $documents WHERE id = :id") }
            .hasMessageContaining("SQL 동사")
    }

    /** probe 가 쓰는 테이블·열 이름. 리터럴로 적지 않고 [EncryptedField] 에서 조립한다. */
    private val documents: String get() = EncryptedField.DOCUMENT_SOURCE_TEXT.wireName.substringBefore('.')
    private val conversions: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringBefore('.')
    private val column: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringAfter('.')

    /** 합성 소스 하나를 스캐너에 먹인다. */
    private fun probe(
        name: String,
        sql: String,
    ): List<Scanner.TableAccess> {
        val directory = File(temp, "$name/src/main/kotlin").apply { mkdirs() }
        File(directory, "Probe.kt").writeText("package probe\n\nval sql = \"\"\"\n$sql\n\"\"\"\n")
        return Scanner.scan(File(temp, name).toPath())
    }

    /** 아무 소스도 없는 루트. 「분모 0」을 합성 인자가 아니라 실제 훑기로 만든다. */
    private fun emptyRoot(): Path {
        File(temp, "empty/src/main/kotlin").mkdirs()
        return File(temp, "empty").toPath()
    }

    private fun unguardedFailure(unguarded: List<Scanner.TableAccess>): String =
        "문서·변환 행에 닿는 제품 SQL 중 소유 매개변수가 걸리지 않은 문장 목록이 핀과 다르다.\n" +
            unguarded.joinToString("\n") { "  - ${it.pin}\n      ${it.statement}" } +
            "\n  기대(정확 열거 핀):\n" +
            EXPECTED_UNGUARDED.joinToString("\n") { "  - $it" } +
            "\n  늘었다면 그 문장이 남의 행을 내줄 수 있는지 먼저 판정하라 — 사용자 요청 경로라면\n" +
            "  소유 조건을 SQL `WHERE` 안에 넣어야 한다(읽고 나서 비교하는 형태는 금지다).\n" +
            "  줄었다면 지켜지던 질의가 사라진 것이니 그것도 diff 로 설명돼야 한다.\n" +
            "  핀은 면제 목록이 아니라 인구조사다 — 이름 패턴으로 예외를 만들지 마라."

    private fun sourceRoot(): Path {
        val configured =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $SOURCE_ROOT_PROPERTY 가 없다. 이 가드는 제품 소스 전수를 훑어야 " +
                        "의미가 있는데, 경로를 못 찾으면 0개 파일을 훑고 통과한다 — 그것은 통과가 아니라 미검사다.",
                )
        val root = Paths.get(configured)
        check(Files.isDirectory(root)) { "소스 루트가 디렉터리가 아니다: $root" }
        return root
    }

    /** 감시 테이블에 닿는 SQL 문장을 제품 소스에서 뽑고, 소유 매개변수의 유무를 판정한다. */
    private object Scanner {
        /** 감시 테이블에 닿는 문장 하나. */
        data class TableAccess(
            val file: String,
            val verb: String,
            val tables: List<String>,
            val statement: String,
            val hasOwnerPredicate: Boolean,
        ) {
            /** 핀에 적히는 표기. */
            val pin: String get() = "$file | $verb [${tables.joinToString(", ")}]"
        }

        /** 감시 대상 테이블. 열거가 아니라 [EncryptedField] 에서 파생한다. */
        private val TABLES: List<String> =
            EncryptedField.entries
                .map { it.wireName.substringBefore('.') }
                .distinct()
                .sorted()

        /** 문장이 이 테이블에 닿는가. 이름 앞의 낱말로 「SQL 안의 테이블 자리」인지 가른다. */
        private val TABLE_REFERENCE =
            Regex("""\b(?:FROM|JOIN|UPDATE|INTO)\s+(${TABLES.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

        private val VERB = Regex("""\b(SELECT|INSERT|UPDATE|DELETE|WITH)\b""", RegexOption.IGNORE_CASE)

        /**
         * 소유 열을 매개변수와 묶은 자리. 이름 붙은 매개변수(`:이름`)와 위치 매개변수(`?`)
         * 둘 다 받는다. 컬럼끼리의 비교는 받지 않는다 — 그것은 소유를 좁히지 않는다.
         */
        private val OWNER_PREDICATE =
            Regex("""(?<![A-Za-z0-9_])user_id\s*=\s*(?::[A-Za-z_]\w*|\?)""", RegexOption.IGNORE_CASE)

        /** 문장이 끝났다고 볼 자리. 이 저장소의 SQL 은 전부 Kotlin 문자열 안에 산다. */
        private val STATEMENT_END = Regex("""(;|\"\"\"|\")""")

        /** `include("core", …)` 에서 모듈 이름을 뽑는다. */
        private val INCLUDE = Regex("""^include\(([^)]*)\)""", RegexOption.MULTILINE)
        private val QUOTED = Regex("\"([A-Za-z0-9_-]+)\"")

        fun scan(root: Path): List<TableAccess> =
            productSources(root).flatMap { file ->
                accessesIn(root.relativize(file).joinToString("/"), file.readText())
            }

        /**
         * 한 건도 없으면 끊는다. 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(accesses: List<TableAccess>) {
            check(accesses.isNotEmpty()) {
                "문서·변환 테이블에 닿는 제품 SQL 을 한 건도 찾지 못했다 — 검사 대상 0건은 통과가 아니라 실패다. " +
                    "스캐너가 소스를 못 읽었거나(경로·확장자·소스셋 이름), 저장 경로가 통째로 사라졌다."
            }
        }

        /** 제품 소스. `src/main` 아래의 `.kt` 를 깊이 제한 없이 걷는다. */
        fun productSources(root: Path): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { isProductSource(root.relativize(it)) }
                    .sorted()
                    .toList()
            }

        /** `settings.gradle.kts` 의 `include` 선언에서 파생한 모듈 이름. */
        fun declaredModules(root: Path): List<String> {
            val settings = root.resolve("settings.gradle.kts")
            check(Files.isRegularFile(settings)) {
                "$settings 를 찾지 못했다 — 모듈 목록을 파생할 기준이 없다"
            }
            val declaration =
                INCLUDE.find(settings.readText())
                    ?: error("$settings 에 `include(...)` 선언이 없다 — 모듈 목록을 파생할 기준이 없다")
            return QUOTED.findAll(declaration.groupValues[1]).map { it.groupValues[1] }.toList()
        }

        private fun isProductSource(relative: Path): Boolean {
            val parts = relative.map { it.toString() }
            return "src" in parts && "main" in parts && "build" !in parts
        }

        private fun accessesIn(
            file: String,
            text: String,
        ): List<TableAccess> = STATEMENT_END.split(text).mapNotNull { chunk -> accessOf(file, chunk) }

        private fun accessOf(
            file: String,
            chunk: String,
        ): TableAccess? {
            val tables =
                TABLE_REFERENCE
                    .findAll(chunk)
                    .map { it.groupValues[1].lowercase() }
                    .distinct()
                    .sorted()
                    .toList()
            if (tables.isEmpty()) return null
            val excerpt = excerptOf(chunk)
            return TableAccess(
                file = file,
                verb = verbOf(file, chunk, excerpt),
                tables = tables,
                statement = excerpt,
                hasOwnerPredicate = OWNER_PREDICATE.containsMatchIn(LiveSql.of(chunk)),
            )
        }

        /** 문장의 SQL 동사. 못 찾으면 끊는다 — 해석할 수 없는 문장을 조용히 넘기지 않는다. */
        private fun verbOf(
            file: String,
            chunk: String,
            excerpt: String,
        ): String {
            val match =
                VERB.find(chunk)
                    ?: error("$file: 감시 테이블에 닿는 문장에서 SQL 동사를 찾지 못했다 — 조용히 넘기지 않는다: $excerpt")
            return match.groupValues[1].uppercase()
        }

        /** 실패 메시지에만 쓰는 한 줄 요약. 판정에는 쓰지 않는다. */
        private fun excerptOf(chunk: String): String {
            val flat = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (flat.length <= EXCERPT_LIMIT) flat else flat.take(EXCERPT_LIMIT) + " …"
        }

        private const val EXCERPT_LIMIT = 160
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /** 핀 문자열의 경로 앞부분. 목록이 읽히게 하려고 상수로 접는다. */
        private const val MAIN = "infrastructure/src/main/kotlin/kr/easydoc/infrastructure"
        private const val AUTH = "$MAIN/auth"
        private const val DOCUMENT = "$MAIN/document"

        /** 문서·변환에 닿는 제품 SQL 전부. 소유 술어가 있는 것도 함께 적는다. */
        val EXPECTED_STATEMENTS =
            listOf(
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcConversionRepository.kt | SELECT [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | UPDATE [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | SELECT [conversions, documents]",
                "$DOCUMENT/JdbcConversionRepository.kt | INSERT [conversions]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | UPDATE [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | DELETE [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | INSERT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [conversions, documents]",
            )

        /** 그중 소유 매개변수가 걸리지 않은 문장. 오늘 일곱이고, 각각 사유가 있다. */
        val EXPECTED_UNGUARDED =
            listOf(
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcConversionRepository.kt | SELECT [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | UPDATE [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | INSERT [conversions]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | UPDATE [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | INSERT [documents]",
            )
    }
}

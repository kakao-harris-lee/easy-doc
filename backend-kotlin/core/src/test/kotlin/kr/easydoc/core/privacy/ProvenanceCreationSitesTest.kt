package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readLines

/** [ModelDraft]·[ReviewedBody] 를 어디서 만드는지 상시 열거한다. */
class ProvenanceCreationSitesTest {
    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /** 주석 줄 판별. KDoc 이 규약을 설명하며 `ModelDraft(원문)` 을 쓰는 자리가 여럿 있다. */
        val COMMENT_PREFIXES = listOf("//", "*", "/*")

        /** 큰따옴표 문자열 리터럴. 매칭 전에 지운다. */
        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

        /** 감시 대상 타입. 허용목록과 독립된 근거다 (게이트 09 M-04). */
        val WATCHED_TYPES = setOf("ModelDraft", "ReviewedBody")

        /** provenance 래퍼가 선언된 파일. 감시 대상 자기 대조가 여기를 읽는다. */
        const val PROVENANCE_DECLARATION_FILE =
            "core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt"

        /** 생성 지점 허용목록. 키는 타입 이름, 값은 `backend-kotlin` 기준 상대 경로 → 호출 수. */
        val ALLOWED: Map<String, Map<String, Int>> =
            mapOf(
                "ModelDraft" to
                    mapOf(
                        "application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt" to 2,
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptInjectionGuardTest.kt" to 4,
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptTextSnapshotTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptsTest.kt" to 4,
                        "core/src/test/kotlin/kr/easydoc/core/CoreDomainsParityTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/ExportParityTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/llm/LlmPromptTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt" to 10,
                    ),
                "ReviewedBody" to
                    mapOf(
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt" to 4,
                        "core/src/test/kotlin/kr/easydoc/core/ExportParityTest.kt" to 1,
                    ),
            )

        /**
         * import 별칭 탐지. `import kr.easydoc.core.privacy.ModelDraft as 초안` 을 쓰면
         * 생성 지점이 `초안(...)` 이 되어 이름 기반 스캔을 통째로 빠져나간다.
         */
        val ALIAS_IMPORT = Regex("""^import\s+kr\.easydoc\.core\.privacy\.(ModelDraft|ReviewedBody)\s+as\s+""")

        /**
         * 생성자 참조(`::ModelDraft`). 호출 괄호가 없어 `ModelDraft\(` 패턴에 걸리지 않는데,
         * 넘겨받은 쪽에서 임의 문자열로 인스턴스를 만들 수 있다.
         */
        val CONSTRUCTOR_REFERENCE = Regex("""(?<![A-Za-z0-9_])::(?:ModelDraft|ReviewedBody)\b""")
    }

    @Test
    @DisplayName("허용목록 밖에서 provenance 래퍼를 만들지 않는다")
    fun `허용하지 않은 생성 지점이 없다`() {
        val found = creationSites()

        WATCHED_TYPES.forEach { type ->
            val unexpected = (found[type] ?: emptyMap()).keys - (ALLOWED[type] ?: emptyMap()).keys
            assertThat(unexpected)
                .withFailMessage {
                    "$type 을 허용목록 밖에서 만든다: ${unexpected.sorted()}\n" +
                        "  이 목록은 privacy-gate 판정 X-5 의 수용 조건이다. 줄을 더하기 전에 " +
                        "Masking.kt 의 「provenance 래퍼 사용 규약」을 읽어라 — " +
                        "특히 ReviewedBody 는 사람이 제출한 edited_text 를 읽는 어댑터 한 곳뿐이다."
                }.isEmpty()
        }
    }

    @Test
    @DisplayName("허용된 파일 안에서도 생성 **개수**가 늘면 실패한다")
    fun `허용 파일 안의 추가 생성을 잡는다`() {
        val found = creationSites()

        WATCHED_TYPES.forEach { type ->
            (ALLOWED[type] ?: emptyMap()).forEach { (file, count) ->
                assertThat(found[type]?.get(file))
                    .withFailMessage {
                        "$type 의 $file 생성 개수가 $count 가 아니라 ${found[type]?.get(file)} 다.\n" +
                            "  늘었다면 새 생성 지점이 규약에 맞는지 확인하고 이 수를 고쳐라 — " +
                            "그 diff 가 리뷰에 올라가는 것이 이 숫자의 값어치다.\n" +
                            "  줄었다면 남은 수로 고쳐라(죽은 허용은 조용히 권한을 넓힌다)."
                    }.isEqualTo(count)
            }
        }
    }

    @Test
    @DisplayName("허용목록에 더는 만들지 않는 자리가 남아 있지 않다")
    fun `죽은 허용 줄이 없다`() {
        val found = creationSites()

        WATCHED_TYPES.forEach { type ->
            val stale = (ALLOWED[type] ?: emptyMap()).keys - (found[type] ?: emptyMap()).keys
            assertThat(stale)
                .withFailMessage {
                    "$type 허용목록에 더는 생성하지 않는 자리가 남아 있다: ${stale.sorted()}\n" +
                        "  줄을 지워라 — 남겨 두면 그 파일에 새 생성 지점이 들어와도 조용히 통과한다."
                }.isEmpty()
        }
    }

    @Test
    @DisplayName("감시 대상이 소스의 provenance 래퍼 선언과 일치한다")
    fun `감시 대상 목록이 소스와 어긋나지 않는다`() {
        val declaration = sourceRoot().resolve(PROVENANCE_DECLARATION_FILE)
        check(
            java.nio.file.Files
                .isRegularFile(declaration),
        ) {
            "provenance 선언 파일이 없다: $declaration — 옮겼다면 PROVENANCE_DECLARATION_FILE 을 고쳐라."
        }

        val declared =
            declaration
                .readLines()
                .mapNotNull { line ->
                    Regex("""^value class (\w+)\(val value: String\)""").find(line.trimStart())
                }.map { it.groupValues[1] }
                .toSet()

        assertThat(declared)
            .withFailMessage {
                "감시 대상($WATCHED_TYPES)이 소스의 provenance 래퍼 선언($declared)과 다르다.\n" +
                    "  새 래퍼가 생겼다면 WATCHED_TYPES 와 ALLOWED 에 함께 더하라 — " +
                    "감시 대상을 허용목록에서 파생시키면 목록 편집이 곧 감시 축소가 된다.\n" +
                    "  래퍼를 지웠다면 WATCHED_TYPES 에서도 지워라."
            }.isEqualTo(WATCHED_TYPES)
    }

    @Test
    @DisplayName("import 별칭으로 이름을 바꿔 빠져나갈 수 없다")
    fun `별칭 import 를 금지한다`() {
        val offenders =
            kotlinSources(sourceRoot()).filter { file ->
                file.readLines().any { ALIAS_IMPORT.containsMatchIn(it.trimStart()) }
            }

        assertThat(offenders)
            .withFailMessage {
                "provenance 타입을 별칭으로 import 한 파일이 있다: ${offenders.map { it.fileName }}\n" +
                    "  별칭을 쓰면 이 가드가 생성 지점을 이름으로 찾지 못한다. 원래 이름으로 쓰라."
            }.isEmpty()
    }

    @Test
    @DisplayName("생성자 참조(::ModelDraft)로 넘겨줄 수 없다")
    fun `생성자 참조를 금지한다`() {
        val offenders =
            kotlinSources(sourceRoot()).filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trimStart()
                    COMMENT_PREFIXES.none { trimmed.startsWith(it) } &&
                        CONSTRUCTOR_REFERENCE.containsMatchIn(STRING_LITERAL.replace(line, "\"\""))
                }
            }

        assertThat(offenders)
            .withFailMessage {
                "provenance 타입의 생성자 참조가 있다: ${offenders.map { it.fileName }}\n" +
                    "  ::ModelDraft 는 생성 지점을 호출부 밖으로 옮긴다. 명시적으로 감싸라."
            }.isEmpty()
    }

    /** 타입 이름 → (파일 → 그 파일이 그 타입을 생성하는 줄 수). */
    private fun creationSites(): Map<String, Map<String, Int>> {
        val root = sourceRoot()
        val sites = WATCHED_TYPES.associateWith { mutableMapOf<String, Int>() }

        kotlinSources(root).forEach { file ->
            val relative = root.relativize(file).joinToString("/")
            val lines = file.readLines()
            WATCHED_TYPES.forEach { type ->
                val count = lines.count { createsType(it, type) }
                if (count > 0) sites.getValue(type)[relative] = count
            }
        }
        return sites.mapValues { it.value.toMap() }
    }

    /** 스캔 대상 파일. Gradle 산출물은 소스가 아니다 — 넣으면 같은 파일을 두 번 센다. */
    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .filter { root.relativize(it).none { part -> part.toString() == "build" } }
                .toList()
        }

    /** 이 줄이 [type] 을 생성하는가. */
    private fun createsType(
        line: String,
        type: String,
    ): Boolean {
        val trimmed = line.trimStart()
        val commentOrDeclaration =
            COMMENT_PREFIXES.any { trimmed.startsWith(it) } || trimmed.startsWith("value class $type(")
        val code = STRING_LITERAL.replace(line, "\"\"")
        return !commentOrDeclaration && Regex("""(?<![A-Za-z0-9_])$type\(""").containsMatchIn(code)
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
}

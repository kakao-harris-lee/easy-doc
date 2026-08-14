package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readLines

/**
 * [ModelDraft]·[ReviewedBody] 를 **어디서 만드는지** 상시 열거한다.
 *
 * ## 왜 타입으로 막지 않고 열거하나 (privacy-gate 판정 X-5, 2026-08-13)
 *
 * 두 타입의 생성자는 좁힐 수 없다. Kotlin `internal` 은 Gradle 모듈 경계인데,
 * `ReviewedBody` 를 만들어야 하는 계층이 바로 그 밖(`api` 의 HTTP 요청 어댑터)이다 —
 * **요구되는 흐름 자체가 타입 봉쇄와 양립하지 않는다.** 이름 있는 팩터리로 옮기는 것도
 * 방어가 아니다. 손으로 `ReviewedBody(모델응답)` 을 쓸 수 있는 사람은
 * `ReviewedBody.fromHumanSubmission(모델응답)` 도 쓸 수 있다.
 *
 * 그래서 **불가능하게 만들지 않고 조용할 수 없게** 만든다. 새 생성 지점이 생기면 이 테스트가
 * 실패하고, 실패를 풀려면 아래 허용목록에 줄을 더해야 하므로 **그 diff 가 리뷰에 올라간다.**
 * 사용 규약 자체는 `Masking.kt` 의 「provenance 래퍼 사용 규약」 절이 정본이다.
 *
 * ## 선언한 범위와 실제 도달 범위
 *
 * 스캔 대상은 `backend-kotlin` 아래 **모든** `.kt` 파일이다(`build/` 산출물 제외).
 * 테스트 소스도 뺀 게 아니라 포함한다 — 잘못된 습관(`ReviewedBody(초안)`)이 먼저 자리
 * 잡는 곳이 대개 테스트이고, 프로덕션만 보면 선언한 범위가 실제 도달보다 넓어진다.
 *
 * **막지 못하는 것**: 이 테스트 파일 자체의 삭제, 리플렉션, 문자열 조립으로 만든 호출.
 * 최종 방어선은 그 diff 가 리뷰에 올라가는 것이다 — 한 칸 더 옮기지 않는다.
 */
class ProvenanceCreationSitesTest {
    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /** 주석 줄 판별. KDoc 이 규약을 설명하며 `ModelDraft(원문)` 을 쓰는 자리가 여럿 있다. */
        val COMMENT_PREFIXES = listOf("//", "*", "/*")

        /**
         * 큰따옴표 문자열 리터럴. 매칭 전에 지운다.
         *
         * 지우지 않으면 타입 자신의 `toString()` 이 자기 이름을 찍는 순간
         * (`"ModelDraft(${'$'}{value.length}자)"`) 선언 파일이 **생성 지점으로 오인된다** —
         * 실제로 privacy-gate 판정 5 의 `toString` 재정의를 넣자마자 그렇게 됐다.
         * 문자열 템플릿 안에서 생성하는 경우(`"${'$'}{ModelDraft(x)}"`)는 이 처리로 놓치지만,
         * 그런 코드는 실재하지 않고 놓치는 방향이 좁다.
         */
        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

        /**
         * **감시 대상 타입.** 허용목록과 **독립된** 근거다 (게이트 09 M-04).
         *
         * 이전 판은 감시 대상을 `ALLOWED.keys` 에서 얻었다. 그러면 **허용목록을 편집하는
         * 것이 곧 감시 축소**다 — `ALLOWED` 에서 `ReviewedBody` 항목을 지우면 그 타입에
         * 대한 검사가 통째로 사라지는데 테스트는 초록이다. 하나뿐인 provenance 탐지기를
         * 끄는 두 방법 중 하나였다(다른 하나는 파일 삭제 — M-02, CI 에서 닫았다).
         *
         * 여기 적은 수가 옳은지는 **소스가 판정한다** — 아래
         * 「감시 대상이 소스의 value class 선언과 일치한다」가 `Masking.kt` 에서 provenance
         * 래퍼 선언을 세어 이 집합과 대조한다. 세 번째 래퍼가 생기면 그 테스트가 먼저
         * 빨개지므로, 새 타입이 영영 스캔 밖에 남는 경로가 닫힌다.
         */
        val WATCHED_TYPES = setOf("ModelDraft", "ReviewedBody")

        /**
         * provenance 래퍼가 선언된 파일. 감시 대상 자기 대조가 여기를 읽는다.
         *
         * `MaskedText` 는 이 집합에 넣지 않는다 — 생성 통로가 `mask` 하나뿐이라 별도
         * 탐지기(`MaskedTextGatewayTest`)가 맡는 대상이고, 여기는 **생성자가 공개인**
         * 래퍼만 본다. 그 구분이 흐려지면 두 탐지기가 서로를 대신하는 것처럼 보인다.
         */
        const val PROVENANCE_DECLARATION_FILE =
            "core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt"

        /**
         * 생성 지점 허용목록. 키는 타입 이름, 값은 `backend-kotlin` 기준 상대 경로 → 호출 수.
         *
         * **줄을 더하기 전에 `Masking.kt` 의 사용 규약을 읽어라.** 특히 [ReviewedBody] 는
         * "HTTP 요청 경계에서 사람이 제출한 `edited_text` 를 읽는 어댑터" 한 곳뿐이다.
         *
         * 이 목록은 **무엇을 허용하는가**만 정한다. **무엇을 감시하는가**는 [WATCHED_TYPES] 다.
         */
        val ALLOWED: Map<String, Map<String, Int>> =
            mapOf(
                // ModelDraft = LLM 출력 경로. 변환 결과와 그 후처리물만 감싼다.
                "ModelDraft" to
                    mapOf(
                        // 변환 유스케이스 — 보정 프롬프트 입력과 최종 결과. 값의 출처가 LLM 출력이다.
                        "application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt" to 2,
                        // 아래는 전부 테스트. 프롬프트·복원 동작을 재려면 초안을 지어내야 한다.
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptInjectionGuardTest.kt" to 4,
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptTextSnapshotTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptsTest.kt" to 4,
                        // parity 생산자 — fixture 가 "1차 변환문" 자리에 둔 본문을 보정
                        // 프롬프트에 넣는다. 값의 출처가 LLM 출력이라 규약 안이다.
                        "core/src/test/kotlin/kr/easydoc/core/CoreDomainsParityTest.kt" to 1,
                        // export parity 생산자 — 복원 케이스의 "1차 변환문" 자리.
                        // 아래 ReviewedBody 항목에 같은 파일을 넣은 사유가 함께 적혀 있다.
                        "core/src/test/kotlin/kr/easydoc/core/ExportParityTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/llm/LlmPromptTest.kt" to 1,
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt" to 10,
                    ),
                // ReviewedBody = 사람이 제출한 검수본. **프로덕션 생성 지점은 아직 없다** —
                // 검수 제출 API 는 Phase 3~4 다. 지금 프로덕션 경로에 이 타입이 생기면
                // 그것은 "사람이 제출했다"는 사실을 어딘가에서 지어낸 것이다.
                "ReviewedBody" to
                    mapOf(
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt" to 4,
                        // export parity 생산자. `restoreForExport` 는 검수본이 없으면 복원을
                        // **보류**하므로(사람이 위치를 확증하지 않은 본문에 개인정보를 꽂지
                        // 않는다), 정본이 요구하는 "자리표시자가 남김없이 복원된다"는 성질을
                        // 재려면 검수 제출을 표현할 수밖에 없다. 값의 출처는 fixture 이고
                        // 프로덕션 경로가 아니다 — MaskingTest 가 같은 이유로 이미 여기 있다.
                        "core/src/test/kotlin/kr/easydoc/core/ExportParityTest.kt" to 1,
                    ),
            )

        /**
         * import 별칭 탐지. `import kr.easydoc.core.privacy.ModelDraft as 초안` 을 쓰면
         * 생성 지점이 `초안(...)` 이 되어 이름 기반 스캔을 통째로 빠져나간다.
         *
         * 별칭은 **금지한다**. 허용하고 별칭 이름까지 추적하는 방법도 있지만, 그러면 이
         * 가드가 파서에 가까워지고 우회 표면은 그대로 남는다(별칭의 별칭). 금지가 더 좁다.
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
        // 교차 종합 C-05 ①. 파일 단위 집합으로만 보면 **이미 허용된 파일에 생성 지점을
        // 하나 더 넣는 것**이 아무 신호도 내지 않는다. 그 파일이 프로덕션 코드면
        // "ModelDraft 는 LLM 출력 경로에서만" 이라는 규약이 조용히 넓어진다.
        //
        // 개수를 세면 그 diff 가 반드시 이 상수를 건드리므로 리뷰에 올라간다.
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
        // 낡은 허용 줄은 **조용히 권한을 넓힌다.** 어떤 파일에서 생성이 사라졌는데 줄이
        // 남아 있으면, 나중에 그 파일에 전혀 다른 맥락으로 생성이 들어와도 통과한다.
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
        // 게이트 09 M-04. `WATCHED_TYPES` 를 손으로 적었으므로 **그 수가 옳은지 판정할
        // 독립 근거**가 필요하다. 없으면 세 번째 래퍼가 생겨도 영영 스캔 밖에 남는다
        // (증가 방향), 그리고 목록에서 한 줄을 지우면 그 타입 검사가 통째로 사라진다
        // (감소 방향). 두 방향을 같은 단언이 막는다.
        //
        // 판정 근거는 **소스의 선언**이다 — `Masking.kt` 에서 공개 생성자를 가진
        // provenance value class 를 세어 대조한다.
        val declaration = sourceRoot().resolve(PROVENANCE_DECLARATION_FILE)
        check(
            java.nio.file.Files
                .isRegularFile(declaration),
        ) {
            "provenance 선언 파일이 없다: $declaration — 옮겼다면 PROVENANCE_DECLARATION_FILE 을 고쳐라."
        }

        // `private constructor` 인 것(MaskedText)은 제외한다 — 그쪽은 생성 통로가 하나뿐이라
        // MaskedTextGatewayTest 가 맡는 대상이고, 여기는 생성자가 열린 래퍼만 본다.
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
        // 교차 종합 C-05 ②(양측 합의). `import ... ModelDraft as 초안` 뒤에는 생성 지점이
        // `초안(...)` 이라 이름 기반 스캔이 통째로 무력해진다.
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
        // 교차 종합 C-05 ③. `::ModelDraft` 는 호출 괄호가 없어 `ModelDraft(` 패턴에 걸리지
        // 않는데, 넘겨받은 쪽은 임의 문자열로 인스턴스를 만들 수 있다. 생성 지점이 코드에서
        // **보이지 않는 곳으로 옮겨가는** 형태라 금지한다.
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

    /** 타입 이름 → (파일 → 그 파일이 그 타입을 **생성하는 줄 수**). */
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

    /**
     * 이 줄이 [type] 을 **생성**하는가.
     *
     * 주석과 선언은 뺀다 — KDoc 이 규약을 설명하는 자리가 여럿 있고,
     * `value class ModelDraft(val value: String)` 은 생성이 아니라 정의다. 문자열 리터럴도
     * 지운다(타입 자신의 `toString()` 이 자기 이름을 찍는다).
     */
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

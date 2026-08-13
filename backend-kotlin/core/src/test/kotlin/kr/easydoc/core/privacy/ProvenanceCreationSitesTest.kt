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
         * 생성 지점 허용목록. 키는 타입 이름, 값은 `backend-kotlin` 기준 상대 경로.
         *
         * **줄을 더하기 전에 `Masking.kt` 의 사용 규약을 읽어라.** 특히 [ReviewedBody] 는
         * "HTTP 요청 경계에서 사람이 제출한 `edited_text` 를 읽는 어댑터" 한 곳뿐이다.
         */
        val ALLOWED: Map<String, Set<String>> =
            mapOf(
                // ModelDraft = LLM 출력 경로. 변환 결과와 그 후처리물만 감싼다.
                "ModelDraft" to
                    setOf(
                        // 변환 유스케이스 — 보정 프롬프트 입력과 최종 결과. 값의 출처가 LLM 출력이다.
                        "application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt",
                        // 아래는 전부 테스트. 프롬프트·복원 동작을 재려면 초안을 지어내야 한다.
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptInjectionGuardTest.kt",
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptTextSnapshotTest.kt",
                        "core/src/test/kotlin/kr/easydoc/core/easyread/PromptsTest.kt",
                        "core/src/test/kotlin/kr/easydoc/core/llm/LlmPromptTest.kt",
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt",
                    ),
                // ReviewedBody = 사람이 제출한 검수본. **프로덕션 생성 지점은 아직 없다** —
                // 검수 제출 API 는 Phase 3~4 다. 지금 프로덕션 경로에 이 타입이 생기면
                // 그것은 "사람이 제출했다"는 사실을 어딘가에서 지어낸 것이다.
                "ReviewedBody" to
                    setOf(
                        "core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt",
                    ),
            )
    }

    @Test
    @DisplayName("허용목록 밖에서 provenance 래퍼를 만들지 않는다")
    fun `허용하지 않은 생성 지점이 없다`() {
        val found = creationSites()

        ALLOWED.keys.forEach { type ->
            val unexpected = (found[type] ?: emptySet()) - ALLOWED.getValue(type)
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
    @DisplayName("허용목록에 더는 만들지 않는 자리가 남아 있지 않다")
    fun `죽은 허용 줄이 없다`() {
        // 낡은 허용 줄은 **조용히 권한을 넓힌다.** 어떤 파일에서 생성이 사라졌는데 줄이
        // 남아 있으면, 나중에 그 파일에 전혀 다른 맥락으로 생성이 들어와도 통과한다.
        val found = creationSites()

        ALLOWED.forEach { (type, allowed) ->
            val stale = allowed - (found[type] ?: emptySet())
            assertThat(stale)
                .withFailMessage {
                    "$type 허용목록에 더는 생성하지 않는 자리가 남아 있다: ${stale.sorted()}\n" +
                        "  줄을 지워라 — 남겨 두면 그 파일에 새 생성 지점이 들어와도 조용히 통과한다."
                }.isEmpty()
        }
    }

    /** 타입 이름 → 그 타입을 **생성하는** 파일들(소스 루트 기준 상대 경로). */
    private fun creationSites(): Map<String, Set<String>> {
        val root = sourceRoot()
        val sites = ALLOWED.keys.associateWith { mutableSetOf<String>() }

        kotlinSources(root).forEach { file ->
            val relative = root.relativize(file).joinToString("/")
            typesCreatedIn(file).forEach { type -> sites.getValue(type) += relative }
        }
        return sites.mapValues { it.value.toSet() }
    }

    /** 스캔 대상 파일. Gradle 산출물은 소스가 아니다 — 넣으면 같은 파일을 두 번 센다. */
    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .filter { root.relativize(it).none { part -> part.toString() == "build" } }
                .toList()
        }

    /** 이 파일이 생성하는 provenance 타입들. */
    private fun typesCreatedIn(file: Path): Set<String> {
        val lines = file.readLines()
        return ALLOWED.keys.filterTo(mutableSetOf()) { type -> lines.any { createsType(it, type) } }
    }

    /**
     * 이 줄이 [type] 을 **생성**하는가.
     *
     * 주석과 선언은 뺀다 — KDoc 이 `ModelDraft(원문)` 처럼 규약을 설명하는 자리가 실제로
     * 여럿 있고, `value class ModelDraft(val value: String)` 은 생성이 아니라 정의다.
     * 문자열 리터럴 안의 우연한 일치까지 가리지는 않는다(그 정밀도는 파서가 필요하고,
     * 이 가드가 재려는 것은 "새 호출 자리가 조용히 생기는가"다).
     */
    private fun createsType(
        line: String,
        type: String,
    ): Boolean {
        val trimmed = line.trimStart()
        val commentOrDeclaration =
            COMMENT_PREFIXES.any { trimmed.startsWith(it) } || trimmed.startsWith("value class $type(")
        return !commentOrDeclaration && Regex("""(?<![A-Za-z0-9_])$type\(""").containsMatchIn(line)
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

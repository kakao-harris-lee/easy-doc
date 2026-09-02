package kr.easydoc.infrastructure.quality

import kr.easydoc.infrastructure.llm.ANTHROPIC_PROVIDER_NAME
import kr.easydoc.infrastructure.llm.AnthropicEffort
import kr.easydoc.infrastructure.llm.DEFAULT_ANTHROPIC_MODEL
import kr.easydoc.infrastructure.llm.FAKE_PROVIDER_NAME
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.MAX_OUTPUT_TOKENS_CEILING
import kr.easydoc.infrastructure.llm.OPENAI_PROVIDER_NAME
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 레인이 **무엇을 재는지**를 유료 호출 없이 고정한다.
 *
 * 2026-08-27 실측을 무효로 만든 것은 채점 코드가 아니라 provider 선택 규칙이었다. 그 규칙은
 * 여기서 전부 시험할 수 있고, 시험할 수 있어야 다음 측정 전에 깨진 것을 안다.
 */
class GoldenLlmLaneTest {
    @Test
    @DisplayName("provider 는 EASYDOC_LLM_PROVIDER 가 정한다 — 다른 벤더 키가 있어도 넘어가지 않는다")
    fun `다른 벤더 키로 대체하지 않는다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to ANTHROPIC_PROVIDER_NAME,
                    GoldenLlmLane.OPENAI_KEY_ENV to "sk-openai-테스트",
                ),
            )

        assertThat(plan).isInstanceOf(LanePlan.Skipped::class.java)
        assertThat((plan as LanePlan.Skipped).reason).contains(GoldenLlmLane.ANTHROPIC_KEY_ENV)
    }

    @Test
    @DisplayName("EASYDOC_LLM_PROVIDER 미설정이면 제품 배포 기본값(anthropic)으로 잰다")
    fun `기본값은 제품과 같다`() {
        val plan = GoldenLlmLane.plan(env(GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY))

        assertThat(ready(plan).provider.name).isEqualTo(ANTHROPIC_PROVIDER_NAME)
        assertThat(ready(plan).description).contains(DEFAULT_ANTHROPIC_MODEL)
    }

    @Test
    @DisplayName("레인 기본 provider 는 api·worker application.yml 의 기본값과 같다")
    fun `기본값이 배포 설정과 어긋나지 않는다`() {
        val root = Path.of(System.getProperty(SOURCE_ROOT_PROPERTY) ?: error("$SOURCE_ROOT_PROPERTY 이 없다"))
        val deployed =
            listOf("api", "worker").map { module ->
                val yml = root.resolve("$module/src/main/resources/application.yml").readText()
                PROVIDER_DEFAULT.find(yml)?.groupValues?.get(1)
                    ?: error("$module application.yml 에서 ${GoldenLlmLane.PROVIDER_ENV} 기본값을 찾지 못했다")
            }

        assertThat(deployed)
            .withFailMessage {
                "레인 기본 provider(${GoldenLlmLane.DEFAULT_PROVIDER})가 배포 기본값 $deployed 와 다르다 — " +
                    "레인이 제품과 다른 벤더를 재게 된다."
            }.containsOnly(GoldenLlmLane.DEFAULT_PROVIDER)
    }

    @Test
    @DisplayName("EASYDOC_LLM_MODEL·EASYDOC_LLM_EFFORT 를 제품과 같은 규칙으로 반영한다")
    fun `모델과 effort 를 반영한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to ANTHROPIC_PROVIDER_NAME,
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                    GoldenLlmLane.MODEL_ENV to "claude-sonnet-5-20260101",
                    GoldenLlmLane.EFFORT_ENV to "low",
                ),
            )

        assertThat(ready(plan).description)
            .contains("claude-sonnet-5-20260101")
            .contains(AnthropicEffort.LOW.toString())
    }

    @Test
    @DisplayName("EASYDOC_LLM_MAX_OUTPUT_TOKENS 를 실제 변환 호출의 LlmOptions 로 전달한다")
    fun `출력 토큰 상한을 반영한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to ANTHROPIC_PROVIDER_NAME,
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                    GoldenLlmLane.MAX_OUTPUT_TOKENS_ENV to "4321",
                ),
            )

        assertThat(ready(plan).options.maxTokens).isEqualTo(4321)
        assertThat(ready(plan).description).contains("max_tokens=4321")
    }

    @Test
    @DisplayName("EASYDOC_LLM_MAX_OUTPUT_TOKENS 미설정이면 제품 기본값과 같다 — 출처가 하나다")
    fun `출력 토큰 상한 기본값은 제품과 같다`() {
        val plan = GoldenLlmLane.plan(env(GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY))

        assertThat(ready(plan).options.maxTokens).isEqualTo(LlmProperties().maxOutputTokens)
    }

    @Test
    @DisplayName("EASYDOC_LLM_MAX_OUTPUT_TOKENS 가 정수가 아니면 기본값으로 접지 않고 실패로 알린다")
    fun `정수가 아닌 출력 토큰 상한은 거절한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                    GoldenLlmLane.MAX_OUTPUT_TOKENS_ENV to "32k",
                ),
            )

        assertThat(plan).isInstanceOf(LanePlan.Unusable::class.java)
        assertThat((plan as LanePlan.Unusable).reason)
            .contains(GoldenLlmLane.MAX_OUTPUT_TOKENS_ENV)
            .contains("32k")
    }

    @Test
    @DisplayName("EASYDOC_LLM_MAX_OUTPUT_TOKENS 가 상한을 넘으면 레인도 제품과 같이 거절한다 — 상한을 우회하지 않는다")
    fun `출력 토큰 상한 초과는 레인도 거절한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to ANTHROPIC_PROVIDER_NAME,
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                    GoldenLlmLane.MAX_OUTPUT_TOKENS_ENV to (MAX_OUTPUT_TOKENS_CEILING + 1).toString(),
                ),
            )

        assertThat(plan).isInstanceOf(LanePlan.Unusable::class.java)
        assertThat((plan as LanePlan.Unusable).reason).contains("A4 20장")
    }

    @Test
    @DisplayName("openai 를 고르면 openai 키를 본다")
    fun `openai 는 openai 키를 본다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to OPENAI_PROVIDER_NAME,
                    GoldenLlmLane.OPENAI_KEY_ENV to KEY,
                ),
            )

        assertThat(ready(plan).provider.name).isEqualTo(OPENAI_PROVIDER_NAME)
    }

    @Test
    @DisplayName("fake 로는 품질을 잴 수 없으므로 skip 이 아니라 실패로 알린다")
    fun `fake 는 거부한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.PROVIDER_ENV to FAKE_PROVIDER_NAME,
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                ),
            )

        assertThat(plan).isInstanceOf(LanePlan.Unusable::class.java)
        assertThat((plan as LanePlan.Unusable).reason).contains(FAKE_PROVIDER_NAME)
    }

    @Test
    @DisplayName("모르는 provider 이름은 skip 으로 숨기지 않고 제품 규칙이 거절한다")
    fun `모르는 provider 는 거절한다`() {
        val plan = GoldenLlmLane.plan(env(GoldenLlmLane.PROVIDER_ENV to "gemini"))

        assertThat(plan).isInstanceOf(LanePlan.Unusable::class.java)
        assertThat((plan as LanePlan.Unusable).reason).contains("지원하지 않는")
    }

    @Test
    @DisplayName("지원하지 않는 effort 값도 레인 시작 전에 거절한다")
    fun `잘못된 effort 는 거절한다`() {
        val plan =
            GoldenLlmLane.plan(
                env(
                    GoldenLlmLane.ANTHROPIC_KEY_ENV to KEY,
                    GoldenLlmLane.EFFORT_ENV to "turbo",
                ),
            )

        assertThat(plan).isInstanceOf(LanePlan.Unusable::class.java)
    }

    @Test
    @DisplayName("비밀값이 하나도 없으면 skip 한다 — 이 경로는 유료 호출이 아니다")
    fun `비밀값이 없으면 skip 한다`() {
        assertThat(GoldenLlmLane.plan { null }).isInstanceOf(LanePlan.Skipped::class.java)
    }

    private fun ready(plan: LanePlan): LanePlan.Ready {
        assertThat(plan).isInstanceOf(LanePlan.Ready::class.java)
        return plan as LanePlan.Ready
    }

    private fun env(vararg entries: Pair<String, String>): (String) -> String? = mapOf(*entries)::get

    private companion object {
        /** 값의 내용은 판정에 쓰이지 않는다 — 비어 있지 않다는 사실만 본다. */
        const val KEY: String = "테스트-키-DO-NOT-LEAK"

        const val SOURCE_ROOT_PROPERTY: String = "easydoc.kotlin.source.root"

        /** `provider: ${EASYDOC_LLM_PROVIDER:anthropic}` 의 기본값 부분. */
        val PROVIDER_DEFAULT: Regex = Regex("""EASYDOC_LLM_PROVIDER:([a-z]+)}""")
    }
}

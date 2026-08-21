package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** provider 조립의 소유자가 `infrastructure` 라는 결정의 회귀 테스트. */
class LlmProviderConfigurationTest {
    private val configuration = LlmProviderConfiguration()

    @Test
    @DisplayName("기본 설정이면 anthropic 어댑터를 만든다")
    fun `기본값은 anthropic 이다`() {
        val provider = configuration.llmProvider(LlmProperties())

        assertThat(provider).isInstanceOf(AnthropicProvider::class.java)
        assertThat(provider.name).isEqualTo(ANTHROPIC_PROVIDER_NAME)

        assertThat(provider.toString()).contains(DEFAULT_ANTHROPIC_MODEL)
    }

    @Test
    @DisplayName("설정한 모델·effort 가 어댑터에 전달된다")
    fun `설정을 어댑터로 넘긴다`() {
        val provider =
            configuration.llmProvider(
                LlmProperties(model = "claude-sonnet-5-20260101", effort = "medium"),
            )

        assertThat(provider.toString()).contains("claude-sonnet-5-20260101")
        assertThat(provider.toString()).contains(AnthropicEffort.MEDIUM.toString())
    }

    @Test
    @DisplayName("키가 없어도 조립은 된다 — 기동을 막지 않는다")
    fun `키 미설정은 기동을 막지 않는다`() {
        assertThat(configuration.llmProvider(LlmProperties(anthropicApiKey = Secret.EMPTY)))
            .isInstanceOf(AnthropicProvider::class.java)
    }

    @Test
    @DisplayName("모르는 벤더 이름은 조립 시점에 거절한다")
    fun `지원하지 않는 provider 는 던진다`() {
        assertThatThrownBy { configuration.llmProvider(LlmProperties(provider = "openai")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("지원하지 않는")
            .hasMessageNotContaining("openai")
    }

    @Test
    @DisplayName("잘못된 effort 값도 조립 시점에 거절한다")
    fun `지원하지 않는 effort 는 던진다`() {
        assertThatThrownBy { configuration.llmProvider(LlmProperties(effort = "turbo")) }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("설정 표면에 호출 대상(엔드포인트)을 여는 필드가 없다")
    fun `baseUrl 을 설정으로 열지 않는다`() {
        val endpointish =

            LlmProperties::class.java.declaredFields
                .map { it.name }
                .filter { name ->
                    listOf("url", "uri", "endpoint", "host", "baseurl").any { name.lowercase().contains(it) }
                }

        assertThat(endpointish)
            .withFailMessage {
                "LlmProperties 에 호출 대상을 여는 것으로 보이는 필드가 생겼다: $endpointish\n" +
                    "  이것을 여는 것은 구현 재량이 아니라 privacy-gate 재판정 사안이다 " +
                    "(판정 07_privacy-gate_masking-verdicts.md §4.3 해제 조건 1). " +
                    "테스트 스텁 서버는 AnthropicSettings 생성자 인자를 쓰면 되고, 설정 바인딩은 필요 없다."
            }.isEmpty()
    }
}

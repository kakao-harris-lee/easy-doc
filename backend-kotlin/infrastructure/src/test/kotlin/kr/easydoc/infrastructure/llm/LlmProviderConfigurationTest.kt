package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.math.BigDecimal

/** provider 조립의 소유자가 `infrastructure` 라는 결정의 회귀 테스트. */
class LlmProviderConfigurationTest {
    private val configuration = LlmProviderConfiguration()

    @Test
    @DisplayName("기본 설정이면 OpenAI 어댑터를 metrics decorator로 감싼다")
    fun `기본값은 openai 이다`() {
        val provider = assemble(LlmProperties())

        assertThat(provider).isInstanceOf(MetricsLlmProviderDecorator::class.java)
        assertThat(provider.name).isEqualTo(OPENAI_PROVIDER_NAME)
        assertThat(provider.toString()).contains(DEFAULT_OPENAI_MODEL)
    }

    @Test
    @DisplayName("Anthropic을 선택하면 모델·effort를 해당 어댑터에 전달한다")
    fun `anthropic 설정을 어댑터로 넘긴다`() {
        val provider =
            assemble(
                LlmProperties(
                    provider = ANTHROPIC_PROVIDER_NAME,
                    model = "claude-sonnet-5-20260101",
                    effort = "medium",
                ),
            )

        assertThat(provider.toString()).contains("claude-sonnet-5-20260101")
        assertThat(provider.toString()).contains(AnthropicEffort.MEDIUM.toString())
    }

    @Test
    @DisplayName("local 프로필에서 fake 를 선택하면 로컬 대역을 metrics decorator로 감싼다")
    fun `fake 는 로컬 대역이다`() {
        val provider = assemble(LlmProperties(provider = FAKE_PROVIDER_NAME), LOCAL_PROFILE)

        assertThat(provider).isInstanceOf(MetricsLlmProviderDecorator::class.java)
        assertThat(provider.name).isEqualTo(FAKE_PROVIDER_NAME)
        assertThat(provider.toString()).contains(FAKE_MODEL_NAME)
    }

    @Test
    @DisplayName("test 프로필에서도 fake 를 조립한다")
    fun `test 프로필의 fake 는 허용한다`() {
        val provider = assemble(LlmProperties(provider = FAKE_PROVIDER_NAME), TEST_PROFILE)

        assertThat(provider.name).isEqualTo(FAKE_PROVIDER_NAME)
    }

    @Test
    @DisplayName("운영 프로필에서 fake 는 기동을 막는다")
    fun `worker 만으로는 fake 를 쓰지 못한다`() {
        assertThatThrownBy { assemble(LlmProperties(provider = FAKE_PROVIDER_NAME), "worker") }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LOCAL_PROFILE)
            .hasMessageContaining(TEST_PROFILE)
    }

    @Test
    @DisplayName("키가 없어도 조립은 된다 — 기동을 막지 않는다")
    fun `키 미설정은 기동을 막지 않는다`() {
        assertThat(assemble(LlmProperties(openAiApiKey = Secret.EMPTY)).name)
            .isEqualTo(OPENAI_PROVIDER_NAME)
    }

    @Test
    @DisplayName("모르는 벤더 이름은 조립 시점에 거절한다")
    fun `지원하지 않는 provider 는 던진다`() {
        assertThatThrownBy { assemble(LlmProperties(provider = "gemini")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("지원하지 않는")
            .hasMessageNotContaining("gemini")
    }

    @Test
    @DisplayName("잘못된 effort 값도 조립 시점에 거절한다")
    fun `지원하지 않는 effort 는 던진다`() {
        assertThatThrownBy {
            assemble(LlmProperties(provider = ANTHROPIC_PROVIDER_NAME, effort = "turbo"))
        }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("입력·출력 토큰 단가는 함께 설정해야 한다")
    fun `단가 한쪽만 설정하면 거절한다`() {
        assertThatThrownBy {
            assemble(
                LlmProperties(
                    pricing =
                        LlmPricingProperties(
                            inputUsdPerMillionTokens = BigDecimal("2.00"),
                        ),
                ),
            )
        }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("openai + 모델 미지정 + 기본 모델 한도 초과는 조립 시점에 거절한다")
    fun `openai 기본 모델 한도를 넘으면 거절한다`() {
        assertThatThrownBy {
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    maxOutputTokens = OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS + 1,
                ),
            )
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(DEFAULT_OPENAI_MODEL)
            .hasMessageContaining(OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS.toString())
    }

    @Test
    @DisplayName("openai + 모델 지정이면 같은 값도 통과한다 — 모르는 모델의 한도는 검사하지 않는다")
    fun `openai 모델을 지정하면 한도를 검사하지 않는다`() {
        val provider =
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    model = "gpt-4.1-custom",
                    maxOutputTokens = OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS + 1,
                ),
            )

        assertThat(provider.toString()).contains("gpt-4.1-custom")
    }

    @Test
    @DisplayName("anthropic 기본 모델은 현재 기본값과 게이트 ⓪ 이 쓴 64,000 모두 통과한다")
    fun `anthropic 기본 모델은 64000 까지 통과한다`() {
        val defaultAssembled = assemble(LlmProperties(provider = ANTHROPIC_PROVIDER_NAME))
        val gateZeroAssembled =
            assemble(
                LlmProperties(
                    provider = ANTHROPIC_PROVIDER_NAME,
                    maxOutputTokens = MAX_OUTPUT_TOKENS_CEILING,
                ),
            )

        assertThat(defaultAssembled.toString()).contains(DEFAULT_ANTHROPIC_MODEL)
        assertThat(gateZeroAssembled.toString()).contains(DEFAULT_ANTHROPIC_MODEL)
    }

    @Test
    @DisplayName("openai + 모델을 명시적으로 gpt-4.1 로 지정해도 그 모델의 한도를 검사한다")
    fun `openai 명시적 gpt-4-1 은 알려진 모델 한도를 검사한다`() {
        assertThatThrownBy {
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    model = DEFAULT_OPENAI_MODEL,
                    maxOutputTokens = OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS + 1,
                ),
            )
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(DEFAULT_OPENAI_MODEL)
            .hasMessageContaining(OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS.toString())
    }

    @Test
    @DisplayName("openai + 모델을 명시적으로 gpt-4.1 로 지정하고 한도 이내면 통과한다")
    fun `openai 명시적 gpt-4-1 은 한도 이내면 통과한다`() {
        val provider =
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    model = DEFAULT_OPENAI_MODEL,
                    maxOutputTokens = OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS,
                ),
            )

        assertThat(provider.toString()).contains(DEFAULT_OPENAI_MODEL)
    }

    @Test
    @DisplayName("openai + 모르는 모델을 명시적으로 지정하면 표에 없어 검사하지 않는다")
    fun `openai 모르는 명시적 모델은 표에 없어 통과한다`() {
        val provider =
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    model = "gpt-4.1-custom",
                    maxOutputTokens = MAX_OUTPUT_TOKENS_CEILING,
                ),
            )

        assertThat(provider.toString()).contains("gpt-4.1-custom")
    }

    @Test
    @DisplayName("provider별 모델 한도 검사를 더해도 기존 하한·상한 검증은 그대로 던진다")
    fun `기존 하한 상한 검증은 그대로다`() {
        assertThatThrownBy {
            assemble(LlmProperties(provider = OPENAI_PROVIDER_NAME, maxOutputTokens = 0))
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.llm.max-output-tokens")

        assertThatThrownBy {
            assemble(
                LlmProperties(
                    provider = OPENAI_PROVIDER_NAME,
                    model = "gpt-4.1-custom",
                    maxOutputTokens = MAX_OUTPUT_TOKENS_CEILING + 1,
                ),
            )
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("A4 20장")
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
                    "  문서 본문을 설정만으로 임의 호스트에 보낼 수 있게 된다. " +
                    "테스트 스텁 서버는 AnthropicSettings 생성자 인자를 쓰면 되고, 설정 바인딩은 필요 없다."
            }.isEmpty()
    }

    private fun assemble(
        properties: LlmProperties,
        vararg profiles: String,
    ) = configuration.llmProvider(
        properties,
        MockEnvironment().apply { setActiveProfiles(*profiles) },
    )
}

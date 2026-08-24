package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

// infrastructure가 LLM composition root를 소유한다. 설정으로 strategy를 선택하고
// metrics decorator를 조립하므로 서비스와 core는 구체 provider를 알지 못한다.
// 호출 대상 URL은 설정으로 열지 않는다. 사용자 문서를 임의 호스트로 보낼 수 있는
// 설정 표면이 되기 때문이다. 각 Settings의 baseUrl은 테스트 스텁에만 사용한다.

/** LLM 벤더 설정. 바인딩 접두사는 `easydoc.llm`. */
@ConfigurationProperties(prefix = "easydoc.llm")
data class LlmProperties(
    val provider: String = OPENAI_PROVIDER_NAME,
    val model: String? = null,
    val effort: String? = null,
    val anthropicApiKey: Secret = Secret.EMPTY,
    val openAiApiKey: Secret = Secret.EMPTY,
    val pricing: LlmPricingProperties = LlmPricingProperties(),
)

/** 모델 가격은 코드 상수가 아니라 배포 설정으로 받는다. */
data class LlmPricingProperties(
    val inputUsdPerMillionTokens: BigDecimal? = null,
    val outputUsdPerMillionTokens: BigDecimal? = null,
) {
    fun toTokenPricing(): TokenPricing? {
        if (inputUsdPerMillionTokens == null && outputUsdPerMillionTokens == null) return null
        if (inputUsdPerMillionTokens == null || outputUsdPerMillionTokens == null) {
            throw ConfigurationException("LLM 입력·출력 토큰 단가는 함께 설정해야 합니다")
        }
        if (inputUsdPerMillionTokens.signum() < 0 || outputUsdPerMillionTokens.signum() < 0) {
            throw ConfigurationException("LLM 토큰 단가는 0 이상이어야 합니다")
        }
        return TokenPricing(inputUsdPerMillionTokens, outputUsdPerMillionTokens)
    }
}

/** 설정에서 [LlmProvider] 구현체를 고른다. */
@Configuration(proxyBeanMethods = false)
class LlmProviderConfiguration {
    @Bean
    fun llmProvider(properties: LlmProperties): LlmProvider {
        val provider =
            when (properties.provider.lowercase()) {
                OPENAI_PROVIDER_NAME -> {
                    OpenAiProvider(openAiSettings(properties))
                }

                ANTHROPIC_PROVIDER_NAME -> {
                    AnthropicProvider(anthropicSettings(properties))
                }

                else -> {
                    throw ConfigurationException(
                        "지원하지 않는 LLM provider 설정입니다 " +
                            "(가능: $OPENAI_PROVIDER_NAME, $ANTHROPIC_PROVIDER_NAME)",
                    )
                }
            }
        return MetricsLlmProviderDecorator(
            delegate = provider,
            pricing = properties.pricing.toTokenPricing(),
            observer = StructuredLogLlmCallObserver(),
        )
    }

    /** 호출 대상 URL은 adapter의 프로토콜 불변식을 사용한다. */
    private fun anthropicSettings(properties: LlmProperties) =
        AnthropicSettings(
            apiKey = properties.anthropicApiKey,
            model = properties.model.nonBlankOr(DEFAULT_ANTHROPIC_MODEL),
            effort = AnthropicEffort.from(properties.effort),
        )

    private fun openAiSettings(properties: LlmProperties) =
        OpenAiSettings(
            apiKey = properties.openAiApiKey,
            model = properties.model.nonBlankOr(DEFAULT_OPENAI_MODEL),
        )
}

private fun String?.nonBlankOr(default: String): String = this?.takeIf(String::isNotBlank) ?: default

package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.DEFAULT_MAX_TOKENS
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
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
    /**
     * 출력 토큰 상한. 운영 중 조정될 수 있는 값이라 코드 상수가 아니라 구성값이다
     * (CLAUDE.md 「상수와 구성 관리」). 기본값은 [DEFAULT_MAX_TOKENS] — 미설정 시
     * 동작이 바뀌지 않도록 출처를 하나로 유지한다.
     *
     * 값을 그대로 쓰지 마라 — [validatedMaxOutputTokens] 를 거쳐라. 이 필드는 운영자
     * 입력이라 [kr.easydoc.core.llm.LlmOptions] 의 `init` `require` (프로그래밍 오류용
     * `IllegalArgumentException`)에 검증을 맡기지 않는다.
     */
    val maxOutputTokens: Int = DEFAULT_MAX_TOKENS,
) {
    /**
     * [maxOutputTokens] 를 운영자 오설정 관점에서 검증한다 — [LlmPricingProperties.toTokenPricing]
     * 과 같은 자리·같은 예외 타입([ConfigurationException])을 쓴다. 0 이하는 배포 환경변수
     * 오타(빈 문자열이 0으로 바인딩되는 경우 등)이지 호출 코드의 버그가 아니다.
     *
     * 상한([MAX_OUTPUT_TOKENS_CEILING]) 검증도 여기서 한다. [kr.easydoc.core.llm.LlmOptions]
     * 의 `init` `require` 는 "1 이상"이라는 도메인 불변식만 지키면 되는 마지막 방어선이고,
     * 상한은 도메인 불변식이 아니라 "운영자 설정이 받아들일 수 있는 범위"라는 배포 정책이라
     * core 가 알 이유가 없다 — [LlmProperties] 가 이미 그 정책의 조립 지점이므로 여기 둔다.
     *
     * 호출자(현재는 [kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration],
     * 그리고 같은 규칙을 재사용하는 골든 LLM 레인
     * [kr.easydoc.infrastructure.quality.GoldenLlmLane]) 는 이 값을 거쳐서만 `LlmOptions`
     * 를 조립해야 한다.
     */
    fun validatedMaxOutputTokens(): Int {
        if (maxOutputTokens <= 0) {
            throw ConfigurationException(
                "easydoc.llm.max-output-tokens 는 1 이상이어야 합니다 (현재: $maxOutputTokens)",
            )
        }
        if (maxOutputTokens > MAX_OUTPUT_TOKENS_CEILING) {
            throw ConfigurationException(
                "easydoc.llm.max-output-tokens 는 $MAX_OUTPUT_TOKENS_CEILING 이하여야 합니다 " +
                    "(현재: $maxOutputTokens, 기준: A4 20장 분량 문서를 절단 없이 처리할 수 있는 상한)",
            )
        }
        return maxOutputTokens
    }
}

/**
 * [LlmProperties.maxOutputTokens] 의 운영자 설정 허용 상한.
 *
 * 사용자가 정한 기준(2026-09-02): **A4 20장 정도의 내용을 처리하는 것을 상한으로 잡는다.**
 * 도출(각 단계는 보수적으로 — 즉 상한이 낮아서 정상 문서를 거절하는 쪽으로는 틀리지 않게 —
 * 잡았다):
 *
 * | 단계 | 값 | 출처 |
 * |---|---|---|
 * | A4 1장 | ≈ 1,800자 | 관공서 문서 관행(10pt·줄간격 160%) — 규격이 아니라 관행이다 |
 * | 20장 원문 | ≈ 36,000자 | |
 * | 팽창비 상한 | 1.35 | 게이트 ⓪ 2차 측정 실측 — 장문 4건 중 최대값 |
 * | 변환문 | ≈ 48,600자 | |
 * | 토큰/글자 | 1.3 | 게이트 ⓪ 2차 측정 실측 — 나머지 문서가 1:1보다 높아 보수적으로 잡은 값 |
 * | **상한** | **64,000** | 위 계산(≈63,000)을 올림 — 게이트 ⓪ 1·2차 측정이 이 값으로 21,926자
 * |   |   | 문서를 절단 없이 처리했다(실제로 쓰인 값이기도 하다) |
 *
 * **약한 가정**: 팽창비·토큰/글자 비율은 n=4 표본에서 왔고, A4 1장 1,800자는 관행이지
 * 규격이 아니다. 셋 다 보수적으로(높게) 잡았으므로 이 상한이 정상 문서를 거절하는 방향으로
 * 틀릴 가능성은 낮다.
 */
const val MAX_OUTPUT_TOKENS_CEILING: Int = 64_000

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
    fun llmProvider(
        properties: LlmProperties,
        environment: Environment,
    ): LlmProvider {
        val provider =
            when (properties.provider.lowercase()) {
                OPENAI_PROVIDER_NAME -> {
                    requireMaxOutputTokensWithinDefaultModelLimit(
                        properties,
                        OPENAI_PROVIDER_NAME,
                        DEFAULT_OPENAI_MODEL,
                        OPENAI_DEFAULT_MODEL_MAX_OUTPUT_TOKENS,
                    )
                    OpenAiProvider(openAiSettings(properties))
                }

                ANTHROPIC_PROVIDER_NAME -> {
                    requireMaxOutputTokensWithinDefaultModelLimit(
                        properties,
                        ANTHROPIC_PROVIDER_NAME,
                        DEFAULT_ANTHROPIC_MODEL,
                        ANTHROPIC_DEFAULT_MODEL_MAX_OUTPUT_TOKENS,
                    )
                    AnthropicProvider(anthropicSettings(properties))
                }

                FAKE_PROVIDER_NAME -> {
                    requireFakeAllowed(environment)
                    LocalLlmProvider()
                }

                else -> {
                    throw ConfigurationException(
                        "지원하지 않는 LLM provider 설정입니다 " +
                            "(가능: $OPENAI_PROVIDER_NAME, $ANTHROPIC_PROVIDER_NAME, $FAKE_PROVIDER_NAME)",
                    )
                }
            }
        return MetricsLlmProviderDecorator(
            delegate = provider,
            pricing = properties.pricing.toTokenPricing(),
            observer = StructuredLogLlmCallObserver(),
        )
    }

    /**
     * `easydoc.llm.max-output-tokens` 가 provider **기본 모델**이 실제로 낼 수 있는 최대
     * 출력 토큰을 넘지 않는지 조립 시점에 확인한다.
     *
     * [LlmProperties.validatedMaxOutputTokens] 의 하한·상한([MAX_OUTPUT_TOKENS_CEILING])
     * 검증 위에 얹는 provider별 추가 제약이다 — 값 출처는 늘리지 않는다.
     *
     * **모델을 지정했을 때는 검사하지 않는다.** `easydoc.llm.model` 은 자유 문자열이라
     * 임의 모델의 한도를 신뢰성 있게 알 수 없다(전체 모델 한도 표는 조용히 낡는다). 아는
     * 것(어댑터 기본 모델의 공식 한도)만 검사하고, 모르는 것은 provider 호출 실패로
     * 드러나게 둔다 — 모델을 직접 지정한 운영자는 그 모델의 한도를 아는 쪽이 우리보다 낫다.
     */
    private fun requireMaxOutputTokensWithinDefaultModelLimit(
        properties: LlmProperties,
        providerName: String,
        defaultModel: String,
        defaultModelMaxOutputTokens: Int,
    ) {
        val configured = properties.validatedMaxOutputTokens()
        if (!properties.model.isNullOrBlank()) return
        if (configured > defaultModelMaxOutputTokens) {
            throw ConfigurationException(
                "easydoc.llm.max-output-tokens 는 $providerName 기본 모델($defaultModel)의 " +
                    "최대 출력 토큰($defaultModelMaxOutputTokens) 이하여야 합니다 (현재: $configured)",
            )
        }
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

    private fun requireFakeAllowed(environment: Environment) {
        if (!environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE, TEST_PROFILE))) {
            throw ConfigurationException(
                "fake LLM provider 는 $LOCAL_PROFILE/$TEST_PROFILE 프로필에서만 사용할 수 있습니다",
            )
        }
    }
}

private fun String?.nonBlankOr(default: String): String = this?.takeIf(String::isNotBlank) ?: default

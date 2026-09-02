package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.quality.JudgeLane
import kr.easydoc.core.quality.JudgeLaneDecision
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.llm.ANTHROPIC_PROVIDER_NAME
import kr.easydoc.infrastructure.llm.FAKE_PROVIDER_NAME
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.LlmProviderConfiguration
import kr.easydoc.infrastructure.llm.OPENAI_PROVIDER_NAME
import org.springframework.mock.env.MockEnvironment

/**
 * 골든 LLM 레인이 **제품과 같은 규칙으로** provider 를 만든다.
 *
 * 이 파일이 하는 일은 환경변수를 [LlmProperties] 로 옮기는 것뿐이다. provider 선택, 모델
 * 기본값, effort 해석, metrics decorator 조립은 전부 제품 composition root
 * ([LlmProviderConfiguration])가 한다 — 해석 규칙을 레인에 한 벌 더 두면 두 벌이 어긋난다.
 *
 * **2026-08-27 실측이 그 어긋남이었다.** 레인이 `OPENAI_API_KEY` 의 *존재*만 보고 provider 를
 * 골라 제품이 쓰지 않는 벤더를 쟀고, `EASYDOC_LLM_MODEL`·`EASYDOC_LLM_EFFORT` 를 통째로
 * 무시해 제품과 다른 effort 로 1시간 11분을 썼다. 그 값은 품질 측정치가 아니다.
 */
internal object GoldenLlmLane {
    /** 읽는 환경변수 이름은 제품 `application.yml` 과 같다. */
    const val PROVIDER_ENV: String = "EASYDOC_LLM_PROVIDER"

    const val MODEL_ENV: String = "EASYDOC_LLM_MODEL"

    const val EFFORT_ENV: String = "EASYDOC_LLM_EFFORT"

    const val ANTHROPIC_KEY_ENV: String = "ANTHROPIC_API_KEY"

    const val OPENAI_KEY_ENV: String = "OPENAI_API_KEY"

    /**
     * 제품 `application.yml` 과 같은 이름이다(`easydoc.llm.max-output-tokens`). 게이트 ⓪
     * 측정 목적이 상한을 구성으로 바꿔 가며 장문을 재는 것이라, 이 값을 무시하면 레인이
     * 제품과 다른 조건([GoldenLlmLane] 최상단 KDoc — provider 사고와 같은 계열)을 재게 된다.
     */
    const val MAX_OUTPUT_TOKENS_ENV: String = "EASYDOC_LLM_MAX_OUTPUT_TOKENS"

    /**
     * [PROVIDER_ENV] 미설정 시 기본값.
     *
     * `LlmProperties.provider` 의 코드 기본값(openai)이 아니라 **배포 기본값**을 따른다 —
     * api·worker `application.yml` 이 `${EASYDOC_LLM_PROVIDER:anthropic}` 로 덮어쓰므로,
     * 제품이 실제로 도는 벤더는 anthropic 이다. 둘이 어긋나면 [GoldenLlmLaneTest] 의
     * 기본값 대조가 깨진다.
     */
    const val DEFAULT_PROVIDER: String = ANTHROPIC_PROVIDER_NAME

    /**
     * [env] 가 준 환경으로 이 레인을 돌릴 수 있는지 판정한다.
     *
     * [env] 를 인자로 받는 이유는 선택 규칙 자체를 유료 호출 없이 시험하기 위해서다
     * ([GoldenLlmLaneTest]). 실제 레인은 `System::getenv` 를 넘긴다.
     */
    fun plan(env: (String) -> String?): LanePlan {
        val providerName = env(PROVIDER_ENV).orDefault(DEFAULT_PROVIDER).lowercase()
        val blocked = fakeRejection(providerName) ?: missingSecret(providerName, env)
        return blocked ?: assemble(providerName, env)
    }

    /**
     * fake 는 이 레인에서 거부한다. 제품은 local·test 프로필에서 fake 를 허용하지만
     * ([LlmProviderConfiguration]), 고정 문장을 채점한 결과는 품질 판단이 아니다
     * (`docs/pilot-runbook.md` 「사전 조건」). skip 이 아니라 **실패**로 알린다 — 이 값을
     * 설정한 사람은 레인을 돌릴 의도였고, 조용히 건너뛰면 그 의도가 사라진다.
     */
    private fun fakeRejection(providerName: String): LanePlan? =
        if (providerName == FAKE_PROVIDER_NAME) {
            LanePlan.Unusable(
                "$PROVIDER_ENV=$FAKE_PROVIDER_NAME 으로는 품질을 잴 수 없다 — " +
                    "fake 는 고정 문장을 돌려주므로 채점 결과가 모델 판단이 아니다.",
            )
        } else {
            null
        }

    /**
     * **선택한 provider 의 키만 본다.** 다른 벤더 키가 있다고 그쪽으로 넘어가지 않는다 —
     * 그 대체가 2026-08-27 측정을 무효로 만든 원인이다. 모르는 이름은 여기서 판단하지 않고
     * 제품 조립([assemble])에 맡긴다. 오타를 skip 으로 숨기지 않기 위해서다.
     */
    private fun missingSecret(
        providerName: String,
        env: (String) -> String?,
    ): LanePlan? {
        val keyEnv = keyEnvOf(providerName)
        val decision = keyEnv?.let { JudgeLane.decide(env(it)) }
        return if (decision == JudgeLaneDecision.SKIPPED_MISSING_SECRET) {
            LanePlan.Skipped("$providerName 의 $keyEnv 가 없어 골든 LLM 레인을 skip 한다")
        } else {
            null
        }
    }

    private fun keyEnvOf(providerName: String): String? =
        when (providerName) {
            ANTHROPIC_PROVIDER_NAME -> ANTHROPIC_KEY_ENV
            OPENAI_PROVIDER_NAME -> OPENAI_KEY_ENV
            else -> null
        }

    /**
     * 조립은 제품 코드가 한다. 모르는 provider 이름·지원하지 않는 effort 값의 거절 문구도
     * 제품 것을 그대로 쓴다 — 레인이 다시 적으면 규칙이 두 벌이 된다.
     *
     * `MockEnvironment` 에는 프로필을 넣지 않는다. 이 자리에서 필요한 것은 fake 허용 판정뿐이고
     * fake 는 이미 위에서 거부했다.
     *
     * 제품 조립([LlmProviderConfiguration.llmProvider])은 `properties.maxOutputTokens` 를
     * 읽지 않는다(그 필드는 `ConversionWorkerConfiguration` 이 조립 시점에 쓴다). 그래서 여기서
     * 같은 값으로 [LlmOptions] 를 만들어 함께 반환한다 — 해석 자체는 [properties] 하나뿐이고
     * provider 조립과 옵션 조립이 **같은 [LlmProperties] 인스턴스**에서 갈라질 뿐이다.
     *
     * `props.maxOutputTokens` 를 직접 읽지 않고 [LlmProperties.validatedMaxOutputTokens] 를
     * 거친다 — `ConversionWorkerConfiguration` 이 쓰는 것과 같은 관문이다. 직접 읽으면 레인이
     * 상한([kr.easydoc.infrastructure.llm.MAX_OUTPUT_TOKENS_CEILING])을 우회해 제품이 거절할
     * 값으로도 유료 호출을 낼 수 있다 — 검증 규칙 자체는 여기서 다시 적지 않는다.
     */
    private fun assemble(
        providerName: String,
        env: (String) -> String?,
    ): LanePlan =
        try {
            val props = properties(providerName, env)
            LanePlan.Ready(
                provider = LlmProviderConfiguration().llmProvider(props, MockEnvironment()),
                options = LlmOptions(maxTokens = props.validatedMaxOutputTokens()),
            )
        } catch (exc: ConfigurationException) {
            LanePlan.Unusable("제품 설정 규칙이 이 레인 설정을 거절했다: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            // maxOutputTokensOf 의 파싱 거절과 LlmOptions.init 의 0 이하 거절이 둘 다 여기로 온다 —
            // 둘 다 "이 레인 설정으로는 조립할 수 없다" 는 같은 결이라 문구를 하나로 묶는다.
            LanePlan.Unusable("이 레인 설정을 해석할 수 없다: ${exc.message}")
        }

    private fun properties(
        providerName: String,
        env: (String) -> String?,
    ): LlmProperties =
        LlmProperties(
            provider = providerName,
            model = env(MODEL_ENV),
            effort = env(EFFORT_ENV),
            anthropicApiKey = secretOf(env(ANTHROPIC_KEY_ENV)),
            openAiApiKey = secretOf(env(OPENAI_KEY_ENV)),
            maxOutputTokens = maxOutputTokensOf(env),
            // 단가는 받지 않는다. 레인이 내는 값은 통과율과 실패 원인이고, 비용 추정은
            // 운영 관측(metrics decorator)의 몫이다. 여기서 받으면 파싱 실패 표면만 늘어난다.
        )

    /**
     * 미설정·빈 값은 [LlmProperties] 의 기본값(= core `DEFAULT_MAX_TOKENS`)으로 접는다 —
     * 그 기본값을 여기 다시 적으면 출처가 둘이 된다.
     *
     * **값이 있는데 정수가 아니면 기본값으로 접지 않는다.** 조용히 접으면 운영자가
     * `EASYDOC_LLM_MAX_OUTPUT_TOKENS=32k` 처럼 잘못 넣었을 때 레인이 그것을 모르고 다른
     * 조건으로 측정한다 — 같은 값에서 제품(Spring 바인딩)은 기동을 거부하므로 레인과 제품의
     * 행동이 갈린다. 다른 필드가 잘못됐을 때(`missingSecret`·제품 조립 거절)와 같은 결로
     * [IllegalArgumentException] 을 던져 [assemble] 이 [LanePlan.Unusable] 로 접게 한다.
     */
    private fun maxOutputTokensOf(env: (String) -> String?): Int {
        val raw = env(MAX_OUTPUT_TOKENS_ENV)?.takeIf(String::isNotBlank) ?: return LlmProperties().maxOutputTokens
        return raw.toIntOrNull()
            ?: throw IllegalArgumentException(
                "$MAX_OUTPUT_TOKENS_ENV='$raw' 은 정수가 아니다",
            )
    }

    private fun secretOf(value: String?): Secret = value?.takeIf(String::isNotBlank)?.let(::Secret) ?: Secret.EMPTY

    private fun String?.orDefault(default: String): String = this?.takeIf(String::isNotBlank) ?: default
}

/** 이 환경에서 레인을 돌릴 수 있는가. */
internal sealed interface LanePlan {
    /**
     * 돌릴 수 있다. [provider] 는 제품이 조립한 것 그대로다. [options] 는 실제 변환 호출에
     * 실리는 [LlmOptions] — 출력 토큰 상한이 이 문서 채점에 어떤 조건이었는지는 채점 결과와
     * 함께 남아야 하는 측정 조건이다(게이트 ⓪).
     */
    class Ready(
        val provider: LlmProvider,
        val options: LlmOptions,
    ) : LanePlan {
        /**
         * **무엇으로 쟀는지** 한 줄. provider 자신의 `toString` 을 그대로 쓴다 — 어댑터가 실제로
         * 들고 있는 모델·effort 이고, 레인이 따로 적으면 또 두 벌이 된다. API 키는 [Secret] 이
         * 막으므로 이 문자열에 실리지 않는다. `max_tokens` 는 provider 의 상태가 아니라 호출
         * 옵션이라 provider 의 `toString` 에 실리지 않으므로 여기서 따로 붙인다.
         */
        val description: String get() = "provider=${provider.name} settings=$provider max_tokens=${options.maxTokens}"
    }

    /** 비밀값이 없다. 건너뛴다. */
    class Skipped(val reason: String) : LanePlan

    /** 이 설정으로는 품질을 잴 수 없다. 실패로 알린다. */
    class Unusable(val reason: String) : LanePlan
}

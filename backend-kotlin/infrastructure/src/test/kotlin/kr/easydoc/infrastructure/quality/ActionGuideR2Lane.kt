package kr.easydoc.infrastructure.quality

import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.actionguide.ProviderActionGuideJobRunner
import kr.easydoc.infrastructure.llm.LlmPricingProperties
import java.math.BigDecimal

/**
 * R2 유료 평가의 입장 조건과 고정 표본을 한 곳에서 해석한다.
 *
 * 이 레인은 기존 `testLlm` 태스크에 붙어 있지만, 실수로 행동 안내문 호출이 추가되지 않도록
 * 별도의 명시적 토글을 요구한다. 기본 표본은 개발용 6건이며 보류 표본은 `holdout` 또는
 * `both`를 직접 지정해야만 선택된다. 보류 결과를 프롬프트 튜닝 입력으로 읽는 경로는 이
 * 테스트에 두지 않는다.
 */
internal object ActionGuideR2Lane {
    const val ENABLED_ENV: String = "EASYDOC_R2_LANE_ENABLED"
    const val COHORT_ENV: String = "EASYDOC_R2_LANE_COHORT"
    const val DOCUMENTS_ENV: String = "EASYDOC_R2_LANE_DOCUMENTS"
    const val RUNS_ENV: String = "EASYDOC_R2_LANE_RUNS"
    const val MAX_CALLS_ENV: String = "EASYDOC_R2_LANE_MAX_CALLS"
    const val MAX_USD_ENV: String = "EASYDOC_R2_LANE_MAX_USD"
    const val TRANSCRIPT_ENV: String = "EASYDOC_R2_LANE_TRANSCRIPT_DIR"
    const val PREFLIGHT_ONLY_ENV: String = "EASYDOC_R2_LANE_PREFLIGHT_ONLY"

    /** Production `ProviderActionGuideWorkerConfiguration`의 별도 action-guide 호출 상한. */
    const val ACTION_GUIDE_MAX_OUTPUT_TOKENS: Int = ProviderActionGuideJobRunner.MAX_OUTPUT_TOKENS

    /** Production action-guide provider 조립이 쓰는 읽기 제한(초). */
    const val ACTION_GUIDE_READ_TIMEOUT_SECONDS: Long = 90

    /** 반복은 표본당 세 회까지로 제한한다. 호출·달러 상한과 함께 opt-in 실수를 좁힌다. */
    private const val MAX_RUNS: Int = 3

    /** `docs/reports/2026-09-18-easy-read-r0-baseline.md` §2의 개발용 6건. */
    val DEVELOPMENT_IDS: Set<String> = linkedSetOf("070", "087", "023", "072", "077", "088")

    /** 같은 기준선의 보류 평가용 4건. 기본 선택에 포함하지 않는다. */
    val HOLDOUT_IDS: Set<String> = linkedSetOf("089", "097", "074", "101")

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun plan(env: (String) -> String?): ActionGuideR2LanePlan {
        val enabled = env(ENABLED_ENV)?.trim()
        when {
            enabled.isNullOrEmpty() || enabled.equals("false", ignoreCase = true) -> {
                return ActionGuideR2LanePlan.Disabled
            }

            !enabled.equals("true", ignoreCase = true) -> {
                return ActionGuideR2LanePlan.Unusable(
                    "$ENABLED_ENV 는 true 또는 false 여야 한다(현재: $enabled)",
                )
            }
        }

        val cohort =
            parseCohort(env(COHORT_ENV))
                ?: return ActionGuideR2LanePlan.Unusable(
                    "$COHORT_ENV 는 development, holdout, both 중 하나여야 한다",
                )
        val allowedIds = cohort.ids
        val ids =
            parseIds(env(DOCUMENTS_ENV), allowedIds)
                ?: return ActionGuideR2LanePlan.Unusable(
                    "$DOCUMENTS_ENV 에는 선택한 $cohort 표본의 문서 id만 중복 없이 적어야 한다",
                )
        val runs =
            positiveInt(env(RUNS_ENV), default = 1)
                ?: return ActionGuideR2LanePlan.Unusable("$RUNS_ENV 는 1 이상 정수여야 한다")
        if (runs > MAX_RUNS) {
            return ActionGuideR2LanePlan.Unusable("$RUNS_ENV 는 $MAX_RUNS 이하이어야 한다")
        }
        val expectedCalls = ids.size * runs
        val maxCalls =
            positiveInt(env(MAX_CALLS_ENV), default = expectedCalls)
                ?: return ActionGuideR2LanePlan.Unusable("$MAX_CALLS_ENV 는 1 이상 정수여야 한다")
        if (maxCalls < expectedCalls) {
            return ActionGuideR2LanePlan.Unusable(
                "$MAX_CALLS_ENV=$maxCalls 는 계획 호출 수 $expectedCalls 보다 작다",
            )
        }
        val maxUsd =
            positiveDecimal(env(MAX_USD_ENV))
                ?: return ActionGuideR2LanePlan.Unusable("$MAX_USD_ENV 는 0보다 큰 숫자여야 한다")

        // R2 production runner는 변환 worker와 같은 provider/model/effort·키·단가를 쓰되,
        // 출력 상한과 읽기 제한만 action-guide 전용 값으로 덮어쓴다.
        val providerPlan =
            GoldenLlmLane.plan { key ->
                when (key) {
                    GoldenLlmLane.MAX_OUTPUT_TOKENS_ENV -> ACTION_GUIDE_MAX_OUTPUT_TOKENS.toString()
                    GoldenLlmLane.READ_TIMEOUT_ENV -> ACTION_GUIDE_READ_TIMEOUT_SECONDS.toString()
                    else -> env(key)
                }
            }
        return when (providerPlan) {
            is LanePlan.Skipped -> {
                ActionGuideR2LanePlan.Skipped(providerPlan.reason)
            }

            is LanePlan.Unusable -> {
                ActionGuideR2LanePlan.Unusable(providerPlan.reason)
            }

            is LanePlan.Ready -> {
                val inputPrice = providerPlan.pricing.inputUsdPerMillionTokens
                val outputPrice = providerPlan.pricing.outputUsdPerMillionTokens
                if (inputPrice == null || outputPrice == null) {
                    ActionGuideR2LanePlan.Unusable(
                        "R2 유료 레인은 ${GoldenLlmLane.INPUT_PRICE_ENV}/" +
                            "${GoldenLlmLane.OUTPUT_PRICE_ENV} 단가를 모두 요구한다",
                    )
                } else {
                    ActionGuideR2LanePlan.Ready(
                        cohort = cohort,
                        documentIds = ids,
                        runs = runs,
                        maxCalls = maxCalls,
                        maxUsd = maxUsd,
                        provider = providerPlan.provider,
                        options = providerPlan.options,
                        pricing = providerPlan.pricing,
                    )
                }
            }
        }
    }

    private fun parseCohort(raw: String?): ActionGuideR2Cohort? =
        when (raw?.trim()?.lowercase().orEmpty()) {
            "", "development" -> ActionGuideR2Cohort.DEVELOPMENT
            "holdout" -> ActionGuideR2Cohort.HOLDOUT
            "both" -> ActionGuideR2Cohort.BOTH
            else -> null
        }

    @Suppress("ReturnCount")
    private fun parseIds(
        raw: String?,
        allowed: Set<String>,
    ): List<String>? {
        if (raw.isNullOrBlank()) return allowed.toList()
        val ids = raw.split(',').map(String::trim)
        if (ids.any(String::isEmpty) || ids.size != ids.toSet().size) return null
        return ids.takeIf { it.all(allowed::contains) }
    }

    private fun positiveInt(
        raw: String?,
        default: Int,
    ): Int? {
        val value =
            raw?.takeIf(String::isNotBlank)?.toIntOrNull()
                ?: return if (raw.isNullOrBlank()) default else null
        return value.takeIf { it > 0 }
    }

    private fun positiveDecimal(raw: String?): BigDecimal? {
        val value = raw?.takeIf(String::isNotBlank)?.toBigDecimalOrNull() ?: return null
        return value.takeIf { it > BigDecimal.ZERO }
    }
}

internal enum class ActionGuideR2Cohort(val ids: Set<String>) {
    DEVELOPMENT(ActionGuideR2Lane.DEVELOPMENT_IDS),
    HOLDOUT(ActionGuideR2Lane.HOLDOUT_IDS),
    BOTH(ActionGuideR2Lane.DEVELOPMENT_IDS + ActionGuideR2Lane.HOLDOUT_IDS),
}

internal sealed interface ActionGuideR2LanePlan {
    data object Disabled : ActionGuideR2LanePlan

    data class Skipped(val reason: String) : ActionGuideR2LanePlan

    data class Unusable(val reason: String) : ActionGuideR2LanePlan

    data class Ready(
        val cohort: ActionGuideR2Cohort,
        val documentIds: List<String>,
        val runs: Int,
        val maxCalls: Int,
        val maxUsd: BigDecimal,
        val provider: LlmProvider,
        val options: LlmOptions,
        val pricing: LlmPricingProperties,
    ) : ActionGuideR2LanePlan {
        val description: String
            get() =
                "provider=${provider.name} settings=$provider " +
                    "max_tokens=${options.maxTokens} " +
                    "read_timeout_s=${ActionGuideR2Lane.ACTION_GUIDE_READ_TIMEOUT_SECONDS}"
    }
}

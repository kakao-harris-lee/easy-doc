package kr.easydoc.infrastructure.llm

import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.TimeUnit

data class TokenPricing(
    val inputUsdPerMillionTokens: BigDecimal,
    val outputUsdPerMillionTokens: BigDecimal,
) {
    init {
        require(inputUsdPerMillionTokens.signum() >= 0) { "입력 토큰 단가는 0 이상이어야 합니다" }
        require(outputUsdPerMillionTokens.signum() >= 0) { "출력 토큰 단가는 0 이상이어야 합니다" }
    }

    fun estimate(
        inputTokens: Int,
        outputTokens: Int,
    ): BigDecimal {
        require(inputTokens >= 0 && outputTokens >= 0) { "토큰 수는 0 이상이어야 합니다" }
        val weighted =
            inputUsdPerMillionTokens
                .multiply(BigDecimal(inputTokens))
                .add(outputUsdPerMillionTokens.multiply(BigDecimal(outputTokens)))
        return weighted.divide(ONE_MILLION, MathContext.DECIMAL64)
    }

    private companion object {
        val ONE_MILLION: BigDecimal = BigDecimal(1_000_000)
    }
}

enum class LlmCallOutcome {
    SUCCESS,
    FAILURE,
}

data class LlmCallObservation(
    val provider: String,
    val model: String?,
    val latencyMs: Long,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val estimatedCostUsd: BigDecimal?,
    val outcome: LlmCallOutcome,
)

fun interface LlmCallObserver {
    fun record(observation: LlmCallObservation)

    companion object {
        val NONE: LlmCallObserver = LlmCallObserver { }
    }
}

/** 본문 없이 provider·model·토큰·지연·예상 비용만 구조화 로그로 남긴다. */
class StructuredLogLlmCallObserver : LlmCallObserver {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(observation: LlmCallObservation) {
        logger.info(
            "llm_call provider={} model={} outcome={} latency_ms={} " +
                "input_tokens={} output_tokens={} estimated_cost_usd={}",
            observation.provider,
            observation.model,
            observation.outcome,
            observation.latencyMs,
            observation.inputTokens,
            observation.outputTokens,
            observation.estimatedCostUsd,
        )
    }
}

/** 선택된 provider를 감싸 호출 결과에 비용·성능 관측값을 추가한다. */
class MetricsLlmProviderDecorator(
    private val delegate: LlmProvider,
    /** 모델별 단가에 없을 때 쓰는 기본값. `easydoc.llm.pricing.input/output-usd-per-million-tokens`. */
    private val pricing: TokenPricing?,
    /**
     * 응답이 보고한 model 문자열로 찾는 모델별 단가(`easydoc.llm.pricing.models.<model-id>`).
     * 없으면 [pricing] 으로 떨어진다 — `LlmPricingProperties` KDoc 「모델별 단가」와 같은 순서다.
     * 기본값 `emptyMap()` 이 기존 호출부·테스트를 그대로 통과시킨다(모델별 단가를 쓰지
     * 않던 시절과 동작이 같다).
     */
    private val modelPricing: Map<String, TokenPricing> = emptyMap(),
    private val observer: LlmCallObserver = LlmCallObserver.NONE,
    private val nanoTime: () -> Long = System::nanoTime,
) : LlmProvider {
    override val name: String get() = delegate.name

    override fun toString(): String = "MetricsLlmProviderDecorator(delegate=$delegate)"

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        val startedAt = nanoTime()
        val attempt = runCatching { delegate.complete(prompt, options) }
        val latencyMs = elapsedMillis(startedAt)
        return attempt.fold(
            onSuccess = { completion -> observedSuccess(completion, latencyMs) },
            onFailure = { failure ->
                recordFailure(latencyMs)
                throw failure
            },
        )
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((nanoTime() - startedAt).coerceAtLeast(0))

    private fun recordSafely(observation: LlmCallObservation) {
        runCatching { observer.record(observation) }
            .onFailure { failure ->
                LOGGER.warn("LLM 관측 기록에 실패했습니다 (observer={})", failure::class.java.simpleName)
            }
    }

    private fun observedSuccess(
        completion: LlmCompletion,
        latencyMs: Long,
    ): LlmCompletion {
        val resolvedPricing = modelPricing[completion.model] ?: pricing
        val cost = resolvedPricing?.estimate(completion.inputTokens, completion.outputTokens)
        val observed =
            completion.copy(
                latencyMs = latencyMs,
                estimatedCostUsd = cost,
                pricingInputUsdPerMtok = resolvedPricing?.inputUsdPerMillionTokens,
                pricingOutputUsdPerMtok = resolvedPricing?.outputUsdPerMillionTokens,
            )
        recordSafely(
            LlmCallObservation(
                provider = observed.provider,
                model = observed.model,
                latencyMs = latencyMs,
                inputTokens = observed.inputTokens,
                outputTokens = observed.outputTokens,
                estimatedCostUsd = cost,
                outcome = LlmCallOutcome.SUCCESS,
            ),
        )
        return observed
    }

    private fun recordFailure(latencyMs: Long) {
        recordSafely(
            LlmCallObservation(
                provider = name,
                model = null,
                latencyMs = latencyMs,
                inputTokens = null,
                outputTokens = null,
                estimatedCostUsd = null,
                outcome = LlmCallOutcome.FAILURE,
            ),
        )
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(MetricsLlmProviderDecorator::class.java)
    }
}

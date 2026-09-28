package kr.easydoc.application.conversion

import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.llm.LlmCompletion
import java.time.Instant

/** Conversion ledger snapshots use call-time metadata, never persistence-time values. */
internal object ConversionCallRecords {
    fun completed(
        purpose: LlmCallPurpose,
        provider: String,
        completion: LlmCompletion,
        charCount: Int,
        calledAt: Instant,
    ): LlmCallRecord =
        LlmCallRecord(
            purpose = purpose,
            provider = provider,
            model = completion.model,
            inputTokens = completion.inputTokens,
            outputTokens = completion.outputTokens,
            latencyMs = completion.latencyMs,
            estimatedCostUsd = completion.estimatedCostUsd,
            pricingInputUsdPerMtok = completion.pricingInputUsdPerMtok,
            pricingOutputUsdPerMtok = completion.pricingOutputUsdPerMtok,
            charCount = charCount,
            calledAt = calledAt,
            outcome = LlmCallOutcome.COMPLETED,
        )

    fun failed(
        purpose: LlmCallPurpose,
        provider: String,
        failure: LlmProviderException,
        charCount: Int,
        calledAt: Instant,
    ): LlmCallRecord =
        LlmCallRecord(
            purpose = purpose,
            provider = provider,
            model = null,
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = null,
            estimatedCostUsd = null,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = charCount,
            calledAt = calledAt,
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            failureClass = failure.javaClass.simpleName,
        )
}

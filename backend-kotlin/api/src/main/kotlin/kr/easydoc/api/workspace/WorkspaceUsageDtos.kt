package kr.easydoc.api.workspace

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.usage.PurposeUsage
import kr.easydoc.application.usage.WorkspaceUsage
import java.math.BigDecimal

/** `GET /workspaces/{workspace_id}/usage` 응답. 계약 `components/schemas/WorkspaceUsageResponse`(2.20.0). */
data class WorkspaceUsageResponse(
    @get:JsonProperty("documents") val documents: Int,
    @get:JsonProperty("characters") val characters: Long,
    @get:JsonProperty("credits") val credits: Long,
    @get:JsonProperty("llm_calls") val llmCalls: Int,
    @get:JsonProperty("input_tokens") val inputTokens: Long,
    @get:JsonProperty("output_tokens") val outputTokens: Long,
    @get:JsonProperty("estimated_cost_usd") val estimatedCostUsd: String?,
    @get:JsonProperty("cost_unknown_calls") val costUnknownCalls: Int,
    /** 완성 자체가 나지 않은 호출 수(`outcome = provider_error`, V18). 계약 2.26.0 신설. */
    @get:JsonProperty("failed_calls") val failedCalls: Int,
    @get:JsonProperty("by_purpose") val byPurpose: List<PurposeUsageItemResponse>,
) {
    companion object {
        fun of(usage: WorkspaceUsage): WorkspaceUsageResponse =
            WorkspaceUsageResponse(
                documents = usage.documents,
                characters = usage.characters,
                credits = usage.credits,
                llmCalls = usage.llmCalls,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                estimatedCostUsd = usage.estimatedCostUsd.toWireString(),
                costUnknownCalls = usage.costUnknownCalls,
                failedCalls = usage.failedCalls,
                byPurpose = usage.byPurpose.map(PurposeUsageItemResponse::of),
            )
    }
}

/** `WorkspaceUsageResponse.by_purpose` 항목. 계약 `components/schemas/PurposeUsageItem`. */
data class PurposeUsageItemResponse(
    @get:JsonProperty("purpose") val purpose: String,
    @get:JsonProperty("llm_calls") val llmCalls: Int,
    @get:JsonProperty("input_tokens") val inputTokens: Long,
    @get:JsonProperty("output_tokens") val outputTokens: Long,
    @get:JsonProperty("estimated_cost_usd") val estimatedCostUsd: String?,
    /** 이 목적으로 완성 자체가 나지 않은 호출 수. 계약 2.26.0 신설. */
    @get:JsonProperty("failed_calls") val failedCalls: Int,
) {
    companion object {
        fun of(usage: PurposeUsage): PurposeUsageItemResponse =
            PurposeUsageItemResponse(
                purpose = usage.purpose.wireName,
                llmCalls = usage.llmCalls,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                estimatedCostUsd = usage.estimatedCostUsd.toWireString(),
                failedCalls = usage.failedCalls,
            )
    }
}

/**
 * 부동소수 반올림 오차를 피하려고 `BigDecimal`을 문자열로 그대로 싣는다(계약
 * `estimated_cost_usd` 스키마 설명) — `toPlainString()`은 지수 표기(`1E+2`)를 쓰지 않는다.
 */
private fun BigDecimal?.toWireString(): String? = this?.toPlainString()

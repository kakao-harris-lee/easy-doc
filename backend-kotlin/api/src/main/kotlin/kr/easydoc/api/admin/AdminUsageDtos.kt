package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.usage.UsageReportRow
import kr.easydoc.application.usage.UsageReportRows
import kr.easydoc.core.privacy.CONTENT_MASK

/**
 * `GET /admin/usage` 응답 — `usage-report`(U3, CSV) 프로필과 같은 행을 JSON으로 낸다
 * (어드민 최소 계획 §2 결정 4). CSV는 여전히 그 프로필이 맡는다.
 */
data class AdminUsageResponse(
    @get:JsonProperty("rows") val rows: List<AdminUsageRowResponse>,
    @get:JsonProperty("from") val from: String,
    @get:JsonProperty("to") val to: String,
) {
    companion object {
        fun of(result: UsageReportRows): AdminUsageResponse =
            AdminUsageResponse(
                rows = result.rows.map(AdminUsageRowResponse::of),
                from = result.from.toString(),
                to = result.to.toString(),
            )
    }
}

data class AdminUsageRowResponse(
    /**
     * 탈퇴한 계정이면 `null`이다(회원 탈퇴 계획 `docs/plans/2026-09-09-account-deletion.md`,
     * V19 `llm_calls.user_id SET NULL`, 계약 2.28.0) — `workspace_id`와 같은 이유로
     * `anyOf: [uuid, null]`이다.
     */
    @get:JsonProperty("user_id") val userId: String?,
    /** 탈퇴한 계정이면 `null`이다 — [userId]와 함께 사라진다. */
    @get:JsonProperty("owner_email") val ownerEmail: String?,
    @get:JsonProperty("workspace_id") val workspaceId: String?,
    @get:JsonProperty("workspace_name") val workspaceName: String?,
    @get:JsonProperty("documents") val documents: Int,
    @get:JsonProperty("characters") val characters: Long,
    @get:JsonProperty("credits") val credits: Long,
    @get:JsonProperty("llm_calls") val llmCalls: Int,
    /** 완성 자체가 나지 않은 호출 수(`outcome = provider_error`, V18). 계약 2.26.0 신설. */
    @get:JsonProperty("failed_calls") val failedCalls: Int,
    @get:JsonProperty("input_tokens") val inputTokens: Long,
    @get:JsonProperty("output_tokens") val outputTokens: Long,
    @get:JsonProperty("estimated_cost_usd") val estimatedCostUsd: String?,
    @get:JsonProperty("cost_unknown_calls") val costUnknownCalls: Int,
) {
    /** 소유자 이메일·워크스페이스 이름을 찍지 않는다 — `UsageReportRow`와 같은 규약. */
    override fun toString(): String =
        "AdminUsageRowResponse(userId=$userId, ownerEmail=$CONTENT_MASK, workspaceId=$workspaceId, " +
            "workspaceName=${workspaceName?.let { CONTENT_MASK }}, documents=$documents, characters=$characters, " +
            "credits=$credits, llmCalls=$llmCalls, failedCalls=$failedCalls, inputTokens=$inputTokens, " +
            "outputTokens=$outputTokens, estimatedCostUsd=$estimatedCostUsd, costUnknownCalls=$costUnknownCalls)"

    companion object {
        fun of(row: UsageReportRow): AdminUsageRowResponse =
            AdminUsageRowResponse(
                userId = row.userId?.toString(),
                ownerEmail = row.ownerEmail,
                workspaceId = row.workspaceId?.toString(),
                workspaceName = row.workspaceName,
                documents = row.documents,
                characters = row.characters,
                credits = row.credits,
                llmCalls = row.llmCalls,
                failedCalls = row.failedCalls,
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                estimatedCostUsd = row.estimatedCostUsd?.toPlainString(),
                costUnknownCalls = row.costUnknownCalls,
            )
    }
}

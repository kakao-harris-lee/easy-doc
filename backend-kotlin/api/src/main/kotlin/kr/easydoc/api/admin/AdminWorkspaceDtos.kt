package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.api.invoice.InvoiceRequestResponse
import kr.easydoc.api.workspace.CreditTransactionResponse
import kr.easydoc.application.admin.AdminConversionRow
import kr.easydoc.application.admin.AdminWorkspaceDetail
import kr.easydoc.application.admin.AdminWorkspaceListPage
import kr.easydoc.application.admin.AdminWorkspaceSummary
import kr.easydoc.application.invoice.InvoiceRequestView
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.workspace.Workspace
import java.time.Instant

/** `GET /admin/workspaces`·`GET /admin/workspaces/{workspace_id}` 공용 요약. */
data class AdminWorkspaceSummaryResponse(
    @get:JsonProperty("workspace_id") val workspaceId: String,
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("owner_email") val ownerEmail: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("credit_balance") val creditBalance: Int,
    @get:JsonProperty("credit_reserved") val creditReserved: Int,
    @get:JsonProperty("credit_available") val creditAvailable: Int,
    @get:JsonProperty("month_documents") val monthDocuments: Int,
    @get:JsonProperty("month_credits") val monthCredits: Long,
    @get:JsonProperty("month_cost_usd") val monthCostUsd: String?,
) {
    /** 이름·이메일을 찍지 않는다 — [Workspace.toString]과 같은 규약. */
    override fun toString(): String =
        "AdminWorkspaceSummaryResponse(workspaceId=$workspaceId, name=$CONTENT_MASK, ownerEmail=$CONTENT_MASK, " +
            "createdAt=$createdAt, creditBalance=$creditBalance, creditReserved=$creditReserved, " +
            "creditAvailable=$creditAvailable, monthDocuments=$monthDocuments, monthCredits=$monthCredits, " +
            "monthCostUsd=$monthCostUsd)"

    companion object {
        fun of(summary: AdminWorkspaceSummary): AdminWorkspaceSummaryResponse =
            AdminWorkspaceSummaryResponse(
                workspaceId = summary.workspaceId.toString(),
                name = summary.name,
                ownerEmail = summary.ownerEmail,
                createdAt = summary.createdAt,
                creditBalance = summary.creditBalance,
                creditReserved = summary.creditReserved,
                creditAvailable = summary.creditAvailable,
                monthDocuments = summary.monthDocuments,
                monthCredits = summary.monthCredits,
                monthCostUsd = summary.monthCostUsd?.toPlainString(),
            )
    }
}

/** `GET /admin/workspaces` 응답. */
data class AdminWorkspaceListResponse(
    @get:JsonProperty("items") val items: List<AdminWorkspaceSummaryResponse>,
    @get:JsonProperty("page") val page: Int,
    @get:JsonProperty("size") val size: Int,
    @get:JsonProperty("total") val total: Int,
) {
    companion object {
        fun of(page: AdminWorkspaceListPage): AdminWorkspaceListResponse =
            AdminWorkspaceListResponse(
                items = page.items.map(AdminWorkspaceSummaryResponse::of),
                page = page.page,
                size = page.size,
                total = page.total,
            )
    }
}

/** `GET /admin/workspaces/{workspace_id}` 응답 — 요약 + 최근 거래 50 + 세금계산서 요청 + 최근 변환 20. */
data class AdminWorkspaceDetailResponse(
    @get:JsonProperty("summary") val summary: AdminWorkspaceSummaryResponse,
    @get:JsonProperty("transactions") val transactions: List<CreditTransactionResponse>,
    @get:JsonProperty("invoice_requests") val invoiceRequests: List<InvoiceRequestResponse>,
    @get:JsonProperty("recent_conversions") val recentConversions: List<AdminConversionItemResponse>,
) {
    companion object {
        fun of(detail: AdminWorkspaceDetail): AdminWorkspaceDetailResponse =
            AdminWorkspaceDetailResponse(
                summary = AdminWorkspaceSummaryResponse.of(detail.summary),
                transactions = detail.transactions.map(CreditTransactionResponse::of),
                invoiceRequests =
                    detail.invoiceRequests.map { row -> InvoiceRequestResponse.of(InvoiceRequestView.of(row)) },
                recentConversions = detail.recentConversions.map(AdminConversionItemResponse::of),
            )
    }
}

/** `AdminWorkspaceDetailResponse.recent_conversions` 항목 — **본문·프롬프트 없음**. */
data class AdminConversionItemResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("title") val title: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("failure_code") val failureCode: String?,
    @get:JsonProperty("created_at") val createdAt: Instant,
) {
    /** 문서 제목을 찍지 않는다 — `Workspace`와 같은 규약. */
    override fun toString(): String =
        "AdminConversionItemResponse(id=$id, title=$CONTENT_MASK, status=$status, " +
            "failureCode=$failureCode, createdAt=$createdAt)"

    companion object {
        fun of(row: AdminConversionRow): AdminConversionItemResponse =
            AdminConversionItemResponse(
                id = row.id.toString(),
                title = row.documentTitle,
                status = row.status.wireName,
                failureCode = row.failureCode,
                createdAt = row.createdAt,
            )
    }
}

/** `POST /admin/workspaces/{workspace_id}/credits` 요청 본문. */
data class AdminCreditAdjustmentRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("credits") val credits: Int,
        @param:JsonProperty("reason") val reason: String,
        // 전역 null 처리는 실패다(`JsonRequestStrictnessConfig`) — 선택 필드는 이 표식으로
        // 그 기본을 뒤집는다(`DocumentTextRequest.title`과 같은 관행).
        @param:JsonProperty("note")
        @param:JsonSetter(nulls = Nulls.SET)
        val note: String?,
    ) {
        /** 운영자 메모를 찍지 않는다(인구조사 규약, `CreditGrantArgs`와 같은 판단). */
        override fun toString(): String =
            "AdminCreditAdjustmentRequest(credits=$credits, reason=$reason, note=$CONTENT_MASK)"
    }

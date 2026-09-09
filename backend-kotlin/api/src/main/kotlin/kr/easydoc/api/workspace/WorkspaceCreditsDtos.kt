package kr.easydoc.api.workspace

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.credit.CreditAccountView
import kr.easydoc.application.credit.CreditTransactionView
import kr.easydoc.core.credit.CreditTransactionKind
import java.time.Instant

/**
 * `GET /workspaces/{workspace_id}/credits` 응답. 계약 `components/schemas/WorkspaceCreditsResponse`
 * (2.22.0, `signup_grant_skipped`는 2.29.0).
 */
data class WorkspaceCreditsResponse(
    @get:JsonProperty("workspace_id") val workspaceId: String,
    @get:JsonProperty("balance") val balance: Int,
    @get:JsonProperty("reserved") val reserved: Int,
    @get:JsonProperty("available") val available: Int,
    @get:JsonProperty("enforced") val enforced: Boolean,
    @get:JsonProperty("transactions") val transactions: List<CreditTransactionResponse>,
    @get:JsonProperty("signup_grant_skipped") val signupGrantSkipped: Boolean,
) {
    companion object {
        fun of(view: CreditAccountView): WorkspaceCreditsResponse =
            WorkspaceCreditsResponse(
                workspaceId = view.workspaceId.toString(),
                balance = view.balance,
                reserved = view.reserved,
                available = view.available,
                enforced = view.enforced,
                transactions = view.transactions.map(CreditTransactionResponse::of),
                signupGrantSkipped = view.signupGrantSkipped,
            )
    }
}

/** `WorkspaceCreditsResponse.transactions` 항목. 계약 `components/schemas/CreditTransaction`. */
data class CreditTransactionResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("kind") val kind: String,
    @get:JsonProperty("credits") val credits: Int,
    @get:JsonProperty("reason") val reason: String,
    @get:JsonProperty("note") val note: String?,
    @get:JsonProperty("document_id") val documentId: String?,
    @get:JsonProperty("created_at") val createdAt: Instant,
) {
    companion object {
        fun of(transaction: CreditTransactionView): CreditTransactionResponse =
            CreditTransactionResponse(
                id = transaction.id.toString(),
                kind = transaction.kind.wireName,
                credits = creditsOf(transaction),
                reason = transaction.reason.wireName,
                note = transaction.note,
                documentId = transaction.documentId?.toString(),
                createdAt = transaction.createdAt,
            )

        /**
         * 계약 `CreditTransaction.credits` 부호 규약(계획 §2 결정 2: "grant/release는 +,
         * reserve/consume/adjust는 방향대로") 그대로 계산한다 — [CreditTransactionView] KDoc.
         */
        private fun creditsOf(transaction: CreditTransactionView): Int =
            when (transaction.kind) {
                CreditTransactionKind.RESERVE, CreditTransactionKind.RELEASE -> {
                    -transaction.reservedDelta
                }

                CreditTransactionKind.CONSUME, CreditTransactionKind.GRANT, CreditTransactionKind.ADJUST -> {
                    transaction.balanceDelta
                }
            }
    }
}

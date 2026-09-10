package kr.easydoc.api.workspace

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.credit.CreditAccountView
import kr.easydoc.application.credit.CreditTransactionView
import kr.easydoc.core.credit.CreditTransactionKind
import java.time.Instant

/**
 * `GET /workspaces/{workspace_id}/credits` 응답. 계약 `components/schemas/WorkspaceCreditsResponse`
 * (2.22.0, `signup_grant_skipped`는 2.29.0, `allowance`·`cycle_ends_at`는 2.30.0).
 */
data class WorkspaceCreditsResponse(
    @get:JsonProperty("workspace_id") val workspaceId: String,
    @get:JsonProperty("balance") val balance: Int,
    @get:JsonProperty("reserved") val reserved: Int,
    @get:JsonProperty("available") val available: Int,
    @get:JsonProperty("enforced") val enforced: Boolean,
    @get:JsonProperty("transactions") val transactions: List<CreditTransactionResponse>,
    @get:JsonProperty("signup_grant_skipped") val signupGrantSkipped: Boolean,
    /** 이번 주기에 제공된 이용량(계약 2.30.0). `workspace_credit_accounts.allowance`(V21). */
    @get:JsonProperty("allowance") val allowance: Int,
    /** 이번 주기가 끝나는 시각(계약 2.30.0). `null`이면 이 계정은 주기가 없다. */
    @get:JsonProperty("cycle_ends_at") val cycleEndsAt: Instant?,
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
                allowance = view.allowance,
                cycleEndsAt = view.cycleEndsAt,
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
         * [CreditTransactionKind.CYCLE_SET]·[CreditTransactionKind.CYCLE_RESET]는 잔액을
         * 직접 바꾸는 종류라 [CreditTransactionKind.ADJUST]와 같은 자리 — `balanceDelta`
         * 그대로(설정/초기화 전후 잔액 차, 음수일 수 있다).
         */
        private fun creditsOf(transaction: CreditTransactionView): Int =
            when (transaction.kind) {
                CreditTransactionKind.RESERVE, CreditTransactionKind.RELEASE -> {
                    -transaction.reservedDelta
                }

                CreditTransactionKind.CONSUME,
                CreditTransactionKind.GRANT,
                CreditTransactionKind.ADJUST,
                CreditTransactionKind.CYCLE_SET,
                CreditTransactionKind.CYCLE_RESET,
                -> {
                    transaction.balanceDelta
                }
            }
    }
}

package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.admin.AdminCreditLedgerItem
import kr.easydoc.application.admin.AdminCurrentCredits
import kr.easydoc.application.admin.AdminMonthlyCompleteness
import kr.easydoc.application.admin.AdminMonthlyCredits
import kr.easydoc.application.admin.AdminMonthlyHistory
import kr.easydoc.application.admin.AdminMonthlyPayments
import kr.easydoc.application.admin.AdminMonthlySummary
import kr.easydoc.application.admin.AdminMonthlyUsage
import kr.easydoc.application.admin.AdminPaymentEventItem
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AdminCurrentCreditsResponse(
    @get:JsonProperty("balance") val balance: BigDecimal,
    @get:JsonProperty("reserved") val reserved: BigDecimal,
    @get:JsonProperty("available") val available: BigDecimal,
    @get:JsonProperty("allowance") val allowance: BigDecimal,
    @get:JsonProperty("cycle_started_at") val cycleStartedAt: Instant?,
    @get:JsonProperty("cycle_ends_at") val cycleEndsAt: Instant?,
    @get:JsonProperty("subscription_status") val subscriptionStatus: String?,
    @get:JsonProperty("as_of") val asOf: Instant,
    @get:JsonProperty("revision") val revision: Long,
) {
    override fun toString(): String = "AdminCurrentCreditsResponse(redacted)"

    companion object {
        fun of(value: AdminCurrentCredits): AdminCurrentCreditsResponse =
            AdminCurrentCreditsResponse(
                balance = value.balance,
                reserved = value.reserved,
                available = value.available,
                allowance = value.allowance,
                cycleStartedAt = value.cycleStartedAt,
                cycleEndsAt = value.cycleEndsAt,
                subscriptionStatus = value.subscriptionStatus,
                asOf = value.asOf,
                revision = value.revision,
            )
    }
}

data class AdminMonthlyCreditsResponse(
    @get:JsonProperty("opening") val opening: BigDecimal,
    @get:JsonProperty("granted") val granted: BigDecimal,
    @get:JsonProperty("consumed") val consumed: BigDecimal,
    @get:JsonProperty("expired") val expired: BigDecimal,
    @get:JsonProperty("adjustment") val adjustment: BigDecimal,
    @get:JsonProperty("cycle_net") val cycleNet: BigDecimal,
    @get:JsonProperty("closing") val closing: BigDecimal,
    @get:JsonProperty("reserved") val reserved: BigDecimal,
    @get:JsonProperty("available") val available: BigDecimal,
    @get:JsonProperty("legacy_cycle_count") val legacyCycleCount: Int,
) {
    override fun toString(): String = "AdminMonthlyCreditsResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlyCredits): AdminMonthlyCreditsResponse =
            AdminMonthlyCreditsResponse(
                opening = value.opening,
                granted = value.granted,
                consumed = value.consumed,
                expired = value.expired,
                adjustment = value.adjustment,
                cycleNet = value.cycleNet,
                closing = value.closing,
                reserved = value.reserved,
                available = value.available,
                legacyCycleCount = value.legacyCycleCount,
            )
    }
}

data class AdminMonthlyUsageResponse(
    @get:JsonProperty("documents") val documents: Int,
    @get:JsonProperty("input_tokens") val inputTokens: Long,
    @get:JsonProperty("output_tokens") val outputTokens: Long,
    @get:JsonProperty("known_cost_usd") val knownCostUsd: String?,
    @get:JsonProperty("unknown_cost_calls") val unknownCostCalls: Int,
    @get:JsonProperty("credits_by_reason") val creditsByReason: Map<String, BigDecimal>,
    @get:JsonProperty("estimated_legacy_credits") val estimatedLegacyCredits: BigDecimal,
) {
    override fun toString(): String = "AdminMonthlyUsageResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlyUsage): AdminMonthlyUsageResponse =
            AdminMonthlyUsageResponse(
                documents = value.documents,
                inputTokens = value.inputTokens,
                outputTokens = value.outputTokens,
                knownCostUsd = value.knownCostUsd?.toPlainString(),
                unknownCostCalls = value.unknownCostCalls,
                creditsByReason = value.creditsByReason,
                estimatedLegacyCredits = value.estimatedLegacyCredits,
            )
    }
}

data class AdminMonthlyPaymentsResponse(
    @get:JsonProperty("paid_krw") val paidKrw: Long,
    @get:JsonProperty("refunded_krw") val refundedKrw: Long,
    @get:JsonProperty("net_krw") val netKrw: Long,
    @get:JsonProperty("test_paid_krw") val testPaidKrw: Long,
    @get:JsonProperty("test_refunded_krw") val testRefundedKrw: Long,
    @get:JsonProperty("unknown_date_refund_krw") val unknownDateRefundKrw: Long,
    @get:JsonProperty("unknown_date_paid_krw") val unknownDatePaidKrw: Long,
    @get:JsonProperty("test_unknown_date_paid_krw") val testUnknownDatePaidKrw: Long,
    @get:JsonProperty("test_unknown_date_refund_krw") val testUnknownDateRefundKrw: Long,
) {
    override fun toString(): String = "AdminMonthlyPaymentsResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlyPayments): AdminMonthlyPaymentsResponse =
            AdminMonthlyPaymentsResponse(
                paidKrw = value.paidKrw,
                refundedKrw = value.refundedKrw,
                netKrw = value.netKrw,
                testPaidKrw = value.testPaidKrw,
                testRefundedKrw = value.testRefundedKrw,
                unknownDateRefundKrw = value.unknownDateRefundKrw,
                unknownDatePaidKrw = value.unknownDatePaidKrw,
                testUnknownDatePaidKrw = value.testUnknownDatePaidKrw,
                testUnknownDateRefundKrw = value.testUnknownDateRefundKrw,
            )
    }
}

data class AdminMonthlyCompletenessResponse(
    @get:JsonProperty("ledger_matches_account") val ledgerMatchesAccount: Boolean,
    @get:JsonProperty("monthly_equation_matches") val monthlyEquationMatches: Boolean,
    @get:JsonProperty("warnings") val warnings: List<String>,
) {
    override fun toString(): String = "AdminMonthlyCompletenessResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlyCompleteness): AdminMonthlyCompletenessResponse =
            AdminMonthlyCompletenessResponse(
                ledgerMatchesAccount = value.ledgerMatchesAccount,
                monthlyEquationMatches = value.monthlyEquationMatches,
                warnings = value.warnings,
            )
    }
}

data class AdminMonthlySummaryResponse(
    @get:JsonProperty("workspace_id") val workspaceId: UUID,
    @get:JsonProperty("month") val month: String,
    @get:JsonProperty("timezone") val timezone: String,
    @get:JsonProperty("is_current_month") val isCurrentMonth: Boolean,
    @get:JsonProperty("current") val current: AdminCurrentCreditsResponse,
    @get:JsonProperty("credits") val credits: AdminMonthlyCreditsResponse,
    @get:JsonProperty("usage") val usage: AdminMonthlyUsageResponse,
    @get:JsonProperty("payments") val payments: AdminMonthlyPaymentsResponse,
    @get:JsonProperty("completeness") val completeness: AdminMonthlyCompletenessResponse,
) {
    override fun toString(): String = "AdminMonthlySummaryResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlySummary): AdminMonthlySummaryResponse =
            AdminMonthlySummaryResponse(
                workspaceId = value.workspaceId,
                month = value.month,
                timezone = value.timezone,
                isCurrentMonth = value.isCurrentMonth,
                current = AdminCurrentCreditsResponse.of(value.current),
                credits = AdminMonthlyCreditsResponse.of(value.credits),
                usage = AdminMonthlyUsageResponse.of(value.usage),
                payments = AdminMonthlyPaymentsResponse.of(value.payments),
                completeness = AdminMonthlyCompletenessResponse.of(value.completeness),
            )
    }
}

data class AdminCreditLedgerItemResponse(
    @get:JsonProperty("id") val id: UUID,
    @get:JsonProperty("kind") val kind: String,
    @get:JsonProperty("reason") val reason: String,
    @get:JsonProperty("balance_delta") val balanceDelta: BigDecimal,
    @get:JsonProperty("reserved_delta") val reservedDelta: BigDecimal,
    @get:JsonProperty("note") val note: String?,
    @get:JsonProperty("actor_user_id") val actorUserId: UUID?,
    @get:JsonProperty("payment_id") val paymentId: UUID?,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("granted_amount") val grantedAmount: BigDecimal?,
    @get:JsonProperty("expired_amount") val expiredAmount: BigDecimal?,
    @get:JsonProperty("adjustment_amount") val adjustmentAmount: BigDecimal?,
    @get:JsonProperty("cycle_started_at_before") val cycleStartedAtBefore: Instant?,
    @get:JsonProperty("cycle_ends_at_before") val cycleEndsAtBefore: Instant?,
    @get:JsonProperty("cycle_started_at_after") val cycleStartedAtAfter: Instant?,
    @get:JsonProperty("cycle_ends_at_after") val cycleEndsAtAfter: Instant?,
) {
    override fun toString(): String = "AdminCreditLedgerItemResponse(redacted)"

    companion object {
        fun of(value: AdminCreditLedgerItem): AdminCreditLedgerItemResponse =
            AdminCreditLedgerItemResponse(
                id = value.id,
                kind = value.kind,
                reason = value.reason,
                balanceDelta = value.balanceDelta,
                reservedDelta = value.reservedDelta,
                note = value.note,
                actorUserId = value.actorUserId,
                paymentId = value.paymentId,
                createdAt = value.createdAt,
                grantedAmount = value.grantedAmount,
                expiredAmount = value.expiredAmount,
                adjustmentAmount = value.adjustmentAmount,
                cycleStartedAtBefore = value.cycleStartedAtBefore,
                cycleEndsAtBefore = value.cycleEndsAtBefore,
                cycleStartedAtAfter = value.cycleStartedAtAfter,
                cycleEndsAtAfter = value.cycleEndsAtAfter,
            )
    }
}

data class AdminPaymentEventItemResponse(
    @get:JsonProperty("id") val id: UUID,
    @get:JsonProperty("payment_id") val paymentId: UUID,
    @get:JsonProperty("kind") val kind: String,
    @get:JsonProperty("amount_krw") val amountKrw: Long,
    @get:JsonProperty("is_test") val isTest: Boolean,
    @get:JsonProperty("occurred_at") val occurredAt: Instant?,
    @get:JsonProperty("original_created_at") val originalCreatedAt: Instant,
    @get:JsonProperty("plan_id") val planId: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("credit_transaction_id") val creditTransactionId: UUID?,
    @get:JsonProperty("original_amount_krw") val originalAmountKrw: Long,
    @get:JsonProperty("refunded_amount_krw") val refundedAmountKrw: Long,
    @get:JsonProperty("provider") val provider: String,
) {
    override fun toString(): String = "AdminPaymentEventItemResponse(redacted)"

    companion object {
        fun of(value: AdminPaymentEventItem): AdminPaymentEventItemResponse =
            AdminPaymentEventItemResponse(
                id = value.id,
                paymentId = value.paymentId,
                kind = value.kind,
                amountKrw = value.amountKrw,
                isTest = value.isTest,
                occurredAt = value.occurredAt,
                originalCreatedAt = value.originalCreatedAt,
                planId = value.planId,
                status = value.status,
                creditTransactionId = value.creditTransactionId,
                originalAmountKrw = value.originalAmountKrw,
                refundedAmountKrw = value.refundedAmountKrw,
                provider = value.provider,
            )
    }
}

data class AdminMonthlyHistoryResponse(
    @get:JsonProperty("workspace_id") val workspaceId: UUID,
    @get:JsonProperty("year") val year: Int,
    @get:JsonProperty("timezone") val timezone: String,
    @get:JsonProperty("items") val items: List<AdminMonthlySummaryResponse>,
) {
    override fun toString(): String = "AdminMonthlyHistoryResponse(redacted)"

    companion object {
        fun of(value: AdminMonthlyHistory): AdminMonthlyHistoryResponse =
            AdminMonthlyHistoryResponse(
                workspaceId = value.workspaceId,
                year = value.year,
                timezone = value.timezone,
                items = value.items.map(AdminMonthlySummaryResponse::of),
            )
    }
}

data class AdminCreditLedgerPageResponse(
    val items: List<AdminCreditLedgerItemResponse>,
    val page: Int,
    val size: Int,
    val total: Int,
)

data class AdminPaymentEventPageResponse(
    val items: List<AdminPaymentEventItemResponse>,
    val page: Int,
    val size: Int,
    val total: Int,
)

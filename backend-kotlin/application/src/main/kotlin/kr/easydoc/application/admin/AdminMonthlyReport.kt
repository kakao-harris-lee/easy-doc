package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.InvalidInputException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class AdminMonthPeriod(
    val month: String,
    val from: Instant,
    val until: Instant,
    val current: Boolean,
)

class AdminMonthResolver(
    private val zone: ZoneId,
    private val clock: Clock,
) {
    fun resolve(raw: String?): AdminMonthPeriod {
        val now = YearMonth.now(clock.withZone(zone))
        val month =
            try {
                if (raw == null) {
                    now
                } else {
                    require(Regex("[0-9]{4}-[0-9]{2}").matches(raw)) { "Invalid month format" }
                    YearMonth.parse(raw)
                }
            } catch (_: RuntimeException) {
                throw InvalidInputException("조회 월은 YYYY-MM 형식이어야 합니다")
            }
        if (month.year < 1 || month > now) throw InvalidInputException("미래 월은 조회할 수 없습니다")
        return AdminMonthPeriod(
            month.toString(),
            month.atDay(1).atStartOfDay(zone).toInstant(),
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zone)
                .toInstant(),
            month == now,
        )
    }

    fun months(year: Int): List<AdminMonthPeriod> {
        val now = YearMonth.now(clock.withZone(zone))
        if (year < 1 || year > now.year) throw InvalidInputException("조회 연도가 올바르지 않습니다")
        val lastMonth = if (year == now.year) now.monthValue else java.time.Month.DECEMBER.value
        return (1..lastMonth).map { resolve("%04d-%02d".format(year, it)) }
    }
}

data class AdminCurrentCredits(
    val balance: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val allowance: BigDecimal,
    val cycleStartedAt: Instant?,
    val cycleEndsAt: Instant?,
    val subscriptionStatus: String?,
    val asOf: Instant,
    val revision: Long,
)

data class AdminMonthlyCredits(
    val opening: BigDecimal,
    val granted: BigDecimal,
    val consumed: BigDecimal,
    val expired: BigDecimal,
    val adjustment: BigDecimal,
    val cycleNet: BigDecimal,
    val closing: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
    val legacyCycleCount: Int,
)

data class AdminMonthlyUsage(
    val documents: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val knownCostUsd: BigDecimal?,
    val unknownCostCalls: Int,
    val creditsByReason: Map<String, BigDecimal>,
    val estimatedLegacyCredits: BigDecimal,
)

data class AdminMonthlyPayments(
    val paidKrw: Long,
    val refundedKrw: Long,
    val netKrw: Long,
    val testPaidKrw: Long,
    val testRefundedKrw: Long,
    val unknownDateRefundKrw: Long,
    val unknownDatePaidKrw: Long,
    val testUnknownDatePaidKrw: Long,
    val testUnknownDateRefundKrw: Long,
)

data class AdminMonthlyCompleteness(
    val ledgerMatchesAccount: Boolean,
    val monthlyEquationMatches: Boolean,
    val warnings: List<String>,
)

data class AdminMonthlySummary(
    val workspaceId: UUID,
    val month: String,
    val timezone: String,
    val isCurrentMonth: Boolean,
    val current: AdminCurrentCredits,
    val credits: AdminMonthlyCredits,
    val usage: AdminMonthlyUsage,
    val payments: AdminMonthlyPayments,
    val completeness: AdminMonthlyCompleteness,
)

data class AdminCreditLedgerItem(
    val id: UUID,
    val kind: String,
    val reason: String,
    val balanceDelta: BigDecimal,
    val reservedDelta: BigDecimal,
    val note: String?,
    val actorUserId: UUID?,
    val paymentId: UUID?,
    val createdAt: Instant,
    val grantedAmount: BigDecimal?,
    val expiredAmount: BigDecimal?,
    val adjustmentAmount: BigDecimal?,
    val cycleStartedAtBefore: Instant?,
    val cycleEndsAtBefore: Instant?,
    val cycleStartedAtAfter: Instant?,
    val cycleEndsAtAfter: Instant?,
) {
    override fun toString(): String = "AdminCreditLedgerItem(id=$id, note=***)"
}

data class AdminPaymentEventItem(
    val id: UUID,
    val paymentId: UUID,
    val kind: String,
    val amountKrw: Long,
    val isTest: Boolean,
    val occurredAt: Instant?,
    val originalCreatedAt: Instant,
    val planId: String,
    val status: String,
    val creditTransactionId: UUID?,
    val originalAmountKrw: Long,
    val refundedAmountKrw: Long,
    val provider: String,
)

data class AdminCreditLedgerPage(
    val items: List<AdminCreditLedgerItem>,
    val page: Int,
    val size: Int,
    val total: Int,
)

data class AdminPaymentEventPage(
    val items: List<AdminPaymentEventItem>,
    val page: Int,
    val size: Int,
    val total: Int,
)

data class AdminMonthlyHistory(
    val workspaceId: UUID,
    val year: Int,
    val timezone: String,
    val items: List<AdminMonthlySummary>,
)

interface AdminMonthlyReportRepository {
    fun summary(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        timezone: String,
        now: Instant,
    ): AdminMonthlySummary

    fun transactions(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        kind: String?,
        page: Int,
        size: Int,
    ): AdminCreditLedgerPage

    fun payments(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        page: Int,
        size: Int,
    ): AdminPaymentEventPage
}

private const val MAX_PAGE = 100000
private const val MAX_SIZE = 100

class AdminMonthlyReportService(
    private val repository: AdminMonthlyReportRepository,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    private val resolver = AdminMonthResolver(zone, clock)

    fun summary(
        id: UUID,
        month: String?,
    ): AdminMonthlySummary = repository.summary(id, resolver.resolve(month), zone.id, clock.instant())

    fun history(
        id: UUID,
        year: Int,
    ): AdminMonthlyHistory =
        AdminMonthlyHistory(
            id,
            year,
            zone.id,
            resolver.months(year).map { repository.summary(id, it, zone.id, clock.instant()) },
        )

    fun transactions(
        id: UUID,
        month: String?,
        kind: String?,
        page: Int,
        size: Int,
    ): AdminCreditLedgerPage {
        validatePage(page, size)
        if (kind != null &&
            kind !in setOf("grant", "reserve", "consume", "release", "adjust", "cycle_set", "cycle_reset")
        ) {
            throw InvalidInputException("거래 종류가 올바르지 않습니다")
        }
        return repository.transactions(id, resolver.resolve(month), kind, page, size)
    }

    fun payments(
        id: UUID,
        month: String?,
        page: Int,
        size: Int,
    ): AdminPaymentEventPage {
        validatePage(page, size)
        return repository.payments(id, resolver.resolve(month), page, size)
    }

    private fun validatePage(
        page: Int,
        size: Int,
    ) {
        if (page !in 1..MAX_PAGE || size !in 1..MAX_SIZE) throw InvalidInputException("페이지 범위가 올바르지 않습니다")
    }
}

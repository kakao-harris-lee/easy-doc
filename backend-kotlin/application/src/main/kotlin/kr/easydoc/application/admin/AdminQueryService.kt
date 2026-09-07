package kr.easydoc.application.admin

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.CreditAccountView
import kr.easydoc.application.credit.CreditTransactionView
import kr.easydoc.application.invoice.InvoiceRequestRepository
import kr.easydoc.application.invoice.InvoiceRequestRow
import kr.easydoc.application.usage.UsagePeriodResolver
import kr.easydoc.application.usage.UsageQueryService
import kr.easydoc.application.usage.UsageReportRows
import kr.easydoc.application.usage.UsageReportService
import kr.easydoc.application.usage.WorkspaceUsage
import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.privacy.CONTENT_MASK
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** `GET /admin/workspaces`·`GET /admin/workspaces/{workspace_id}` 목록·상세 한 줄. */
data class AdminWorkspaceSummary(
    val workspaceId: UUID,
    val name: String,
    val ownerEmail: String,
    val createdAt: Instant,
    val creditBalance: Int,
    val creditReserved: Int,
    val creditAvailable: Int,
    val monthDocuments: Int,
    val monthCredits: Long,
    val monthCostUsd: BigDecimal?,
) {
    /** 이름·이메일을 찍지 않는다 — `Workspace`·`User`와 같은 규약. */
    override fun toString(): String =
        "AdminWorkspaceSummary(workspaceId=$workspaceId, name=$CONTENT_MASK, ownerEmail=$CONTENT_MASK, " +
            "createdAt=$createdAt, creditBalance=$creditBalance, creditReserved=$creditReserved, " +
            "creditAvailable=$creditAvailable, monthDocuments=$monthDocuments, monthCredits=$monthCredits, " +
            "monthCostUsd=$monthCostUsd)"
}

/** `GET /admin/workspaces` 응답 — 페이지 하나. */
data class AdminWorkspaceListPage(
    val items: List<AdminWorkspaceSummary>,
    val page: Int,
    val size: Int,
    val total: Int,
)

/** `GET /admin/workspaces/{workspace_id}` 응답 — 기본 정보 + 거래 50 + 세금계산서 요청 + 최근 변환 20. */
data class AdminWorkspaceDetail(
    val summary: AdminWorkspaceSummary,
    val transactions: List<CreditTransactionView>,
    val invoiceRequests: List<InvoiceRequestRow>,
    val recentConversions: List<AdminConversionRow>,
)

/** `GET /admin/errors` 응답 — 코드별 건수 + 최근 목록. */
data class AdminErrorsView(
    val counts: List<AdminFailureCount>,
    val recent: List<AdminErrorRow>,
)

/**
 * 관리자 조회 유스케이스 — 어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md` §2
 * 결정 4. **기존 서비스를 조합한다**(계획 §3 A1) — 크레딧·사용량은 그 워크스페이스의
 * 실제 소유자([AdminWorkspaceRow.ownerId])를 그대로 넘겨 [CreditAccountService.read]·
 * [UsageQueryService.usageOf]의 소유 판정을 자연스럽게 통과시킨다(관리자용으로 별도
 * 소유 우회 조회를 새로 만들지 않는다). 새로 만든 것은 워크스페이스 검색·최근 변환·오류
 * 집계 셋뿐이다 — 그중 최근 변환·오류 집계만 `documents`·`conversions`에 닿는다
 * (관리자 전용 소유 우회, `AdminConversionQueryRepository` KDoc).
 */
@Suppress("LongParameterList")
class AdminQueryService(
    private val workspaces: AdminWorkspaceQueryRepository,
    private val creditAccounts: CreditAccountService,
    private val usage: UsageQueryService,
    private val invoiceRequests: InvoiceRequestRepository,
    private val conversions: AdminConversionQueryRepository,
    private val usageReport: UsageReportService,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    /**
     * 페이지의 워크스페이스 id 전부를 모아 크레딧·사용량을 **한 번씩만** 배치로 읽는다
     * (독립 리뷰 지적 — 이전에는 항목마다 두 질의를 더 불렀다). 계정 행이 없는
     * 워크스페이스는 0/0/0으로 그린다 — 목록은 관용이다(상세는 여전히 엄격하게 던진다).
     */
    fun listWorkspaces(
        rawQuery: String?,
        page: Int,
        size: Int,
    ): AdminWorkspaceListPage {
        val query = rawQuery?.trim()?.ifEmpty { null }
        val result = workspaces.search(query, page, size)
        val workspaceIds = result.items.map { it.workspaceId }
        val balances = workspaces.creditBalances(workspaceIds)
        val period = resolvePeriod(rawFrom = null, rawTo = null)
        val monthUsage = workspaces.monthUsage(workspaceIds, period.first, period.second)
        val items =
            result.items.map { row -> listSummaryOf(row, balances[row.workspaceId], monthUsage[row.workspaceId]) }
        return AdminWorkspaceListPage(items, page, size, result.total)
    }

    /** 없으면 [NotFoundException] — 관리자 전용이라 존재 은닉이 아니라 진짜 404다. */
    fun workspaceDetail(workspaceId: UUID): AdminWorkspaceDetail {
        val row = workspaces.find(workspaceId) ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
        val account = creditAccounts.read(row.ownerId, workspaceId)
        val monthUsage = usage.usageOf(row.ownerId, workspaceId, from = null, to = null)
        val invoices = invoiceRequests.listForOwner(row.ownerId, workspaceId, INVOICE_LIMIT).orEmpty()
        val recentConversions = conversions.recentForWorkspace(workspaceId, RECENT_CONVERSIONS_LIMIT)
        return AdminWorkspaceDetail(
            summary = detailSummaryOf(row, account, monthUsage),
            transactions = account.transactions,
            invoiceRequests = invoices,
            recentConversions = recentConversions,
        )
    }

    /** 기본 기간은 [UsageQueryService]와 같다 — 이번 달 1일~오늘. */
    fun errors(
        from: String?,
        to: String?,
    ): AdminErrorsView {
        val period = resolvePeriod(from, to)
        val counts = conversions.failureCounts(period.first, period.second)
        val recent = conversions.recentFailures(period.first, period.second, RECENT_ERRORS_LIMIT)
        return AdminErrorsView(counts, recent)
    }

    /** `UsageReportService`가 CSV로 굳히기 전의 행 그대로 — `GET /admin/usage`가 JSON으로 낸다. */
    fun usageRows(
        from: String?,
        to: String?,
    ): UsageReportRows = usageReport.rows(from, to)

    /** 목록 요약 — 배치 조회 결과를 합성한다. 계정·사용량 행이 없으면 0/0/0(목록은 관용). */
    private fun listSummaryOf(
        row: AdminWorkspaceRow,
        balance: AdminCreditBalance?,
        monthUsage: AdminMonthUsage?,
    ): AdminWorkspaceSummary =
        AdminWorkspaceSummary(
            workspaceId = row.workspaceId,
            name = row.name,
            ownerEmail = row.ownerEmail,
            createdAt = row.createdAt,
            creditBalance = balance?.balance ?: 0,
            creditReserved = balance?.reserved ?: 0,
            creditAvailable = (balance?.balance ?: 0) - (balance?.reserved ?: 0),
            monthDocuments = monthUsage?.documents ?: 0,
            monthCredits = monthUsage?.credits ?: 0,
            monthCostUsd = monthUsage?.estimatedCostUsd,
        )

    /** 상세 요약 — 워크스페이스 하나뿐이라 기존 서비스(`CreditAccountService`·`UsageQueryService`)를 그대로 쓴다. */
    private fun detailSummaryOf(
        row: AdminWorkspaceRow,
        account: CreditAccountView,
        monthUsage: WorkspaceUsage,
    ): AdminWorkspaceSummary =
        AdminWorkspaceSummary(
            workspaceId = row.workspaceId,
            name = row.name,
            ownerEmail = row.ownerEmail,
            createdAt = row.createdAt,
            creditBalance = account.balance,
            creditReserved = account.reserved,
            creditAvailable = account.available,
            monthDocuments = monthUsage.documents,
            monthCredits = monthUsage.credits,
            monthCostUsd = monthUsage.estimatedCostUsd,
        )

    /** `[from, toExclusive)`. 날짜 파싱·범위 검증은 U2·U3와 같은 [UsagePeriodResolver]가 한다. */
    private fun resolvePeriod(
        rawFrom: String?,
        rawTo: String?,
    ): Pair<Instant, Instant> {
        val today = LocalDate.now(clock.withZone(zone))
        val fromDate = rawFrom?.let(UsagePeriodResolver::parseDate) ?: today.withDayOfMonth(1)
        val toDate = rawTo?.let(UsagePeriodResolver::parseDate) ?: today
        val period = UsagePeriodResolver.resolve(fromDate, toDate, zone)
        return period.fromInstant to period.toExclusiveInstant
    }

    private companion object {
        /** 계획 §2 결정 4 — 최근 세금계산서 요청. `InvoiceRequestService`의 사용자용 상한과 같다. */
        const val INVOICE_LIMIT = 50
        const val RECENT_CONVERSIONS_LIMIT = 20
        const val RECENT_ERRORS_LIMIT = 50
    }
}

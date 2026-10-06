package kr.easydoc.api.admin

import jakarta.validation.constraints.Pattern
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AdminMonthlyReportService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/admin/workspaces/{workspace_id}")
class AdminMonthlyReportController(private val reports: AdminMonthlyReportService) {
    @GetMapping("/monthly-summary")
    fun summary(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") id: UUID,
        @RequestParam(name = "month", required = false) month: String?,
    ) = adminResponse(HttpStatus.OK).body(AdminMonthlySummaryResponse.of(reports.summary(id, month))).also {
        AdminActionLog.record("workspace_monthly_summary", user.id, id)
    }

    @GetMapping("/monthly-history")
    fun history(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") id: UUID,
        @RequestParam(name = "year") year: Int,
    ) = adminResponse(HttpStatus.OK).body(AdminMonthlyHistoryResponse.of(reports.history(id, year))).also {
        AdminActionLog.record("workspace_monthly_history", user.id, id)
    }

    @Suppress("LongParameterList")
    @GetMapping("/credit-transactions")
    fun transactions(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") id: UUID,
        @RequestParam(name = "month", required = false) month: String?,
        @RequestParam(name = "kind", required = false)
        @Pattern(regexp = "grant|reserve|consume|release|adjust|cycle_set|cycle_reset")
        kind: String?,
        @RequestParam(name = "page", defaultValue = "1") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ) = reports.transactions(id, month, kind, page, size).let {
        AdminActionLog.record("workspace_credit_transactions", user.id, id)
        adminResponse(
            HttpStatus.OK,
        ).body(
            AdminCreditLedgerPageResponse(it.items.map(AdminCreditLedgerItemResponse::of), it.page, it.size, it.total),
        )
    }

    @GetMapping("/payments")
    fun payments(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") id: UUID,
        @RequestParam(name = "month", required = false) month: String?,
        @RequestParam(name = "page", defaultValue = "1") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ) = reports.payments(id, month, page, size).let {
        AdminActionLog.record("workspace_payment_events", user.id, id)
        adminResponse(
            HttpStatus.OK,
        ).body(
            AdminPaymentEventPageResponse(it.items.map(AdminPaymentEventItemResponse::of), it.page, it.size, it.total),
        )
    }
}

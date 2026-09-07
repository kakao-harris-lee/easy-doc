package kr.easydoc.api.admin

import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AdminQueryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /admin/usage` — `usage-report`(U3) 프로필의 행을 JSON으로(워크스페이스별). CSV는
 * 계속 그 프로필이 맡는다(어드민 최소 계획 §2 결정 4).
 */
@RestController
@RequestMapping("/admin/usage")
class AdminUsageController(private val queryService: AdminQueryService) {
    @GetMapping
    fun usage(
        user: AuthenticatedUser,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
    ): ResponseEntity<AdminUsageResponse> {
        val rows = queryService.usageRows(from, to)
        AdminActionLog.record(ACTION_USAGE, user.id)
        return adminResponse(HttpStatus.OK).body(AdminUsageResponse.of(rows))
    }

    private companion object {
        const val ACTION_USAGE = "usage_view"
    }
}

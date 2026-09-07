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
 * `GET /admin/errors` — 기간 내 `failed` 변환을 `failure_code`별 건수 + 최근 50건으로.
 * 어드민 최소 계획 §2 결정 4. **본문·프롬프트는 어디에도 없다.** `from`·`to` 생략 시
 * 이번 달 1일~오늘([kr.easydoc.application.usage.UsageQueryService]와 같은 기본값).
 */
@RestController
@RequestMapping("/admin/errors")
class AdminErrorController(private val queryService: AdminQueryService) {
    @GetMapping
    fun errors(
        user: AuthenticatedUser,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
    ): ResponseEntity<AdminErrorsResponse> {
        val view = queryService.errors(from, to)
        AdminActionLog.record(ACTION_ERRORS, user.id)
        return adminResponse(HttpStatus.OK).body(AdminErrorsResponse.of(view))
    }

    private companion object {
        const val ACTION_ERRORS = "errors_view"
    }
}

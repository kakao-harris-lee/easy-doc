package kr.easydoc.api.admin

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.api.workspace.WorkspaceCreditsResponse
import kr.easydoc.application.admin.AdminCreditAdjustmentService
import kr.easydoc.application.admin.AdminQueryService
import kr.easydoc.application.admin.requireAdminCreditReason
import kr.easydoc.application.admin.requireNonZeroCredits
import kr.easydoc.application.admin.requireValidAdminCreditNote
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET /admin/workspaces`·`GET /admin/workspaces/{workspace_id}`·
 * `POST /admin/workspaces/{workspace_id}/credits` — 어드민 최소 계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 4 A1. `x-admin-only: true`(계약
 * 2.25.0) — [kr.easydoc.api.admin.AdminAccessInterceptor]가 걸린다.
 */
@RestController
@RequestMapping("/admin/workspaces")
class AdminWorkspaceController(
    private val queryService: AdminQueryService,
    private val creditAdjustmentService: AdminCreditAdjustmentService,
) {
    /** `q`는 이름·소유자 이메일 부분 일치. `page` 1~100000, `size` 1~100(기본 20). */
    @GetMapping
    fun list(
        user: AuthenticatedUser,
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(name = "page", defaultValue = "1")
        @Min(1)
        @Max(MAX_PAGE)
        page: Int,
        @RequestParam(name = "size", defaultValue = "20")
        @Min(1)
        @Max(100)
        size: Int,
    ): ResponseEntity<AdminWorkspaceListResponse> {
        AdminActionLog.record(ACTION_WORKSPACE_LIST, user.id)
        val result = queryService.listWorkspaces(q, page, size)
        return adminResponse(HttpStatus.OK).body(AdminWorkspaceListResponse.of(result))
    }

    /** 없으면 404(관리자 전용이라 존재 은닉이 아니라 진짜 404). */
    @GetMapping("/{workspace_id}")
    fun detail(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
    ): ResponseEntity<AdminWorkspaceDetailResponse> {
        val detail = queryService.workspaceDetail(workspaceId)
        AdminActionLog.record(ACTION_WORKSPACE_DETAIL, user.id, workspaceId)
        return adminResponse(HttpStatus.OK).body(AdminWorkspaceDetailResponse.of(detail))
    }

    /**
     * 크레딧 수동 조정 — `credit-grant` CLI(C2)와 같은 경로를 재사용하고
     * `actor_user_id`(V17)에 요청한 관리자 id를 남긴다(어드민 최소 계획 §2 결정 3).
     */
    @PostMapping("/{workspace_id}/credits", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun adjustCredits(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
        @RequestBody request: AdminCreditAdjustmentRequest,
    ): ResponseEntity<WorkspaceCreditsResponse> {
        val credits = requireNonZeroCredits(request.credits)
        val reason = requireAdminCreditReason(request.reason)
        val note = requireValidAdminCreditNote(request.note)
        val view = creditAdjustmentService.adjust(workspaceId, credits, reason, note, user.id)
        AdminActionLog.record(ACTION_CREDIT_ADJUST, user.id, workspaceId)
        return adminResponse(HttpStatus.OK).body(WorkspaceCreditsResponse.of(view))
    }

    private companion object {
        /** 계약 `listAdminWorkspaces.parameters[page].schema.maximum`과 같은 값. */
        const val MAX_PAGE = 100000L
        const val ACTION_WORKSPACE_LIST = "workspace_list"
        const val ACTION_WORKSPACE_DETAIL = "workspace_detail"
        const val ACTION_CREDIT_ADJUST = "credit_adjust"
    }
}

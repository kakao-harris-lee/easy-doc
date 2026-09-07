package kr.easydoc.api.workspace

import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.usage.UsageQueryService
import kr.easydoc.application.workspace.WorkspaceService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET·POST /workspaces` · `PATCH·DELETE /workspaces/{workspace_id}` · `GET .../usage`(2.20.0, U2) ·
 * `GET .../credits`(2.22.0, C1).
 */
@RestController
@RequestMapping("/workspaces")
class WorkspaceController(
    private val workspaceService: WorkspaceService,
    private val usageQueryService: UsageQueryService,
    private val creditAccountService: CreditAccountService,
) {
    /** 만든 순서대로 돌려준다. **첫 번째가 기본 작업 공간이다.** */
    @GetMapping
    fun list(user: AuthenticatedUser): ResponseEntity<WorkspaceListResponse> =
        private(HttpStatus.OK).body(
            WorkspaceListResponse(workspaceService.list(user.id).map(WorkspaceListItemResponse::of)),
        )

    /** 만든다. **201** 이다 — 자원이 실제로 생겼다. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        user: AuthenticatedUser,
        @RequestBody request: WorkspaceNameRequest,
    ): ResponseEntity<WorkspaceResponse> =
        private(HttpStatus.CREATED).body(
            WorkspaceResponse.of(workspaceService.create(user.id, request.name)),
        )

    /** 이름을 바꾼다. */
    @PatchMapping("/{workspace_id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rename(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
        @RequestBody request: WorkspaceNameRequest,
    ): ResponseEntity<WorkspaceResponse> =
        private(HttpStatus.OK).body(
            WorkspaceResponse.of(workspaceService.rename(user.id, workspaceId, request.name)),
        )

    /** 빈 작업 공간을 지운다. **204 이고 본문이 없다.** */
    @DeleteMapping("/{workspace_id}")
    fun delete(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
    ): ResponseEntity<Void> {
        workspaceService.delete(user.id, workspaceId)
        return ResponseEntity.noContent().build()
    }

    /**
     * `GET /workspaces/{workspace_id}/usage` — 기간별 사용량 집계(2.20.0, U2).
     * `from`·`to`를 여기서 파싱하지 않는다 — 형식·범위 검증은 [UsageQueryService]가 한다
     * (스키마 제약이 아니라 서비스 층 규칙이라 문자열 detail 422를 내야 한다,
     * `DocumentController.createFromFile`의 `workspace_id`와 같은 이유로 원시 문자열을
     * 그대로 넘긴다).
     */
    @GetMapping("/{workspace_id}/usage")
    fun usage(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
    ): ResponseEntity<WorkspaceUsageResponse> =
        private(HttpStatus.OK).body(
            WorkspaceUsageResponse.of(usageQueryService.usageOf(user.id, workspaceId, from, to)),
        )

    /**
     * `GET /workspaces/{workspace_id}/credits` — 크레딧 계정 조회(2.22.0, C1). 없거나 내
     * 것이 아니면 [CreditAccountService.read] 가 [kr.easydoc.core.exceptions.NotFoundException]
     * 을 던진다(존재 은닉, `usage`와 같은 규약).
     */
    @GetMapping("/{workspace_id}/credits")
    fun credits(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
    ): ResponseEntity<WorkspaceCreditsResponse> =
        private(HttpStatus.OK).body(
            WorkspaceCreditsResponse.of(creditAccountService.read(user.id, workspaceId)),
        )

    /** 고위험 응답에 붙는 하한선 헤더. 값의 정본은 계약 `components/headers` 의 각 컴포넌트다. */
    private fun private(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header(CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)

    private companion object {
        const val CACHE_CONTROL = "Cache-Control"
        const val NO_STORE = "no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}

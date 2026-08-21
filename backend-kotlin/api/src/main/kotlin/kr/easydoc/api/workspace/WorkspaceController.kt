package kr.easydoc.api.workspace

import kr.easydoc.api.auth.AuthenticatedUser
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `GET·POST /workspaces` · `PATCH·DELETE /workspaces/{workspace_id}`. */
@RestController
@RequestMapping("/workspaces")
class WorkspaceController(private val workspaceService: WorkspaceService) {
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

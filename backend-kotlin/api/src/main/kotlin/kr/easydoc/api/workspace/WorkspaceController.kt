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

/**
 * `GET·POST /workspaces` · `PATCH·DELETE /workspaces/{workspace_id}`.
 *
 * ## 라우터에 비즈니스 판단이 없다
 *
 * 이름 정규화·길이 판정, 소유권 확인, 삭제 거절 두 갈래는 전부 [WorkspaceService] 가
 * 한다(`CLAUDE.md` 아키텍처 규칙 3). 여기서 하는 일은 HTTP 표현으로 바꾸는 것뿐이다.
 * 소유자 식별자도 여기서 만들지 않는다 — [AuthenticatedUser] 는 인터셉터가 토큰을
 * 검증해 넣은 값이고, 그 타입이 곧 "이 UUID 는 요청 본문이 아니라 토큰에서 왔다"는 표시다.
 *
 * ## `PUT` 이 아니라 `PATCH` 다
 *
 * 계약이 이유를 적었다 — 바꾸는 것이 이름 하나뿐이라, 전체 표현을 보내는 `PUT` 은
 * 클라이언트가 나머지 필드(만든 시각 등)까지 실어 보내야 한다는 뜻이 된다. 메서드를
 * 바꾸면 React `renameWorkspace` 가 그대로 깨진다.
 *
 * ## 캐시 금지 헤더를 `ResponseEntity` 에 직접 싣는다
 *
 * 전역 필터·밸브가 모든 응답에 같은 헤더를 붙이지만(계약 `x-global-response-headers`),
 * 계약이 고위험 10곳을 **하한선**으로 남겼고 이 컨트롤러의 셋(`GET`·`POST` `/workspaces`,
 * `PATCH /workspaces/{workspace_id}`)이 그중 셋이다 — *"작업 공간 이름도 사용자가 적은
 * 콘텐츠"* 이기 때문이다. 전역 장치가 빠지거나 체인 순서가 어긋났을 때 **여기서 먼저
 * 깨져야 한다**(리더 판정 부수 결정 1).
 *
 * 두 층이 같은 헤더를 쓰므로 **`add` 가 아니라 `set` 이어야 한다.** `ResponseEntity` 의
 * `header(...)` 는 값을 덧붙이지만 전역 필터가 `set` 을 쓰므로 최종 응답에는 하나만
 * 남는다 — 그 사실을 계약 테스트가 **개수까지** 단언한다.
 *
 * `DELETE` 의 204 에는 여기서 붙이지 않는다. 계약의 하한선 10곳에 그 자리가 없고
 * (본문이 없어 콘텐츠가 실리지 않는다), 그래도 헤더는 나간다 — 전역 부착이 요구의
 * 정본이기 때문이다. 빠뜨린 것이 아니라 **하한선 목록을 계약대로 옮긴 것**이다.
 */
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

    /**
     * 이름을 바꾼다.
     *
     * `workspaceId` 를 `UUID` 로 받는다 — 형식이 틀리면 Spring 이 변환 단계에서 422 를
     * 만들고, **그 단계는 인증 인터셉터보다 뒤다.** 즉 토큰 없이 잘못된 UUID 를 보내면
     * 422 가 아니라 401 이 나간다(계약 `info.description` 의 우선순위 절, X-A3).
     */
    @PatchMapping("/{workspace_id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rename(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspaceId: UUID,
        @RequestBody request: WorkspaceNameRequest,
    ): ResponseEntity<WorkspaceResponse> =
        private(HttpStatus.OK).body(
            WorkspaceResponse.of(workspaceService.rename(user.id, workspaceId, request.name)),
        )

    /**
     * 빈 작업 공간을 지운다. **204 이고 본문이 없다.**
     *
     * `Content-Type` 을 붙이지 않는다 — 본문이 없는 응답에 그것을 실으면 "길이 0 의 JSON"
     * 이라는 없는 표현을 선언하는 것이 된다.
     */
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

package kr.easydoc.application.workspace

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.WorkspaceDeletionState
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import java.util.UUID

/**
 * 작업 공간 유스케이스 — 목록 · 생성 · 이름 변경 · 삭제.
 *
 * 요구 정본: 계약 `contracts/easy-doc-v1.yaml` 의 `/workspaces`·`/workspaces/{workspace_id}`,
 * `x-request-field-constraints.fields[4]`, 그리고 `info.description` 의 소유권 은닉 조항.
 *
 * ## 소유권을 숨긴다 — 403 이 아니라 404 다
 *
 * 남의 작업 공간에 손대면 **없는 것과 똑같은 응답**이 나간다. 403 은 "그것은 있는데 네
 * 것이 아니다"를 알려 주므로 식별자를 훑어 남의 자원 목록을 만들 수 있게 된다.
 *
 * 숨기는 축이 셋이다 — **상태 코드·본문·응답 시간**. 앞의 둘은 같은 예외 하나를 쓰면
 * 성립하지만 셋째는 **일하는 양**이 같아야 성립한다. 그래서 소유 조건을 SQL `WHERE` 절에
 * 넣어 두 경우가 **같은 질의 한 번**을 돌게 한다 — 「먼저 읽고 나서 소유자를 비교한다」는
 * 형태는 남의 자원일 때만 행을 읽어 그 차이가 시간에 남는다. auth 의 로그인 타이밍
 * 열거(B-1)가 같은 형태였고, 그때 얻은 교훈이 이 자리의 설계다.
 *
 * ## 사용자 식별자를 응답에 싣지 않는다
 *
 * `Workspace` 는 소유자를 담지 않는다. 소유자는 조회 조건으로만 쓰인다.
 *
 * ## 로그
 *
 * **작업 공간 이름을 로그에 남기지 않는다** — 사용자가 직접 적은 문자열이고, 계약이
 * 이 필드에 사적 응답 헤더를 요구한 이유도 *"작업 공간 이름도 사용자가 적은 콘텐츠"*
 * 이기 때문이다(`x-private-response-headers.applies_to`). 이 클래스는 아무것도 로깅하지
 * 않는다 — 남길 만한 것이 ID 와 상태뿐인데 그것은 접근 로그가 이미 든다.
 */
class WorkspaceService(
    private val workspaces: WorkspaceRepository,
    private val transaction: TransactionRunner,
) {
    /** 만든 순서대로 돌려준다. **첫 번째가 기본 작업 공간이다**(계약 `GET /workspaces`). */
    fun list(ownerId: UUID): List<WorkspaceListing> = workspaces.listOwned(ownerId)

    /** 이름을 정규화·검사한 뒤 만든다. 같은 이름이 이미 있으면 저장소가 409 를 던진다. */
    fun create(
        ownerId: UUID,
        rawName: String,
    ): Workspace = workspaces.create(ownerId, validName(rawName))

    /**
     * 이름을 바꾼다.
     *
     * **이름 검사가 소유권 조회보다 먼저다.** 순서를 뒤집으면 질의가 한 번 더 들고, 무엇보다
     * 두 순서 중 어느 쪽도 소유권을 새게 하지 않는다 — 검사 결과는 요청 본문에만 의존하고
     * 대상 자원의 존재 여부와 무관하기 때문이다(잘못된 이름이면 소유 여부와 상관없이 422,
     * 올바른 이름이면 상관없이 404). 새는 순서는 그 반대, 즉 **자원을 읽어야만 알 수 있는
     * 것을 검사 결과에 섞는 것**이고 여기에는 그런 것이 없다.
     */
    fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        rawName: String,
    ): Workspace =
        workspaces.rename(ownerId, workspaceId, validName(rawName))
            ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)

    /**
     * 빈 작업 공간을 지운다.
     *
     * 판정과 삭제가 **한 트랜잭션**이다. 나누면 판정과 삭제 사이에 문서가 들어오거나 다른
     * 작업 공간이 지워져, 통과한 판정이 삭제 시점에는 거짓이 된다.
     *
     * ## 거절 두 갈래의 순서 — **마지막 하나가 먼저다**
     *
     * **계약이 정한다.** `paths./workspaces/{workspace_id}.delete.description` 이
     * *"둘 다 해당하면 2(마지막 남은 작업 공간)를 낸다"* 로 신설했다(2026-08-19, 근거 G2).
     * 구현이 먼저였고 조항이 뒤따랐지만, 이제 정본은 계약이다 — 순서를 바꾸려면 여기가
     * 아니라 계약을 고쳐야 하고, `WorkspaceEndpointReachTest` WD-9 가 그 조항을 앵커로
     * 읽어 두 자리를 묶는다.
     *
     * 조항의 근거는 이 순서가 **무조건적인 거절**을 먼저 낸다는 데 있다 — 문서를 다 비워도
     * 결과가 바뀌지 않는다. 반대로 골랐다면 사용자는 안내대로 문서를 지운 뒤 다시
     * 거절당하고, 그때는 1번 갈래가 막겠다고 적은 되돌릴 수 없는 파기가 **그 안내를
     * 따랐기 때문에** 이미 일어난 뒤다.
     */
    fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ) {
        transaction.inTransaction {
            val state =
                workspaces.lockForDeletion(ownerId, workspaceId)
                    ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)

            refusalFor(state)?.let { throw ConflictException(it) }

            // 잠금을 쥔 채 지운다. `false` 는 위 판정과 이 삭제 사이에 행이 사라졌다는
            // 뜻인데, 같은 트랜잭션에서 잠근 행이므로 일어날 수 없다 — 일어났다면 잠금
            // 전제가 깨진 것이라 조용히 성공으로 넘기지 않는다.
            check(workspaces.delete(ownerId, workspaceId)) { "잠근 작업 공간이 삭제되지 않았다" }
        }
    }

    /**
     * 삭제를 거절해야 하면 그 사유 문구, 아니면 `null`.
     *
     * **판정을 한 함수에 모은 이유**는 두 갈래의 **순서가 규칙이기 때문**이다. 호출부에
     * 흩어 놓으면 순서가 코드 배치의 부산물처럼 보이고, 다음 사람이 별생각 없이 뒤집는다.
     */
    private fun refusalFor(state: WorkspaceDeletionState): String? =
        when {
            state.ownedWorkspaceCount <= 1 -> LAST_WORKSPACE_MESSAGE
            state.documentCount > 0 -> WORKSPACE_HAS_DOCUMENTS_MESSAGE
            else -> null
        }

    private fun validName(rawName: String): String = requireValidWorkspaceName(normalizeWorkspaceName(rawName))
}

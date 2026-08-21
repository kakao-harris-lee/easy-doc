package kr.easydoc.application.workspace

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.WorkspaceDeletionState
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import java.util.UUID

/** 작업 공간 유스케이스 — 목록 · 생성 · 이름 변경 · 삭제. */
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

    /** 이름을 바꾼다. */
    fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        rawName: String,
    ): Workspace =
        workspaces.rename(ownerId, workspaceId, validName(rawName))
            ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)

    /** 빈 작업 공간을 지운다. */
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

    /** 삭제를 거절해야 하면 그 사유 문구, 아니면 `null`. */
    private fun refusalFor(state: WorkspaceDeletionState): String? =
        when {
            state.ownedWorkspaceCount <= 1 -> LAST_WORKSPACE_MESSAGE
            state.documentCount > 0 -> WORKSPACE_HAS_DOCUMENTS_MESSAGE
            else -> null
        }

    private fun validName(rawName: String): String = requireValidWorkspaceName(normalizeWorkspaceName(rawName))
}

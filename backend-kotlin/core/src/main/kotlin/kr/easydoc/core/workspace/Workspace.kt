package kr.easydoc.core.workspace

import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/** 가입 시 함께 만들어지는 기본 작업 공간의 이름. */
const val DEFAULT_WORKSPACE_NAME: String = "기본 작업 공간"

/** 작업 공간 한 건. 계약 `components/schemas/WorkspaceResponse` 가 요구하는 세 값이다. */
data class Workspace(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
) {
    /** **이름을 찍지 않는다.** */
    override fun toString(): String = "Workspace(id=$id, name=$NAME_MASK, createdAt=$createdAt)"

    companion object {
        /** 이름 자리에 대신 찍히는 표식. 테스트가 이 값으로 「가려졌음」을 확인한다. */
        const val NAME_MASK: String = CONTENT_MASK
    }
}

/** 목록 한 줄 — 작업 공간 + 그 안의 문서 수. */
data class WorkspaceListing(
    val workspace: Workspace,
    val documentCount: Int,
)

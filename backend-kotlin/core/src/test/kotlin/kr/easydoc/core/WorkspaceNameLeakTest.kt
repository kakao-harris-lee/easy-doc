package kr.easydoc.core

import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 작업 공간 이름이 `toString()` 으로 새지 않는지 확인한다 (A-3). */
class WorkspaceNameLeakTest {
    private val name = "감사보고서 초안 2026 3분기"

    @Test
    fun `Workspace toString 이 이름을 노출하지 않는다`() {
        val workspace = Workspace(UUID.randomUUID(), name, Instant.EPOCH)

        assertThat(workspace.toString()).doesNotContain(name)
        assertThat(workspace.toString()).contains(Workspace.NAME_MASK)

        assertThat(workspace.toString()).contains(workspace.id.toString())
    }

    @Test
    fun `감싸는 타입에 실려도 이름이 나가지 않는다`() {
        val listing = WorkspaceListing(Workspace(UUID.randomUUID(), name, Instant.EPOCH), documentCount = 3)

        assertThat(listing.toString()).doesNotContain(name)
        assertThat(listOf(listing).toString()).doesNotContain(name)

        assertThat("작업 공간 조회: $listing").doesNotContain(name)
    }
}

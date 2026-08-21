package kr.easydoc.api

import kr.easydoc.api.workspace.WorkspaceListItemResponse
import kr.easydoc.api.workspace.WorkspaceListResponse
import kr.easydoc.api.workspace.WorkspaceNameRequest
import kr.easydoc.api.workspace.WorkspaceResponse
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `/workspaces` DTO 의 `toString()` 이 사용자가 적은 이름을 노출하지 않는지 확인한다 (A-3). */
class WorkspaceDtoLeakTest {
    private val name = "복지 안내문 초안"

    @Test
    @DisplayName("요청·응답 DTO 의 toString 이 이름을 노출하지 않는다")
    fun `DTO toString 이 이름을 가린다`() {
        val single = WorkspaceResponse.of(sample())
        val item = WorkspaceListItemResponse.of(WorkspaceListing(sample(), documentCount = 2))
        val request = WorkspaceNameRequest(name)

        listOf(single.toString(), item.toString(), request.toString()).forEach { rendered ->
            assertThat(rendered).doesNotContain(name)
            assertThat(rendered).contains(Workspace.NAME_MASK)
        }

        assertThat(WorkspaceListResponse(listOf(item)).toString()).doesNotContain(name)
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 직렬화되는 값은 그대로다")
    fun `응답 값 자체는 이름을 그대로 담는다`() {
        assertThat(WorkspaceResponse.of(sample()).name).isEqualTo(name)
        assertThat(WorkspaceNameRequest(name).name).isEqualTo(name)
    }

    private fun sample(): Workspace = Workspace(UUID.randomUUID(), name, Instant.EPOCH)
}

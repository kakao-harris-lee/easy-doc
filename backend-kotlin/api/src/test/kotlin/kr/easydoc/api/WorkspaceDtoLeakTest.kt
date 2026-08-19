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

/**
 * `/workspaces` DTO 의 `toString()` 이 사용자가 적은 이름을 노출하지 않는지 확인한다 (A-3).
 *
 * `core` 쪽은 `WorkspaceNameLeakTest` 가 맡고 여기는 **HTTP 표현 타입**이다. 요청 DTO 를
 * 함께 두는 이유: 역직렬화·검증 실패의 진단 로그가 **요청 객체를 통째로** 찍는 것이 가장
 * 흔한 형태이고, 거기 담긴 것이 사용자가 방금 적은 문자열이다.
 *
 * 응답 DTO 는 **직렬화 결과에는 이름이 있어야 한다** — 계약이 `name` 을 required 로 뒀다.
 * 가리는 것은 `toString()` 뿐이고, 그 구분이 이 테스트가 지키는 것이다.
 */
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
        // 목록 응답은 항목의 toString 을 타고 나간다 — 감싸는 타입도 함께 본다.
        assertThat(WorkspaceListResponse(listOf(item)).toString()).doesNotContain(name)
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 직렬화되는 값은 그대로다")
    fun `응답 값 자체는 이름을 그대로 담는다`() {
        // 여기까지 가리면 계약(`name` required)이 깨진다. 두 축을 섞지 않는다.
        assertThat(WorkspaceResponse.of(sample()).name).isEqualTo(name)
        assertThat(WorkspaceNameRequest(name).name).isEqualTo(name)
    }

    private fun sample(): Workspace = Workspace(UUID.randomUUID(), name, Instant.EPOCH)
}

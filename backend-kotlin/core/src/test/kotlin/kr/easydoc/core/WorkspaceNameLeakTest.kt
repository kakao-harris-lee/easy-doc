package kr.easydoc.core

import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 작업 공간 **이름**이 `toString()` 으로 새지 않는지 확인한다 (A-3).
 *
 * 계약이 작업 공간 이름을 사적 응답 헤더 대상으로 분류했다
 * (`x-private-response-headers.applies_to` — *"작업 공간 이름도 사용자가 적은 콘텐츠"*).
 * Kotlin `data class` 의 기본 동작은 그 반대라 `Secret` 과 같은 방식으로 막고, 막았다는
 * 사실을 여기 고정한다.
 *
 * **오늘 도달은 0이다** — 작업 공간 경로에 로거가 0개다. 이 테스트는 「지금 새고 있다」가
 * 아니라 **「로깅이 처음 들어오는 커밋에서 새지 않는다」**를 지킨다. 그 커밋을 쓰는 사람은
 * 이 파일을 보지 않을 것이므로, 보지 않아도 걸리게 둔다.
 */
class WorkspaceNameLeakTest {
    private val name = "감사보고서 초안 2026 3분기"

    @Test
    fun `Workspace toString 이 이름을 노출하지 않는다`() {
        val workspace = Workspace(UUID.randomUUID(), name, Instant.EPOCH)

        assertThat(workspace.toString()).doesNotContain(name)
        assertThat(workspace.toString()).contains(Workspace.NAME_MASK)
        // 진단에 필요한 것은 남아야 한다 — 통째로 가리면 로그가 쓸모없어져 다시 풀린다.
        assertThat(workspace.toString()).contains(workspace.id.toString())
    }

    @Test
    fun `감싸는 타입에 실려도 이름이 나가지 않는다`() {
        // 가장 흔한 유출 경로 — 컬렉션이나 상위 객체를 통째로 로깅하는 경우.
        val listing = WorkspaceListing(Workspace(UUID.randomUUID(), name, Instant.EPOCH), documentCount = 3)

        assertThat(listing.toString()).doesNotContain(name)
        assertThat(listOf(listing).toString()).doesNotContain(name)
        // 문자열 템플릿도 같은 경로다 — 로거 인자와 예외 메시지가 이 모양으로 만들어진다.
        assertThat("작업 공간 조회: $listing").doesNotContain(name)
    }
}

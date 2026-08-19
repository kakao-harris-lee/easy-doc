package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.WorkspaceLookup
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 문서 경로가 쓰는 **작업 공간 읽기 전용** 어댑터.
 *
 * ## 왜 `JdbcWorkspaceRepository` 가 이 포트를 겸하지 않는가 (실측으로 정한 자리)
 *
 * 처음에는 그 클래스가 두 포트를 함께 구현하게 했다. **기동이 깨졌다** — `AuthConfiguration`
 * 이 내는 `workspaceRepository` 빈과 문서 쪽 `workspaceLookup` 빈이 **같은 구상 클래스**라,
 * Spring 이 두 포트 어느 쪽을 주입할 때도 후보를 둘로 보고 `NoUniqueBeanDefinitionException`
 * 을 냈다(실측: `worker`·`api` 기동 테스트 전건 빨강). 한쪽 빈을 지우는 갈래도 있었지만
 * 그러면 `DocumentConfiguration` 만 올린 컨텍스트(통합 테스트)에서 이 포트가 사라진다.
 *
 * 그래서 **구상 클래스를 가른다.** 포트 하나당 클래스 하나이면 주입이 모호해질 수 없고,
 * 문서 경로가 가질 수 있는 권한도 읽기 둘로 좁혀진다(원본
 * `app/services/documents.py::WorkspaceLookup` 이 적은 최소 권한 판단과 같다).
 *
 * ## 소유 조건이 SQL 안에 있다
 *
 * `JdbcWorkspaceRepository` 와 같은 규칙이다 — 읽은 뒤 Kotlin 에서 비교하지 않는다.
 * 비교를 잊으면 조용히 남의 자원을 내주고, 잊지 않아도 **남의 자원일 때만 행을 읽으므로**
 * 그 차이가 응답 시간에 남는다.
 *
 * 이름·생성 시각을 읽지 않고 `id` 만 돌려준다. 문서 경로가 쓰지 않을 값을 요청마다 힙에
 * 올릴 이유가 없고, 작업 공간 이름은 사용자가 적은 콘텐츠다.
 */
class JdbcWorkspaceLookup(private val jdbc: JdbcClient) : WorkspaceLookup {
    override fun findOwnedId(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID? =
        jdbc
            .sql("SELECT id FROM workspaces WHERE id = :id AND user_id = :ownerId")
            .param("id", workspaceId)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .optional()
            .orElse(null)

    /**
     * 기본 작업 공간 — **가장 먼저 만든 것**이다(계약 `GET /workspaces` 가 그 순서를 못박았다).
     *
     * 동률을 `id` 로 가른다. `created_at` 이 같은 두 행의 순서가 정해지지 않으면 「첫 번째가
     * 기본 작업 공간」이 실행마다 다른 값이 되고, 그러면 **업로드가 갈 곳이 흔들린다.**
     * `JdbcWorkspaceRepository.listOwned` 가 같은 이유로 같은 정렬을 쓴다 — 두 자리가
     * 갈리면 목록의 첫 줄과 업로드 대상이 서로 다른 작업 공간이 된다.
     */
    override fun findDefaultId(ownerId: UUID): UUID? =
        jdbc
            .sql(
                """
                SELECT id FROM workspaces
                WHERE user_id = :ownerId
                ORDER BY created_at, id
                LIMIT 1
                """.trimIndent(),
            ).param("ownerId", ownerId)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .optional()
            .orElse(null)
}

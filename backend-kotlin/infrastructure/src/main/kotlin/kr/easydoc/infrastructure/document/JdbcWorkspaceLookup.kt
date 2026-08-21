package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.WorkspaceLookup
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** 문서 경로가 쓰는 **작업 공간 읽기 전용** 어댑터. */
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

    /** 기본 작업 공간 — **가장 먼저 만든 것**이다(계약 `GET /workspaces` 가 그 순서를 못박았다). */
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

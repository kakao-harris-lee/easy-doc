package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.WorkspaceDeletionState
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.workspace.DUPLICATE_WORKSPACE_NAME_MESSAGE
import kr.easydoc.application.workspace.WORKSPACE_HAS_DOCUMENTS_MESSAGE
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `workspaces` 테이블 접근. 스키마는 `V1__initial_schema.sql` 이 정한다. */
class JdbcWorkspaceRepository(private val jdbc: JdbcClient) : WorkspaceRepository {
    /** 기본 작업 공간을 만든다. */
    override fun createDefault(userId: UUID): UUID {
        val id = UUID.randomUUID()
        try {
            jdbc
                .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
                .param("id", id)
                .param("userId", userId)
                .param("name", DEFAULT_WORKSPACE_NAME)
                .update()
        } catch (_: DataIntegrityViolationException) {
            // 원인을 잇지 않는다 — PostgreSQL 이 제약 위반 DETAIL 에 행 전체를 담는다.
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }
        return id
    }

    /** 소유한 작업 공간을 문서 수와 함께 **만든 순서로** 읽는다. */
    override fun listOwned(ownerId: UUID): List<WorkspaceListing> =
        jdbc
            .sql(
                """
                SELECT w.id, w.name, w.created_at, count(d.id) AS document_count
                FROM workspaces w
                LEFT JOIN documents d ON d.workspace_id = w.id
                WHERE w.user_id = :ownerId
                GROUP BY w.id, w.name, w.created_at
                ORDER BY w.created_at, w.id
                """.trimIndent(),
            ).param("ownerId", ownerId)
            .query { rs, _ -> WorkspaceListing(toWorkspace(rs), rs.getInt("document_count")) }
            .list()

    /** 새 작업 공간을 만든다. */
    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace {
        val id = UUID.randomUUID()
        return try {
            jdbc
                .sql(
                    """
                    INSERT INTO workspaces (id, user_id, name)
                    VALUES (:id, :ownerId, :name)
                    RETURNING id, name, created_at
                    """.trimIndent(),
                ).param("id", id)
                .param("ownerId", ownerId)
                .param("name", name)
                .query { rs, _ -> toWorkspace(rs) }
                .single()
        } catch (_: DuplicateKeyException) {
            throw ConflictException(DUPLICATE_WORKSPACE_NAME_MESSAGE)
        } catch (_: DataIntegrityViolationException) {
            // 남은 제약은 사용자 FK 뿐이다 — 토큰은 유효한데 계정이 지워진 자리이고,
            // 계약이 그 갈래를 이 경로에 두지 않았다. 산출물에 열린 항목으로 적었다.
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }
    }

    /** 이름을 바꾼다. 갱신된 행이 없으면 `null` — **없는 것과 내 것이 아닌 것을 가르지 않는다.** */
    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace? =
        try {
            jdbc
                .sql(
                    """
                    UPDATE workspaces SET name = :name
                    WHERE id = :id AND user_id = :ownerId
                    RETURNING id, name, created_at
                    """.trimIndent(),
                ).param("name", name)
                .param("id", workspaceId)
                .param("ownerId", ownerId)
                .query { rs, _ -> toWorkspace(rs) }
                .optional()
                .orElse(null)
        } catch (_: DuplicateKeyException) {
            throw ConflictException(DUPLICATE_WORKSPACE_NAME_MESSAGE)
        } catch (_: DataIntegrityViolationException) {
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }

    /** 삭제 판정에 필요한 상태를 읽고 **그 사용자의 작업 공간 행 전부를 잠근다**. */
    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState? {
        val locked =
            jdbc
                .sql("SELECT id FROM workspaces WHERE user_id = :ownerId ORDER BY id FOR UPDATE")
                .param("ownerId", ownerId)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list()

        if (workspaceId !in locked) {
            return null
        }

        val documentCount =
            jdbc
                .sql("SELECT count(*) FROM documents WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query { rs, _ -> rs.getInt(1) }
                .single()

        return WorkspaceDeletionState(ownedWorkspaceCount = locked.size, documentCount = documentCount)
    }

    /** 지운다. */
    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean =
        try {
            jdbc
                .sql("DELETE FROM workspaces WHERE id = :id AND user_id = :ownerId")
                .param("id", workspaceId)
                .param("ownerId", ownerId)
                .update() > 0
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException(WORKSPACE_HAS_DOCUMENTS_MESSAGE)
        }

    private fun toWorkspace(rs: ResultSet): Workspace =
        Workspace(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            // timestamptz 를 OffsetDateTime 으로 받는다. `getTimestamp` 는 JVM 기본 시간대를
            // 끼워 넣어 서버 시간대에 따라 값이 달라진다.
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private companion object {
        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"
    }
}

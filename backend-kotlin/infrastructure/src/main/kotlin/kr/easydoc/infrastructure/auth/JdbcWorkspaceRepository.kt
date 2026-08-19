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

/**
 * `workspaces` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` 이 정한다.
 *
 * ## 소유 조건이 SQL 안에 있다
 *
 * 모든 질의가 `WHERE ... AND user_id = :ownerId` 를 든다. 읽은 뒤에 Kotlin 에서 소유자를
 * 비교하지 않는 이유가 둘이다 — 비교를 잊으면 조용히 남의 자원을 내주고, 잊지 않아도
 * **남의 자원일 때만 행을 읽으므로 그 차이가 응답 시간에 남는다.** 계약이 숨기라고 한
 * 것에는 시간 축도 들어 있다.
 *
 * ## 원본 DB 예외를 올리지 않는다
 *
 * PostgreSQL 은 제약 위반의 `DETAIL` 에 **실패한 행 전체**를 담는다 — 여기서는 사용자가
 * 적은 작업 공간 이름이 그 문자열에 실린다. 그대로 감싸 올리면 로그와 응답 어디로든
 * 새므로 도메인 예외로 갈아 끼우고 **원인 체인을 끊는다**.
 */
class JdbcWorkspaceRepository(private val jdbc: JdbcClient) : WorkspaceRepository {
    /**
     * 기본 작업 공간을 만든다.
     *
     * 제약 위반은 전부 [StorageException] → 500 이다. 방금 만든 사용자에게
     * `uq_workspaces_user_id_name` 이 걸릴 수 없고 FK 도 같은 트랜잭션의 행을 가리키므로,
     * 여기서 터지는 것은 **사용자 입력 문제가 아니라 코드·스키마 버그**다. 4xx 로 감싸면
     * 서버 버그가 "사용자가 뭘 잘못했다"로 둔갑해 조용히 묻힌다.
     */
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

    /**
     * 소유한 작업 공간을 문서 수와 함께 **만든 순서로** 읽는다.
     *
     * 문서 수는 `LEFT JOIN` + `count` 로 한 질의에 담는다 — 목록 한 줄마다 COUNT 질의를
     * 따로 내면 N+1 이 된다. `count(d.id)` 이지 `count(*)` 가 아닌 것이 요점이다:
     * `LEFT JOIN` 이 만든 NULL 행을 `count(*)` 는 1 로 세어 빈 작업 공간이 1 이 된다.
     *
     * `created_at` 이 같은 두 행의 순서를 `id` 로 가른다. 같은 트랜잭션에서 두 개를 만들면
     * `now()` 가 같아 순서가 정해지지 않는데, 정해지지 않은 순서는 **"첫 번째가 기본 작업
     * 공간"이라는 계약을 실행마다 다르게 만든다.** `id` 가 뜻 있는 순서를 주지는 않지만
     * 흔들리지 않게는 한다.
     */
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

    /**
     * 새 작업 공간을 만든다.
     *
     * `created_at` 은 DB `DEFAULT now()` 가 채우고 `RETURNING` 으로 되읽는다 — 앱 시계와
     * DB 시계가 갈리는 자리를 만들지 않는다.
     */
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

    /**
     * 이름을 바꾼다. 갱신된 행이 없으면 `null` — **없는 것과 내 것이 아닌 것을 가르지 않는다.**
     *
     * 소유 조건이 `WHERE` 에 있으므로 남의 자원에서는 유일성 위반이 애초에 일어나지 않는다.
     * 즉 남의 작업 공간 식별자로 이미 쓰이는 이름을 보내도 409 가 아니라 404 다 — 409 가
     * 나가면 그것이 "그 식별자는 존재한다"는 신호가 된다.
     */
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

    /**
     * 삭제 판정에 필요한 상태를 읽고 **그 사용자의 작업 공간 행 전부를 잠근다**.
     *
     * 대상 행 하나만 잠그면 「마지막 하나」 판정이 집합에 걸린다는 사실을 지키지 못한다 —
     * 같은 사용자의 서로 다른 두 작업 공간을 동시에 지우면 둘 다 "아직 둘 있다"를 보고
     * 통과해 결과가 0 개가 된다. `ORDER BY id` 로 잠금 순서를 고정해 두 요청이 서로를
     * 물고 늘어지는 교착을 막는다.
     *
     * **없는 자원과 남의 자원이 같은 지점에서 `null` 로 끝난다** — 문서 수 질의는 소유가
     * 확인된 뒤에만 돈다. 두 경우가 하는 일이 같으므로 응답 시간이 존재 여부를 알려 주지
     * 않는다.
     */
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

    /**
     * 지운다.
     *
     * 무결성 위반을 409 로 옮긴다. 이 DELETE 에서 터질 수 있는 제약은
     * `fk_documents_workspace_id_workspaces` **하나뿐**이므로 원인을 메시지에서 읽을 필요가
     * 없다 — 스키마가 `ON DELETE` 를 주지 않아(NO ACTION) 문서가 든 작업 공간은 DB 가
     * 지우지 못하게 막는다. 호출자가 트랜잭션 안에서 문서 수를 이미 확인하지만, 그 확인과
     * 이 DELETE 사이에 **문서 삽입은 아무것도 막지 않는다.** 그때 남는 것이 이 갈래다.
     */
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

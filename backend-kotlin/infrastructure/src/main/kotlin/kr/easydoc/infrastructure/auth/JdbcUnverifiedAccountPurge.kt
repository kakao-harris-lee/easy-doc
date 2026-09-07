package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.UnverifiedAccountPurge
import kr.easydoc.application.auth.UnverifiedAccountPurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 미검증 계정을 [createdBefore] 기준으로 고르고 지운다 — `JdbcExpiredDocumentPurge` 와
 * 같은 잠금(`FOR UPDATE SKIP LOCKED`) 후 별도 `DELETE` 두 단계 구조다.
 *
 * 문서를 가진 계정은 후보에서 아예 제외한다(`NOT EXISTS`) — 미검증 계정은 문서를 만들 수
 * 없다는 전제(`DocumentService.requireVerifiedEmail`)가 있지만 이 파기는 그 규칙에 기대지
 * 않는다. 제외된 건수는 별도 질의로 센다 — 감사 로그에 「몇 건을 왜 건드리지 않았는지」를
 * 남기기 위해서다.
 *
 * 삭제는 `users` 행 하나만 지운다 — `workspaces`·`documents`·`user_identities`·
 * `oauth_states`·`email_verification_codes`·`password_reset_codes` 는 전부 `user_id` FK가
 * `ON DELETE CASCADE` 라 함께 사라진다(`V1__initial_schema.sql`, `V6__user_identities.sql`,
 * `V7__email_verification.sql`, `V8__oauth_state_link_user.sql`,
 * `V11__password_reset_codes.sql`).
 */
class JdbcUnverifiedAccountPurge(private val jdbc: JdbcClient) : UnverifiedAccountPurge {
    override fun purge(
        createdBefore: Instant,
        batchSize: Int,
    ): UnverifiedAccountPurgeResult {
        val ids = lockCandidates(createdBefore, batchSize)
        val skipped = countSkippedWithDocuments(createdBefore)
        val deleted = if (ids.isNotEmpty()) deleteUsers(ids) else 0
        return UnverifiedAccountPurgeResult(enabled = true, deleted = deleted, skippedWithDocuments = skipped)
    }

    private fun lockCandidates(
        createdBefore: Instant,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(LOCK_CANDIDATES_SQL)
            .param("createdBefore", Timestamp.from(createdBefore))
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun countSkippedWithDocuments(createdBefore: Instant): Int =
        jdbc
            .sql(COUNT_SKIPPED_SQL)
            .param("createdBefore", Timestamp.from(createdBefore))
            .query { rs, _ -> rs.getInt(1) }
            .single()

    /**
     * 실제로 지워진 행 수를 돌려준다 — 방어 계층 `AND email_verified_at IS NULL` 이 걸러낸
     * 행이 있으면 [lockCandidates] 가 고른 후보 수보다 적을 수 있다. 정상 경로에서는
     * [lockCandidates] 가 이미 같은 트랜잭션 안에서 이 행들을 잠갔으므로 그 사이 다른
     * 트랜잭션이 `email_verified_at` 을 채울 수 없다 — 이 조건은 그 불변식이 깨지는
     * 미래의 실수를 잡는 안전망이다(`EmailVerificationService.confirm` 과의 경합 — team
     * lead 리뷰 후속, 2026-09-07).
     */
    private fun deleteUsers(ids: List<UUID>): Int {
        val statement =
            ids.foldIndexed(jdbc.sql(deleteSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        return statement.update()
    }

    private companion object {
        fun idParam(index: Int): String = "id$index"

        fun placeholders(size: Int): String = (0 until size).joinToString { ":${idParam(it)}" }

        fun deleteSql(size: Int): String =
            "DELETE FROM users WHERE id IN (${placeholders(size)}) AND email_verified_at IS NULL"

        val LOCK_CANDIDATES_SQL =
            """
            SELECT u.id
            FROM users u
            WHERE u.email_verified_at IS NULL
              AND u.created_at < :createdBefore
              AND NOT EXISTS (
                  SELECT 1 FROM documents d WHERE d.user_id = u.id
              )
            ORDER BY u.created_at ASC, u.id ASC
            LIMIT :limit
            FOR UPDATE OF u SKIP LOCKED
            """.trimIndent()

        val COUNT_SKIPPED_SQL =
            """
            SELECT count(*)
            FROM users u
            WHERE u.email_verified_at IS NULL
              AND u.created_at < :createdBefore
              AND EXISTS (
                  SELECT 1 FROM documents d WHERE d.user_id = u.id
              )
            """.trimIndent()
    }
}

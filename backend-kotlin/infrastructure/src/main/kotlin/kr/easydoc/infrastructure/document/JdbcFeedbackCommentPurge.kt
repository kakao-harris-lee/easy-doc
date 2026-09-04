package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.FeedbackCommentPurge
import kr.easydoc.application.document.FeedbackCommentPurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 보존 일수가 지난 `conversion_feedback` 자유 의견의 봉투 세 열을 DB 시계 기준으로 고르고
 * 비운다. `JdbcExpiredDocumentPurge`와 같은 잠금(`FOR UPDATE SKIP LOCKED`) 후 별도
 * `UPDATE` 두 단계 구조다 — 잠근 행 집합과 실제로 비운 행 집합을 갈라 두면 `dryRun`이
 * 두 번째 단계만 건너뛰는 것으로 끝난다.
 *
 * 나이 기준은 `submitted_at`이다 — `FeedbackProperties`의 `commentRetentionDays` KDoc이
 * 정본이고, 그 이유(키 회전은 `updated_at`만 밀고 `submitted_at`은 밀지 않는다)도 거기 있다.
 */
class JdbcFeedbackCommentPurge(private val jdbc: JdbcClient) : FeedbackCommentPurge {
    override fun purge(
        dryRun: Boolean,
        limit: Int,
        retentionDays: Int,
    ): FeedbackCommentPurgeResult {
        val ids = lockExpiredComments(limit, retentionDays)
        if (!dryRun && ids.isNotEmpty()) {
            nullOutComments(ids)
        }
        return FeedbackCommentPurgeResult(dryRun = dryRun, enabled = true, purgedComments = ids.size)
    }

    private fun lockExpiredComments(
        limit: Int,
        retentionDays: Int,
    ): List<UUID> =
        jdbc
            .sql(LOCK_EXPIRED_COMMENTS_SQL)
            .param("limit", limit)
            .param("retentionDays", retentionDays)
            .query { rs, _ -> rs.getObject("conversion_id", UUID::class.java) }
            .list()

    private fun nullOutComments(ids: List<UUID>) {
        val statement =
            ids.foldIndexed(jdbc.sql(nullOutSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        statement.update()
    }

    private companion object {
        fun idParam(index: Int): String = "id$index"

        fun placeholders(size: Int): String = (0 until size).joinToString { ":${idParam(it)}" }

        fun nullOutSql(size: Int): String =
            """
            UPDATE conversion_feedback
            SET comment_encrypted = NULL, encryption_scheme = NULL, key_version = NULL
            WHERE conversion_id IN (${placeholders(size)})
            """.trimIndent()

        val LOCK_EXPIRED_COMMENTS_SQL =
            """
            SELECT conversion_id
            FROM conversion_feedback
            WHERE comment_encrypted IS NOT NULL
              AND submitted_at < now() - make_interval(days => :retentionDays)
            ORDER BY submitted_at ASC, conversion_id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.trimIndent()
    }
}

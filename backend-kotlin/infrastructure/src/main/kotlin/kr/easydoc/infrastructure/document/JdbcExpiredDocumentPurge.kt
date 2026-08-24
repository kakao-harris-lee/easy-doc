package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 만료 문서를 DB 시계 기준으로 고른다. 활성 리스(`leased` 이고 `lease_until` 이 미래)가 있는
 * 행은 건너뛰어, 변환 worker 가 붙잡고 있는 문서를 밑에서 지우지 않는다.
 */
class JdbcExpiredDocumentPurge(private val jdbc: JdbcClient) : ExpiredDocumentPurge {
    override fun purge(
        dryRun: Boolean,
        limit: Int,
    ): RetentionPurgeResult {
        val skippedLeased = countSkippedLeased()
        val ids = lockExpiredWithoutLease(limit)
        val conversions = countConversions(ids)
        if (!dryRun && ids.isNotEmpty()) {
            deleteDocuments(ids)
        }
        return RetentionPurgeResult(
            dryRun = dryRun,
            enabled = true,
            purgedDocuments = ids.size,
            purgedConversions = conversions,
            skippedLeased = skippedLeased,
            documentIds = ids,
        )
    }

    private fun countSkippedLeased(): Int =
        jdbc
            .sql(SKIPPED_LEASED_SQL)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun lockExpiredWithoutLease(limit: Int): List<UUID> =
        jdbc
            .sql(LOCK_EXPIRED_SQL)
            .param("limit", limit)
            .param("leased", JdbcConversionQueue.LEASED_STATE)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun countConversions(ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        val statement =
            ids.foldIndexed(jdbc.sql(countConversionsSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        return statement.query { rs, _ -> rs.getInt(1) }.single()
    }

    private fun deleteDocuments(ids: List<UUID>) {
        val statement =
            ids.foldIndexed(jdbc.sql(deleteSql(ids.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        statement.update()
    }

    private companion object {
        fun idParam(index: Int): String = "id$index"

        fun placeholders(size: Int): String = (0 until size).joinToString { ":${idParam(it)}" }

        fun countConversionsSql(size: Int): String =
            "SELECT count(*) FROM conversions WHERE document_id IN (${placeholders(size)})"

        fun deleteSql(size: Int): String = "DELETE FROM documents WHERE id IN (${placeholders(size)})"

        val SKIPPED_LEASED_SQL =
            """
            SELECT count(*)
            FROM documents d
            WHERE d.retention_expires_at <= now()
              AND EXISTS (
                  SELECT 1
                  FROM conversions c
                  INNER JOIN conversion_jobs j ON j.conversion_id = c.id
                  WHERE c.document_id = d.id
                    AND j.state = '${JdbcConversionQueue.LEASED_STATE}'
                    AND j.lease_until > now()
              )
            """.trimIndent()

        val LOCK_EXPIRED_SQL =
            """
            SELECT d.id
            FROM documents d
            WHERE d.retention_expires_at <= now()
              AND NOT EXISTS (
                  SELECT 1
                  FROM conversions c
                  INNER JOIN conversion_jobs j ON j.conversion_id = c.id
                  WHERE c.document_id = d.id
                    AND j.state = :leased
                    AND j.lease_until > now()
              )
            ORDER BY d.retention_expires_at ASC, d.id ASC
            LIMIT :limit
            FOR UPDATE OF d SKIP LOCKED
            """.trimIndent()
    }
}

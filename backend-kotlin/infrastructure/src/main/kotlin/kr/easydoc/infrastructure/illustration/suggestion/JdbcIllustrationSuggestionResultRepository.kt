package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultRepository
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionResult
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 결과는 암호문만 다루고, 사용자 경로에서 소유권과 보존기간을 한 SQL 로 확인한다. */
class JdbcIllustrationSuggestionResultRepository(private val jdbc: JdbcClient) :
    IllustrationSuggestionResultRepository {
    override fun insertResult(
        job: StoredIllustrationSuggestionJob,
        result: StoredIllustrationSuggestionResult,
    ): Boolean {
        require(result.jobId == job.jobId && result.conversionId == job.conversionId)
        require(result.basedOnContentRevision == job.basedOnContentRevision)
        return jdbc
            .sql(INSERT_SQL)
            .param("id", result.resultId)
            .param("jobId", result.jobId)
            .param("ownerId", job.ownerId)
            .param("conversionId", result.conversionId)
            .param("revision", result.basedOnContentRevision)
            .param("payload", result.payload.bytes)
            .param("scheme", result.payload.scheme)
            .param("keyVersion", result.payload.keyVersion)
            .param("createdAt", utc(result.createdAt))
            .update() == 1
    }

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionResult? =
        jdbc
            .sql(LATEST_SQL)
            .param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::resultRow)
            .optional()
            .orElse(null)

    private fun resultRow(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): StoredIllustrationSuggestionResult =
        StoredIllustrationSuggestionResult(
            resultId = rs.getObject("id", UUID::class.java),
            jobId = rs.getObject("job_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            payload =
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        val INSERT_SQL =
            """
            INSERT INTO illustration_suggestion_results
                (id, job_id, conversion_id, based_on_content_revision,
                 payload_encrypted, encryption_scheme, key_version, created_at)
            SELECT :id, j.id, c.id, :revision, :payload, :scheme, :keyVersion, :createdAt
            FROM illustration_suggestion_jobs j
            JOIN conversions c ON c.id = j.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE j.id = :jobId AND j.owner_user_id = :ownerId
              AND j.document_id = d.id AND j.conversion_id = :conversionId
              AND j.based_on_content_revision = :revision
              AND j.status IN ('running', 'succeeded')
              AND c.content_revision = :revision AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            ON CONFLICT (job_id) DO NOTHING
            """.trimIndent()

        val LATEST_SQL =
            """
            SELECT r.id, r.job_id, r.conversion_id, r.based_on_content_revision,
                   r.payload_encrypted, r.encryption_scheme, r.key_version, r.created_at
            FROM illustration_suggestion_results r
            JOIN conversions c ON c.id = r.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE r.conversion_id = :conversionId AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT 1
            """.trimIndent()
    }
}

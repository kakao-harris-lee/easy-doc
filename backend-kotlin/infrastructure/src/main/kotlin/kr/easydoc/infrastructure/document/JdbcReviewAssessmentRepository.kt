package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ReviewAssessmentRepository
import kr.easydoc.application.document.StoredReviewAssessment
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

class JdbcReviewAssessmentRepository(private val jdbc: JdbcClient) : ReviewAssessmentRepository {
    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredReviewAssessment? =
        jdbc
            .sql(
                """
                SELECT a.* FROM review_assessments a
                JOIN conversions c ON c.id = a.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE a.conversion_id = :conversionId AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT 1
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::row)
            .optional()
            .orElse(null)

    override fun findExact(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        analyzerVersion: String,
    ): StoredReviewAssessment? =
        jdbc
            .sql(
                """
                SELECT a.* FROM review_assessments a
                JOIN conversions c ON c.id = a.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE a.conversion_id = :conversionId AND a.content_revision = :contentRevision
                  AND a.analyzer_version = :analyzerVersion AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("contentRevision", contentRevision)
            .param("analyzerVersion", analyzerVersion)
            .param("ownerId", ownerId)
            .query(::row)
            .optional()
            .orElse(null)

    override fun lockOwned(
        ownerId: UUID,
        conversionId: UUID,
        assessmentId: UUID,
    ): StoredReviewAssessment? =
        jdbc
            .sql(
                """
                SELECT a.* FROM review_assessments a
                JOIN conversions c ON c.id = a.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE a.id = :assessmentId AND a.conversion_id = :conversionId AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                FOR NO KEY UPDATE OF a
                """.trimIndent(),
            ).param("assessmentId", assessmentId)
            .param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::row)
            .optional()
            .orElse(null)

    override fun insert(
        ownerId: UUID,
        assessment: StoredReviewAssessment,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO review_assessments
                    (id, conversion_id, content_revision, analyzer_version, review_revision,
                     payload_encrypted, encryption_scheme, key_version)
                SELECT :id, :conversionId, :contentRevision, :analyzerVersion, :reviewRevision,
                       :payload, :scheme, :keyVersion
                WHERE EXISTS (
                    SELECT 1 FROM conversions c
                    JOIN documents d ON d.id = c.document_id
                    WHERE c.id = :conversionId AND d.user_id = :ownerId
                      AND d.retention_expires_at > now()
                )
                ON CONFLICT (conversion_id, content_revision, analyzer_version) DO NOTHING
                """.trimIndent(),
            ).param("id", assessment.assessmentId)
            .param("conversionId", assessment.conversionId)
            .param("contentRevision", assessment.contentRevision)
            .param("analyzerVersion", assessment.analyzerVersion)
            .param("reviewRevision", assessment.reviewRevision)
            .param("payload", assessment.payload.bytes)
            .param("scheme", assessment.payload.scheme)
            .param("keyVersion", assessment.payload.keyVersion)
            .param("ownerId", ownerId)
            .update() > 0

    override fun update(
        ownerId: UUID,
        assessmentId: UUID,
        expectedReviewRevision: Long,
        payload: EncryptedContent,
        updatedReviewRevision: Long,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE review_assessments
                SET payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion,
                    review_revision = :updatedReviewRevision, updated_at = now()
                WHERE id = :assessmentId AND review_revision = :expectedReviewRevision
                  AND conversion_id IN (
                      SELECT c.id FROM conversions c
                      JOIN documents d ON d.id = c.document_id
                      WHERE d.user_id = :ownerId AND d.retention_expires_at > now()
                  )
                """.trimIndent(),
            ).param("payload", payload.bytes)
            .param("scheme", payload.scheme)
            .param("keyVersion", payload.keyVersion)
            .param("updatedReviewRevision", updatedReviewRevision)
            .param("assessmentId", assessmentId)
            .param("expectedReviewRevision", expectedReviewRevision)
            .param("ownerId", ownerId)
            .update() > 0

    override fun lockEnvelope(assessmentId: UUID): StoredReviewAssessment? =
        jdbc
            .sql("SELECT * FROM review_assessments WHERE id = :id FOR NO KEY UPDATE")
            .param("id", assessmentId)
            .query(::row)
            .optional()
            .orElse(null)

    override fun rewriteEnvelope(
        expected: StoredReviewAssessment,
        payload: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE review_assessments
                SET payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id AND encryption_scheme = :expectedScheme AND key_version = :expectedKeyVersion
                  AND payload_encrypted = :expectedPayload
                """.trimIndent(),
            ).param("payload", payload.bytes)
            .param("scheme", payload.scheme)
            .param("keyVersion", payload.keyVersion)
            .param("id", expected.assessmentId)
            .param("expectedScheme", expected.payload.scheme)
            .param("expectedKeyVersion", expected.payload.keyVersion)
            .param("expectedPayload", expected.payload.bytes)
            .update() > 0

    override fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM review_assessments
                WHERE key_version < :keyVersion AND id > :after
                ORDER BY id ASC LIMIT :limit
                """.trimIndent(),
            ).param("keyVersion", keyVersion)
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun row(
        rs: ResultSet,
        ignored: Int,
    ): StoredReviewAssessment =
        StoredReviewAssessment(
            assessmentId = rs.getObject("id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            contentRevision = rs.getLong("content_revision"),
            analyzerVersion = rs.getString("analyzer_version"),
            reviewRevision = rs.getLong("review_revision"),
            payload =
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
        )
}

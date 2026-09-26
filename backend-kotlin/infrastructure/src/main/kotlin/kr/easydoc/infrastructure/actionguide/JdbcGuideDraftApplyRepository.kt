package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideDraftApplyCommand
import kr.easydoc.application.actionguide.GuideDraftApplyRepository
import kr.easydoc.application.actionguide.GuidePreviousBodyView
import kr.easydoc.application.actionguide.StoredGuideDraftApplication
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/** Every user access checks current ownership and retention, including idempotent replay and recovery. */
class JdbcGuideDraftApplyRepository(private val jdbc: JdbcClient) : GuideDraftApplyRepository {
    override fun listApplications(
        ownerId: UUID,
        conversionId: UUID,
    ): List<GuidePreviousBodyView> =
        jdbc
            .sql(
                """
                SELECT s.id, s.draft_id, s.content_revision, s.applied_content_revision
                FROM action_guide_body_snapshots s
                JOIN conversions c ON c.id = s.conversion_id JOIN documents d ON d.id = c.document_id
                WHERE s.conversion_id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                ORDER BY s.content_revision DESC
                """.trimIndent(),
            ).param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { rs, _ ->
                GuidePreviousBodyView(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("draft_id", UUID::class.java),
                    rs.getLong("content_revision"),
                    rs.getLong("applied_content_revision"),
                )
            }.list()

    override fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredGuideDraftApplication? = select(ownerId, conversionId, "AND s.request_id = :key", requestId)

    override fun findSnapshot(
        ownerId: UUID,
        conversionId: UUID,
        snapshotId: UUID,
    ): StoredGuideDraftApplication? = select(ownerId, conversionId, "AND s.id = :key", snapshotId)

    override fun insertSnapshot(
        ownerId: UUID,
        application: StoredGuideDraftApplication,
    ): Boolean {
        val command = application.command
        return jdbc
            .sql(INSERT_SQL)
            .param("ownerId", ownerId)
            .param("id", application.snapshotId)
            .param("conversionId", application.conversionId)
            .param("requestId", command.requestId)
            .param("draftId", command.draftId)
            .param("contentRevision", command.expectedContentRevision)
            .param("appliedRevision", application.appliedContentRevision)
            .param("analysisRevision", command.expectedAnalysisRevision)
            .param("draftRevision", command.expectedDraftRevision)
            .param("reviewRevision", command.expectedReviewRevision)
            .param("payload", application.previousBody.bytes)
            .param("scheme", application.previousBody.scheme)
            .param("keyVersion", application.previousBody.keyVersion)
            .update() == 1
    }

    override fun saveUnreviewed(
        ownerId: UUID,
        expected: ConversionEnvelope,
        updated: ConversionEnvelope,
        expectedRevision: Long,
        updatedRevision: Long,
    ): Boolean =
        jdbc
            .sql(SAVE_SQL)
            .param("ownerId", ownerId)
            .param("id", expected.conversionId)
            .param("expectedRevision", expectedRevision)
            .param("updatedRevision", updatedRevision)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedEasyText", expected.ciphertexts.easyText?.bytes)
            .param("expectedEditedText", expected.ciphertexts.editedText?.bytes)
            .param("scheme", updated.scheme)
            .param("keyVersion", updated.keyVersion)
            .param("easyText", updated.ciphertexts.easyText?.bytes)
            .param("editedText", updated.ciphertexts.editedText?.bytes)
            .update() == 1

    private fun select(
        ownerId: UUID,
        conversionId: UUID,
        suffix: String,
        key: UUID,
    ): StoredGuideDraftApplication? =
        jdbc
            .sql(SELECT_SQL + " " + suffix)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .param("key", key)
            .query { rs, _ -> read(rs) }
            .optional()
            .orElse(null)

    private fun read(rs: ResultSet): StoredGuideDraftApplication =
        StoredGuideDraftApplication(
            rs.getObject("id", UUID::class.java),
            rs.getObject("conversion_id", UUID::class.java),
            GuideDraftApplyCommand(
                rs.getObject("draft_id", UUID::class.java),
                rs.getObject("request_id", UUID::class.java),
                rs.getLong("content_revision"),
                rs.getLong("analysis_revision"),
                rs.getLong("draft_revision"),
                rs.getLong("review_revision"),
            ),
            rs.getLong("applied_content_revision"),
            EncryptedContent(
                rs.getBytes("payload_encrypted"),
                rs.getString("encryption_scheme"),
                rs.getInt("key_version"),
            ),
        )

    private companion object {
        val SELECT_SQL =
            """
            SELECT s.* FROM action_guide_body_snapshots s
            JOIN conversions c ON c.id = s.conversion_id JOIN documents d ON d.id = c.document_id
            WHERE s.conversion_id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
            """.trimIndent()

        val INSERT_SQL =
            """
            INSERT INTO action_guide_body_snapshots
                (id, conversion_id, request_id, draft_id, content_revision, applied_content_revision,
                 analysis_revision, draft_revision, review_revision, payload_encrypted, encryption_scheme, key_version)
            SELECT :id, c.id, :requestId, :draftId, :contentRevision, :appliedRevision,
                   :analysisRevision, :draftRevision, :reviewRevision, :payload, :scheme, :keyVersion
            FROM conversions c JOIN documents d ON d.id = c.document_id
            WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                AND c.status = 'done' AND c.content_revision = :contentRevision
            """.trimIndent()

        // Incrementing content_revision invokes the existing v1 guide invalidation trigger.
        // R1, illustrations and analysis resources also compare their own base revision on reads.
        val SAVE_SQL =
            """
            UPDATE conversions SET easy_text_encrypted = :easyText, edited_text_encrypted = :editedText,
                encryption_scheme = :scheme, key_version = :keyVersion,
                reviewed_at = NULL, content_revision = :updatedRevision
            WHERE id = :id AND status = 'done' AND content_revision = :expectedRevision
                AND document_id IN (
                    SELECT id FROM documents WHERE user_id = :ownerId AND retention_expires_at > now()
                )
                AND encryption_scheme = :expectedScheme AND key_version = :expectedKeyVersion
                AND easy_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEasyText AS bytea)
                AND edited_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEditedText AS bytea)
            """.trimIndent()
    }
}

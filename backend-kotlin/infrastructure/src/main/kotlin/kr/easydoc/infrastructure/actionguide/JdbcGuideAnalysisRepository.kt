package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideAnalysisInput
import kr.easydoc.application.actionguide.GuideAnalysisRepository
import kr.easydoc.application.actionguide.GuideAnalysisSnapshot
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.exceptions.StorageException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/** Every public lookup binds ownership and retention; the payload is encrypted with its own AAD. */
class JdbcGuideAnalysisRepository(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : GuideAnalysisRepository {
    @Suppress("LongMethod") // Read and decrypt a single locked input envelope.
    override fun lockInput(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisInput? =
        jdbc
            .sql(
                """
                SELECT c.id, c.document_id, c.content_revision, c.status, c.reading_level,
                    c.edited_text_encrypted, c.easy_text_encrypted,
                    c.encryption_scheme, c.key_version, d.source_text_encrypted,
                    d.encryption_scheme AS source_scheme, d.key_version AS source_key_version
                FROM conversions c JOIN documents d ON d.id = c.document_id
                WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                FOR UPDATE OF c
                """.trimIndent(),
            ).param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { rs, _ ->
                val edited = rs.getBytes("edited_text_encrypted")
                val body = edited ?: rs.getBytes("easy_text_encrypted")
                val bodyField =
                    if (edited ==
                        null
                    ) {
                        EncryptedField.CONVERSION_EASY_TEXT
                    } else {
                        EncryptedField.CONVERSION_EDITED_TEXT
                    }
                GuideAnalysisInput(
                    rs.getLong("content_revision"),
                    cipher
                        .decrypt(
                            EncryptedContent(
                                rs.getBytes("source_text_encrypted"),
                                rs.getString("source_scheme"),
                                rs.getInt("source_key_version"),
                            ),
                            rs.getObject("document_id", UUID::class.java),
                            EncryptedField.DOCUMENT_SOURCE_TEXT,
                        ).value,
                    body?.let {
                        cipher
                            .decrypt(
                                EncryptedContent(
                                    it,
                                    rs.getString("encryption_scheme"),
                                    rs.getInt("key_version"),
                                ),
                                conversionId,
                                bodyField,
                            ).value
                    } ?: "",
                    rs.getString("reading_level"),
                    rs.getString("status") == "done",
                )
            }.optional()
            .orElse(null)

    override fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): GuideAnalysisSnapshot? =
        select(
            ownerId,
            conversionId,
            "AND a.id IN (SELECT analysis_id FROM action_guide_analysis_requests " +
                "WHERE conversion_id = :conversionId AND request_id = :key)",
            requestId,
        )

    override fun findRevision(
        ownerId: UUID,
        conversionId: UUID,
        revision: Long,
    ): GuideAnalysisSnapshot? = select(ownerId, conversionId, "AND a.based_on_content_revision = :key", revision)

    override fun find(
        ownerId: UUID,
        conversionId: UUID,
        analysisId: UUID,
    ): GuideAnalysisSnapshot? = select(ownerId, conversionId, "AND a.id = :key", analysisId)

    override fun latest(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisSnapshot? = select(ownerId, conversionId, "ORDER BY a.based_on_content_revision DESC LIMIT 1")

    override fun insert(
        ownerId: UUID,
        conversionId: UUID,
        snapshot: GuideAnalysisSnapshot,
    ) {
        val sealed =
            cipher.encryptBytes(
                PlainBytes(GuideAnalysisSnapshotCodec.encode(snapshot)),
                snapshot.analysisId,
                EncryptedField.ACTION_GUIDE_ANALYSIS_PAYLOAD,
            )
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO action_guide_analyses
                        (id, conversion_id, based_on_content_revision, payload_encrypted, encryption_scheme, key_version)
                    SELECT :id, c.id, :revision, :payload, :scheme, :keyVersion
                    FROM conversions c JOIN documents d ON d.id = c.document_id
                    WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                        AND c.content_revision = :revision AND c.status = 'done'
                    """.trimIndent(),
                ).param("id", snapshot.analysisId)
                .param("conversionId", conversionId)
                .param("ownerId", ownerId)
                .param("revision", snapshot.basedOnContentRevision)
                .param("payload", sealed.bytes)
                .param("scheme", sealed.scheme)
                .param("keyVersion", sealed.keyVersion)
                .update()
        if (inserted != 1) throw StorageException("행동 분석을 저장하지 못했습니다")
    }

    override fun bindRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        analysisId: UUID,
    ) {
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO action_guide_analysis_requests (conversion_id, request_id, analysis_id)
                    SELECT a.conversion_id, :requestId, a.id FROM action_guide_analyses a
                    JOIN conversions c ON c.id = a.conversion_id JOIN documents d ON d.id = c.document_id
                    WHERE a.id = :analysisId AND a.conversion_id = :conversionId
                        AND d.user_id = :ownerId AND d.retention_expires_at > now()
                    """.trimIndent(),
                ).param("conversionId", conversionId)
                .param("ownerId", ownerId)
                .param("requestId", requestId)
                .param("analysisId", analysisId)
                .update()
        if (inserted != 1) throw StorageException("행동 분석 요청을 저장하지 못했습니다")
    }

    private fun select(
        ownerId: UUID,
        conversionId: UUID,
        suffix: String,
        key: Any? = null,
    ): GuideAnalysisSnapshot? {
        var query =
            jdbc
                .sql(
                    """
                    SELECT a.* FROM action_guide_analyses a JOIN conversions c ON c.id = a.conversion_id
                    JOIN documents d ON d.id = c.document_id
                    WHERE a.conversion_id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                    """.trimIndent() + " " + suffix,
                ).param("ownerId", ownerId)
                .param("conversionId", conversionId)
        if (key != null) query = query.param("key", key)
        return query.query(::read).optional().orElse(null)
    }

    private fun read(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") ignored: Int,
    ): GuideAnalysisSnapshot {
        val id = rs.getObject("id", UUID::class.java)
        val opened =
            cipher.decryptBytes(
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
                id,
                EncryptedField.ACTION_GUIDE_ANALYSIS_PAYLOAD,
            )
        return GuideAnalysisSnapshotCodec.decode(opened.value)
    }
}

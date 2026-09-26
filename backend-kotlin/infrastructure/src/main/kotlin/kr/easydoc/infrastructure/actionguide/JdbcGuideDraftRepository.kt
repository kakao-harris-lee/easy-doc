package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideDraft
import kr.easydoc.application.actionguide.GuideDraftBlock
import kr.easydoc.application.actionguide.GuideDraftRepository
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.actionguide.GuideOutputMode
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.exceptions.StorageException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class JdbcGuideDraftRepository(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : GuideDraftRepository {
    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        draftId: UUID,
    ): GuideDraft? = select(ownerId, conversionId, "AND g.id = :key", draftId).singleOrNull()

    override fun listOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): List<GuideDraft> = select(ownerId, conversionId, "ORDER BY g.created_at DESC")

    override fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): GuideDraft? = select(ownerId, conversionId, "AND g.request_id = :key", requestId).singleOrNull()

    override fun insertOwned(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        draft: GuideDraft,
    ) {
        val sealed =
            cipher.encryptBytes(
                PlainBytes(mapper.writeValueAsBytes(draft)),
                draft.draftId,
                EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD,
            )
        val count =
            jdbc
                .sql(
                    """
                    INSERT INTO action_guide_drafts(id, conversion_id, analysis_id, request_id, draft_revision,
                        payload_encrypted, encryption_scheme, key_version)
                    SELECT :id, c.id, a.id, :requestId, :revision, :payload, :scheme, :keyVersion
                    FROM conversions c JOIN documents d ON d.id = c.document_id
                    JOIN action_guide_analyses a ON a.conversion_id = c.id AND a.id = :analysisId
                    WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                        AND c.content_revision = :contentRevision
                    """.trimIndent(),
                ).param("id", draft.draftId)
                .param("analysisId", draft.analysisId)
                .param("requestId", requestId)
                .param("revision", draft.draftRevision)
                .param("payload", sealed.bytes)
                .param("scheme", sealed.scheme)
                .param("keyVersion", sealed.keyVersion)
                .param(
                    "conversionId",
                    conversionId,
                ).param("ownerId", ownerId)
                .param("contentRevision", draft.basedOnContentRevision)
                .update()
        if (count != 1) throw StorageException("보완문을 저장하지 못했습니다")
    }

    override fun replaceOwned(
        ownerId: UUID,
        conversionId: UUID,
        expectedDraftRevision: Long,
        draft: GuideDraft,
    ): Boolean {
        val sealed =
            cipher.encryptBytes(
                PlainBytes(mapper.writeValueAsBytes(draft)),
                draft.draftId,
                EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD,
            )
        return jdbc
            .sql(
                """
                UPDATE action_guide_drafts g SET draft_revision = :revision, payload_encrypted = :payload,
                    encryption_scheme = :scheme, key_version = :keyVersion
                FROM conversions c JOIN documents d ON d.id = c.document_id
                WHERE g.id = :id AND g.conversion_id = c.id AND c.id = :conversionId
                    AND d.user_id = :ownerId AND d.retention_expires_at > now()
                    AND g.draft_revision = :expectedRevision AND c.content_revision = :contentRevision
                """.trimIndent(),
            ).param("id", draft.draftId)
            .param("revision", draft.draftRevision)
            .param("payload", sealed.bytes)
            .param("scheme", sealed.scheme)
            .param("keyVersion", sealed.keyVersion)
            .param(
                "conversionId",
                conversionId,
            ).param("ownerId", ownerId)
            .param("expectedRevision", expectedDraftRevision)
            .param("contentRevision", draft.basedOnContentRevision)
            .update() == 1
    }

    private fun select(
        ownerId: UUID,
        conversionId: UUID,
        suffix: String,
        key: UUID? = null,
    ): List<GuideDraft> {
        var query =
            jdbc
                .sql(
                    """
                    SELECT g.* FROM action_guide_drafts g JOIN conversions c ON c.id = g.conversion_id
                    JOIN documents d ON d.id = c.document_id
                    WHERE g.conversion_id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                    """.trimIndent() + " " + suffix,
                ).param("ownerId", ownerId)
                .param("conversionId", conversionId)
        if (key != null) query = query.param("key", key)
        return query
            .query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val bytes =
                    cipher.decryptBytes(
                        EncryptedContent(
                            rs.getBytes("payload_encrypted"),
                            rs.getString("encryption_scheme"),
                            rs.getInt("key_version"),
                        ),
                        id,
                        EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD,
                    )
                decode(bytes.value)
            }.list()
    }

    private fun decode(bytes: ByteArray): GuideDraft {
        val n = mapper.readTree(bytes)
        return GuideDraft(
            UUID.fromString(n["draftId"].asString()),
            UUID.fromString(n["analysisId"].asString()),
            n["analysisRevision"].asLong(),
            n["analysisReviewRevision"].asLong(),
            n["basedOnContentRevision"].asLong(),
            n["draftRevision"].asLong(),
            GuideOutputMode.valueOf(n["mode"].asString()),
            n["body"].asString(),
            n["blocks"].toList().map {
                GuideDraftBlock(
                    it["id"].asString(),
                    it["actionId"].asString(),
                    it["text"].asString(),
                    it["cautions"].toList().map { value ->
                        value.asString()
                    },
                    GuideAnalysisSnapshotCodec.anchors(it["evidence"]),
                )
            },
            n["reviewed"].asBoolean(),
            Instant.parse(n["createdAt"].asString()),
        )
    }

    private companion object {
        val mapper = JsonMapper.builder().build()
    }
}

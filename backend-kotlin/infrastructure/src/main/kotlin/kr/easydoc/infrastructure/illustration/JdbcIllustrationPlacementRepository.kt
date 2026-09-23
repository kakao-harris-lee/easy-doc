package kr.easydoc.infrastructure.illustration

import kr.easydoc.application.illustration.IllustrationPlacementRepository
import kr.easydoc.application.illustration.StoredIllustrationPlacements
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/**
 * `illustration_placements` 저장소(ER-16). 사용자 경로 문장은 `JdbcReviewAssessmentRepository`
 * 와 같은 모양이다 — 소유·보존 술어를 SQL 자체에 건다. 회전 팔([lockEnvelope]·[rewriteEnvelope]·
 * [idsOlderThan])은 소유자를 받지 않는다(회전 배치에 「내 것」이 없다).
 */
class JdbcIllustrationPlacementRepository(private val jdbc: JdbcClient) : IllustrationPlacementRepository {
    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationPlacements? =
        jdbc
            .sql(
                """
                SELECT p.* FROM illustration_placements p
                JOIN conversions c ON c.id = p.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE p.conversion_id = :conversionId AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::row)
            .optional()
            .orElse(null)

    override fun replaceOwned(
        ownerId: UUID,
        conversionId: UUID,
        id: UUID,
        contentRevision: Long,
        payload: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO illustration_placements
                    (id, conversion_id, content_revision, payload_encrypted, encryption_scheme, key_version)
                SELECT :id, :conversionId, :contentRevision, :payload, :scheme, :keyVersion
                WHERE EXISTS (
                    SELECT 1 FROM conversions c
                    JOIN documents d ON d.id = c.document_id
                    WHERE c.id = :conversionId AND d.user_id = :ownerId
                      AND d.retention_expires_at > now()
                )
                ON CONFLICT (conversion_id) DO UPDATE
                SET content_revision = EXCLUDED.content_revision,
                    payload_encrypted = EXCLUDED.payload_encrypted,
                    encryption_scheme = EXCLUDED.encryption_scheme,
                    key_version = EXCLUDED.key_version,
                    updated_at = now()
                """.trimIndent(),
            ).param("id", id)
            .param("conversionId", conversionId)
            .param("contentRevision", contentRevision)
            .param("payload", payload.bytes)
            .param("scheme", payload.scheme)
            .param("keyVersion", payload.keyVersion)
            .param("ownerId", ownerId)
            .update() > 0

    override fun deleteOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                DELETE FROM illustration_placements
                WHERE conversion_id = :conversionId
                  AND conversion_id IN (
                      SELECT c.id FROM conversions c
                      JOIN documents d ON d.id = c.document_id
                      WHERE d.user_id = :ownerId AND d.retention_expires_at > now()
                  )
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .update() > 0

    override fun lockEnvelope(id: UUID): StoredIllustrationPlacements? =
        jdbc
            .sql("SELECT * FROM illustration_placements WHERE id = :id FOR NO KEY UPDATE")
            .param("id", id)
            .query(::row)
            .optional()
            .orElse(null)

    override fun rewriteEnvelope(
        expected: StoredIllustrationPlacements,
        payload: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE illustration_placements
                SET payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion,
                    updated_at = now()
                WHERE id = :id AND encryption_scheme = :expectedScheme AND key_version = :expectedKeyVersion
                  AND payload_encrypted = :expectedPayload
                """.trimIndent(),
            ).param("payload", payload.bytes)
            .param("scheme", payload.scheme)
            .param("keyVersion", payload.keyVersion)
            .param("id", expected.id)
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
                SELECT id FROM illustration_placements
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
    ): StoredIllustrationPlacements =
        StoredIllustrationPlacements(
            id = rs.getObject("id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            contentRevision = rs.getLong("content_revision"),
            payload =
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}

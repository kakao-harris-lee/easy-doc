package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetRepository
import kr.easydoc.application.illustration.generation.StoredGeneratedIllustrationAsset
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class JdbcGeneratedIllustrationAssetRepository(private val jdbc: JdbcClient) : GeneratedIllustrationAssetRepository {
    override fun insertAsset(
        job: StoredIllustrationGenerationJob,
        asset: StoredGeneratedIllustrationAsset,
    ): Boolean {
        require(asset.jobId == job.jobId && asset.conversionId == job.conversionId)
        require(asset.basedOnContentRevision == job.basedOnContentRevision)
        return jdbc
            .sql(INSERT_SQL)
            .param("id", asset.assetId)
            .param("jobId", asset.jobId)
            .param("conversionId", asset.conversionId)
            .param("ownerId", job.ownerId)
            .param("revision", asset.basedOnContentRevision)
            .param("suggestionResultId", job.suggestionResultId)
            .param("metadata", asset.metadata.bytes)
            .param("png", asset.png.bytes)
            .param("scheme", asset.metadata.scheme)
            .param("keyVersion", asset.metadata.keyVersion)
            .param("pngByteSize", asset.pngByteSize)
            .param("width", asset.width)
            .param("height", asset.height)
            .param("provenance", asset.provenance)
            .update() == 1
    }

    override fun listOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): List<StoredGeneratedIllustrationAsset> =
        jdbc
            .sql(LIST_SQL)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query(::mapAsset)
            .list()

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
    ): StoredGeneratedIllustrationAsset? =
        jdbc
            .sql(FIND_SQL)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .param("assetId", assetId)
            .query(::mapAsset)
            .optional()
            .orElse(null)

    override fun updateApplication(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        applied: Boolean,
    ): Boolean =
        jdbc
            .sql(UPDATE_APPLICATION_SQL)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .param("assetId", assetId)
            .param("expectedContentRevision", expectedContentRevision)
            .param("expectedAssetRevision", expectedAssetRevision)
            .param("applied", applied)
            .update() == 1

    private fun mapAsset(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): StoredGeneratedIllustrationAsset =
        StoredGeneratedIllustrationAsset(
            assetId = rs.getObject("id", UUID::class.java),
            jobId = rs.getObject("job_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            metadata =
                EncryptedContent(
                    rs.getBytes("metadata_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
            png =
                EncryptedContent(
                    rs.getBytes("png_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
            pngByteSize = rs.getInt("png_byte_size"),
            width = rs.getInt("width"),
            height = rs.getInt("height"),
            provenance = rs.getString("provenance"),
            assetRevision = rs.getLong("asset_revision"),
            applied = rs.getBoolean("applied"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private companion object {
        val COLUMNS =
            """
            a.id, a.job_id, a.conversion_id, a.based_on_content_revision,
            a.metadata_encrypted, a.png_encrypted, a.encryption_scheme, a.key_version,
            a.png_byte_size, a.width, a.height, a.provenance, a.asset_revision, a.applied, a.created_at
            """.trimIndent()

        val INSERT_SQL =
            """
            INSERT INTO generated_illustration_assets
                (id, job_id, conversion_id, based_on_content_revision,
                 metadata_encrypted, png_encrypted, encryption_scheme, key_version,
                 png_byte_size, width, height, provenance)
            SELECT :id, j.id, c.id, :revision, :metadata, :png, :scheme, :keyVersion,
                   :pngByteSize, :width, :height, :provenance
            FROM illustration_generation_jobs j
            JOIN conversions c ON c.id = j.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE j.id = :jobId AND j.owner_user_id = :ownerId
              AND j.document_id = d.id AND j.conversion_id = :conversionId
              AND j.based_on_content_revision = :revision
              AND j.suggestion_result_id = :suggestionResultId
              AND j.status IN ('running', 'succeeded')
              AND c.content_revision = :revision AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
              AND (SELECT r.id FROM illustration_suggestion_results r
                   WHERE r.conversion_id = c.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) = :suggestionResultId
            ON CONFLICT (job_id) DO NOTHING
            """.trimIndent()

        val LIST_SQL =
            """
            SELECT $COLUMNS
            FROM generated_illustration_assets a
            JOIN conversions c ON c.id = a.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE a.conversion_id = :conversionId AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            ORDER BY a.created_at DESC, a.id DESC
            """.trimIndent()

        val FIND_SQL =
            """
            SELECT $COLUMNS
            FROM generated_illustration_assets a
            JOIN conversions c ON c.id = a.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE a.id = :assetId AND a.conversion_id = :conversionId
              AND d.user_id = :ownerId AND d.retention_expires_at > now()
            """.trimIndent()

        val UPDATE_APPLICATION_SQL =
            """
            UPDATE generated_illustration_assets AS a
            SET applied = CAST(:applied AS boolean),
                applied_content_revision = CASE WHEN CAST(:applied AS boolean) THEN c.content_revision ELSE NULL END,
                asset_revision = a.asset_revision + 1,
                updated_at = now()
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE a.id = :assetId AND a.conversion_id = :conversionId
              AND c.id = a.conversion_id AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
              AND c.content_revision = :expectedContentRevision
              AND a.asset_revision = :expectedAssetRevision
              AND (CAST(:applied AS boolean) = false OR a.based_on_content_revision = :expectedContentRevision)
            """.trimIndent()
    }
}

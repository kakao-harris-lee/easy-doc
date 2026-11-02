package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class IllustrationGenerationKeyRotation(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    init {
        require(batchSize > 0)
    }

    fun runJobs(): Int = rotateFamily(JOB_IDS_SQL, ::rotateJob)

    fun runAssets(): Int = rotateFamily(ASSET_IDS_SQL, ::rotateAsset)

    private fun rotateFamily(
        idsSql: String,
        rotate: (UUID) -> Boolean,
    ): Int {
        var cursor = UUID(0L, 0L)
        var rotated = 0
        do {
            val ids =
                jdbc
                    .sql(idsSql)
                    .param("target", cipher.writeKeyVersion)
                    .param("cursor", cursor)
                    .param("limit", batchSize)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .list()
            ids.forEach { id -> if (transactions.execute { rotate(id) } == true) rotated++ }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    @Suppress("ReturnCount")
    private fun rotateJob(id: UUID): Boolean {
        val old =
            jdbc
                .sql(JOB_LOCK_SQL)
                .param("id", id)
                .query { rs, _ ->
                    EncryptedContent(
                        rs.getBytes("input_payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    )
                }.optional()
                .orElse(null) ?: return false
        if (old.keyVersion >= cipher.writeKeyVersion) return false
        val field = EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT
        val fresh = cipher.encryptBytes(cipher.decryptBytes(old, id, field), id, field)
        return jdbc
            .sql(JOB_UPDATE_SQL)
            .param("payload", fresh.bytes)
            .param("scheme", fresh.scheme)
            .param("keyVersion", fresh.keyVersion)
            .param("id", id)
            .param("expectedVersion", old.keyVersion)
            .update() == 1
    }

    @Suppress("ReturnCount")
    private fun rotateAsset(id: UUID): Boolean {
        val old =
            jdbc
                .sql(ASSET_LOCK_SQL)
                .param("id", id)
                .query { rs, _ ->
                    val scheme = rs.getString("encryption_scheme")
                    val version = rs.getInt("key_version")
                    Pair(
                        EncryptedContent(rs.getBytes("metadata_encrypted"), scheme, version),
                        EncryptedContent(rs.getBytes("png_encrypted"), scheme, version),
                    )
                }.optional()
                .orElse(null) ?: return false
        if (old.first.keyVersion >= cipher.writeKeyVersion) return false
        val metadataField = EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA
        val pngField = EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG
        val metadata = cipher.encryptBytes(cipher.decryptBytes(old.first, id, metadataField), id, metadataField)
        val png = cipher.encryptBytes(cipher.decryptBytes(old.second, id, pngField), id, pngField)
        return jdbc
            .sql(ASSET_UPDATE_SQL)
            .param("metadata", metadata.bytes)
            .param("png", png.bytes)
            .param("scheme", metadata.scheme)
            .param("keyVersion", metadata.keyVersion)
            .param("id", id)
            .param("expectedVersion", old.first.keyVersion)
            .update() == 1
    }

    private companion object {
        const val JOB_IDS_SQL =
            "SELECT id FROM illustration_generation_jobs WHERE key_version < :target AND id > :cursor " +
                "AND input_payload_encrypted IS NOT NULL ORDER BY id LIMIT :limit"
        const val JOB_LOCK_SQL =
            "SELECT input_payload_encrypted, encryption_scheme, key_version FROM illustration_generation_jobs " +
                "WHERE id = :id AND input_payload_encrypted IS NOT NULL FOR NO KEY UPDATE"
        val JOB_UPDATE_SQL =
            """
            UPDATE illustration_generation_jobs
            SET input_payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion
            WHERE id = :id AND key_version = :expectedVersion AND input_payload_encrypted IS NOT NULL
            """.trimIndent()

        const val ASSET_IDS_SQL =
            "SELECT id FROM generated_illustration_assets WHERE key_version < :target AND id > :cursor " +
                "ORDER BY id LIMIT :limit"
        const val ASSET_LOCK_SQL =
            "SELECT metadata_encrypted, png_encrypted, encryption_scheme, key_version " +
                "FROM generated_illustration_assets WHERE id = :id FOR NO KEY UPDATE"
        val ASSET_UPDATE_SQL =
            """
            UPDATE generated_illustration_assets
            SET metadata_encrypted = :metadata, png_encrypted = :png,
                encryption_scheme = :scheme, key_version = :keyVersion
            WHERE id = :id AND key_version = :expectedVersion
            """.trimIndent()
    }
}

package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Immutable recovery payloads still participate in key rotation before an old key can be retired. */
class GuideDraftApplyKeyRotation(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    init {
        require(batchSize > 0)
    }

    fun run(): Int = rotate(Family.PREVIOUS_BODIES) + rotate(Family.DRAFTS)

    private fun rotate(family: Family): Int {
        var cursor = UUID(0L, 0L)
        var rotated = 0
        do {
            val ids =
                jdbc
                    .sql(family.idsSql)
                    .param("target", cipher.writeKeyVersion)
                    .param("cursor", cursor)
                    .param("limit", batchSize)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .list()
            ids.forEach { id -> if (transactions.execute { rotateOne(family, id) } == true) rotated++ }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    private fun rotateOne(
        family: Family,
        id: UUID,
    ): Boolean {
        val old =
            jdbc
                .sql(family.lockSql)
                .param("id", id)
                .query { rs, _ ->
                    EncryptedContent(
                        rs.getBytes("payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    )
                }.optional()
                .orElse(null)
        if (old == null || old.keyVersion >= cipher.writeKeyVersion) return false
        val field = family.field
        val fresh = cipher.encryptBytes(cipher.decryptBytes(old, id, field), id, field)
        return jdbc
            .sql(family.updateSql)
            .param("id", id)
            .param("expectedVersion", old.keyVersion)
            .param("payload", fresh.bytes)
            .param("scheme", fresh.scheme)
            .param("version", fresh.keyVersion)
            .update() ==
            1
    }

    /** Static SQL lets the ownership/envelope census inspect both narrowly scoped maintenance families. */
    private enum class Family(
        val field: EncryptedField,
        val idsSql: String,
        val lockSql: String,
        val updateSql: String,
    ) {
        PREVIOUS_BODIES(
            EncryptedField.ACTION_GUIDE_PREVIOUS_BODY,
            """
            SELECT id FROM action_guide_body_snapshots
            WHERE key_version < :target AND id > :cursor ORDER BY id LIMIT :limit
            """.trimIndent(),
            "SELECT payload_encrypted, encryption_scheme, key_version FROM action_guide_body_snapshots " +
                "WHERE id = :id FOR NO KEY UPDATE",
            """
            UPDATE action_guide_body_snapshots SET payload_encrypted = :payload, encryption_scheme = :scheme,
                key_version = :version WHERE id = :id AND key_version = :expectedVersion
            """.trimIndent(),
        ),
        DRAFTS(
            EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD,
            "SELECT id FROM action_guide_drafts WHERE key_version < :target AND id > :cursor ORDER BY id LIMIT :limit",
            "SELECT payload_encrypted, encryption_scheme, key_version FROM action_guide_drafts " +
                "WHERE id = :id FOR NO KEY UPDATE",
            """
            UPDATE action_guide_drafts SET payload_encrypted = :payload, encryption_scheme = :scheme,
                key_version = :version WHERE id = :id AND key_version = :expectedVersion
            """.trimIndent(),
        ),
    }
}

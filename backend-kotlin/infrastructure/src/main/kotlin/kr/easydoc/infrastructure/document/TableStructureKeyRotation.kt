package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** `rotate-keys`에서 R4 표 구조 payload만 현재 키 세대로 다시 봉인한다. */
class TableStructureKeyRotation(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    init {
        require(batchSize > 0)
    }

    /** 회전된 행 수. 행 id와 payload는 결과에 싣지 않는다. */
    fun run(): Int {
        var cursor = ZERO_UUID
        var rotated = 0
        do {
            val ids =
                jdbc
                    .sql(IDS_SQL)
                    .param("target", cipher.writeKeyVersion)
                    .param("cursor", cursor)
                    .param("limit", batchSize)
                    .query { rs, _ -> rs.getObject("document_id", UUID::class.java) }
                    .list()
            ids.forEach { id -> if (transactions.execute { rotateOne(id) } == true) rotated++ }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    private fun rotateOne(documentId: UUID): Boolean {
        val current =
            jdbc
                .sql(LOCK_SQL)
                .param("documentId", documentId)
                .query { rs, _ ->
                    EncryptedContent(
                        bytes = rs.getBytes("payload_encrypted"),
                        scheme = rs.getString("encryption_scheme"),
                        keyVersion = rs.getInt("key_version"),
                    )
                }.optional()
                .orElse(null) ?: return false
        return if (current.scheme == cipher.writeScheme && current.keyVersion == cipher.writeKeyVersion) {
            false
        } else {
            rewrite(documentId, current)
        }
    }

    private fun rewrite(
        documentId: UUID,
        current: EncryptedContent,
    ): Boolean {
        val opened = cipher.decryptBytes(current, documentId, EncryptedField.DOCUMENT_TABLE_STRUCTURE)
        val fresh = cipher.encryptBytes(opened, documentId, EncryptedField.DOCUMENT_TABLE_STRUCTURE)
        return jdbc
            .sql(UPDATE_SQL)
            .param("payload", fresh.bytes)
            .param("scheme", fresh.scheme)
            .param("keyVersion", fresh.keyVersion)
            .param("documentId", documentId)
            .param("expectedScheme", current.scheme)
            .param("expectedKeyVersion", current.keyVersion)
            .param("expectedPayload", current.bytes)
            .update() == 1
    }

    private companion object {
        val ZERO_UUID: UUID = UUID(0L, 0L)
        const val IDS_SQL =
            "SELECT document_id FROM document_table_structures " +
                "WHERE key_version < :target AND document_id > :cursor " +
                "ORDER BY document_id LIMIT :limit"
        const val LOCK_SQL =
            "SELECT payload_encrypted, encryption_scheme, key_version " +
                "FROM document_table_structures WHERE document_id = :documentId FOR NO KEY UPDATE"
        val UPDATE_SQL =
            """
            UPDATE document_table_structures
            SET payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion
            WHERE document_id = :documentId
              AND encryption_scheme = :expectedScheme
              AND key_version = :expectedKeyVersion
              AND payload_encrypted IS NOT DISTINCT FROM CAST(:expectedPayload AS bytea)
            """.trimIndent()
    }
}

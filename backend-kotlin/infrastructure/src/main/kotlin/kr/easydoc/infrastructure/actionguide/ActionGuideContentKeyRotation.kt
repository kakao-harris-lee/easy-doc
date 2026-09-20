package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** `rotate-keys` 실행에서 후보/안내문을 현재 키 세대로 재봉인한다. 행별 잠금으로 편집과 직렬화한다. */
class ActionGuideContentKeyRotation(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    init {
        require(batchSize > 0)
    }

    fun run(): Pair<Int, Int> = rotateFamily(CANDIDATES) to rotateFamily(GUIDES)

    private fun rotateFamily(family: RotationFamily): Int {
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
            ids.forEach { id ->
                if (transactions.execute { rotateOne(family, id) } == true) rotated++
            }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    @Suppress("ReturnCount")
    private fun rotateOne(
        family: RotationFamily,
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
                .orElse(null) ?: return false
        if (old.keyVersion >= cipher.writeKeyVersion) return false
        val fresh = cipher.encryptBytes(cipher.decryptBytes(old, id, family.field), id, family.field)
        return jdbc
            .sql(family.updateSql)
            .param("payload", fresh.bytes)
            .param("scheme", fresh.scheme)
            .param("keyVersion", fresh.keyVersion)
            .param("id", id)
            .param("expectedVersion", old.keyVersion)
            .update() == 1
    }

    private data class RotationFamily(
        val field: EncryptedField,
        val idsSql: String,
        val lockSql: String,
        val updateSql: String,
    )

    private companion object {
        // 표 이름을 정적 SQL에 둬 소유권/봉투 열 가드가 회전 경로도 인구조사하게 한다.
        val CANDIDATES =
            RotationFamily(
                EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD,
                "SELECT id FROM action_guide_candidates WHERE key_version < :target AND id > :cursor " +
                    "ORDER BY id LIMIT :limit",
                "SELECT payload_encrypted, encryption_scheme, key_version FROM action_guide_candidates " +
                    "WHERE id = :id FOR NO KEY UPDATE",
                """
                UPDATE action_guide_candidates SET payload_encrypted = :payload,
                    encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id AND key_version = :expectedVersion
                """.trimIndent(),
            )

        val GUIDES =
            RotationFamily(
                EncryptedField.ACTION_GUIDE_PAYLOAD,
                "SELECT id FROM action_guides WHERE key_version < :target AND id > :cursor " +
                    "ORDER BY id LIMIT :limit",
                "SELECT payload_encrypted, encryption_scheme, key_version FROM action_guides " +
                    "WHERE id = :id FOR NO KEY UPDATE",
                """
                UPDATE action_guides SET payload_encrypted = :payload,
                    encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id AND key_version = :expectedVersion
                """.trimIndent(),
            )
    }
}

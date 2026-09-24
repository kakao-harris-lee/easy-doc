package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * `rotate-keys` 실행에서 그림 제안 결과를 현재 키 세대로 재봉인한다
 * (`ActionGuideContentKeyRotation` 과 같은 커서·행 잠금·CAS 구조).
 *
 * 회전 경로가 없는 봉인 열은 옛 세대를 설정에서 내리는 순간 영원히 열리지 않는다 — AAD 에
 * `key_version` 이 실리기 때문이다.
 */
class IllustrationSuggestionResultKeyRotation(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    init {
        require(batchSize > 0)
    }

    fun run(): Int {
        var cursor = UUID(0L, 0L)
        var rotated = 0
        do {
            val ids =
                jdbc
                    .sql(IDS_SQL)
                    .param("target", cipher.writeKeyVersion)
                    .param("cursor", cursor)
                    .param("limit", batchSize)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                    .list()
            ids.forEach { id ->
                if (transactions.execute { rotateOne(id) } == true) rotated++
            }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    @Suppress("ReturnCount")
    private fun rotateOne(id: UUID): Boolean {
        val old =
            jdbc
                .sql(LOCK_SQL)
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
        val fresh = cipher.encryptBytes(cipher.decryptBytes(old, id, FIELD), id, FIELD)
        return jdbc
            .sql(UPDATE_SQL)
            .param("payload", fresh.bytes)
            .param("scheme", fresh.scheme)
            .param("keyVersion", fresh.keyVersion)
            .param("id", id)
            .param("expectedVersion", old.keyVersion)
            .update() == 1
    }

    private companion object {
        val FIELD = EncryptedField.ILLUSTRATION_SUGGESTION_RESULT

        // 표 이름을 정적 SQL에 둬 소유권/봉투 열 가드가 회전 경로도 인구조사하게 한다.
        const val IDS_SQL =
            "SELECT id FROM illustration_suggestion_results WHERE key_version < :target AND id > :cursor " +
                "ORDER BY id LIMIT :limit"

        const val LOCK_SQL =
            "SELECT payload_encrypted, encryption_scheme, key_version FROM illustration_suggestion_results " +
                "WHERE id = :id FOR NO KEY UPDATE"

        val UPDATE_SQL =
            """
            UPDATE illustration_suggestion_results SET payload_encrypted = :payload,
                encryption_scheme = :scheme, key_version = :keyVersion
            WHERE id = :id AND key_version = :expectedVersion
            """.trimIndent()
    }
}

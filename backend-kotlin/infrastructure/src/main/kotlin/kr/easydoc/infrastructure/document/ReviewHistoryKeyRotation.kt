package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Re-seals R5 snapshots through the same repository envelope operations verified by storage tests. */
class ReviewHistoryKeyRotation(
    jdbc: JdbcClient,
    private val cipher: ContentCipher,
    private val transactions: TransactionTemplate,
    private val batchSize: Int,
) {
    private val repository = JdbcReviewHistoryRepository(jdbc)

    init {
        require(batchSize > 0)
    }

    fun run(): Int {
        var cursor = UUID(0L, 0L)
        var rotated = 0
        do {
            val ids = repository.snapshotIdsOlderThan(cipher.writeKeyVersion, cursor, batchSize)
            ids.forEach { id -> if (transactions.execute { rotateOne(id) } == true) rotated++ }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == batchSize)
        return rotated
    }

    private fun rotateOne(id: UUID): Boolean {
        val current = repository.lockSnapshot(id) ?: return false
        return if (
            current.payload.scheme == cipher.writeScheme &&
            current.payload.keyVersion == cipher.writeKeyVersion
        ) {
            false
        } else {
            val plain = cipher.decryptBytes(current.payload, id, EncryptedField.REVIEW_HISTORY_SNAPSHOT)
            val fresh = cipher.encryptBytes(plain, id, EncryptedField.REVIEW_HISTORY_SNAPSHOT)
            repository.rewriteSnapshotEnvelope(current, fresh)
        }
    }
}

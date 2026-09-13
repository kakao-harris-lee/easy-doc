package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminFeedbackItem
import kr.easydoc.application.admin.AdminFeedbackPage
import kr.easydoc.application.admin.AdminFeedbackQuery
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.DecryptionFailedException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 문서 보존 만료 뒤에도 의견은 조회한다. 문서 본문은 읽지 않는다. */
open class JdbcAdminFeedbackQuery(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : AdminFeedbackQuery {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun list(
        page: Int,
        size: Int,
    ): AdminFeedbackPage {
        val total = jdbc.sql("SELECT COUNT(*) FROM conversion_feedback").query(Long::class.java).single()
        val items =
            jdbc
                .sql(
                    """
                    SELECT f.conversion_id, f.user_id, u.email AS owner_email, f.publish_intent,
                           f.quality_score, f.minutes_spent, f.comment_encrypted,
                           f.encryption_scheme, f.key_version, f.submitted_at
                    FROM conversion_feedback f
                    LEFT JOIN users u ON u.id = f.user_id
                    ORDER BY f.submitted_at DESC, f.conversion_id DESC
                    LIMIT :size OFFSET :offset
                    """.trimIndent(),
                ).param("size", size)
                .param("offset", (page - 1) * size)
                .query { rs, _ ->
                    val id = rs.getObject("conversion_id", UUID::class.java)
                    var commentUnreadable = false
                    val comment =
                        rs.getBytes("comment_encrypted")?.let { bytes ->
                            try {
                                cipher.decrypt(
                                    EncryptedContent(
                                        bytes,
                                        rs.getString("encryption_scheme"),
                                        rs.getInt("key_version"),
                                    ),
                                    id,
                                    EncryptedField.CONVERSION_FEEDBACK_COMMENT,
                                )
                            } catch (_: DecryptionFailedException) {
                                commentUnreadable = true
                                null
                            }
                        }
                    AdminFeedbackItem(
                        conversionId = id,
                        userId = rs.getObject("user_id", UUID::class.java),
                        ownerEmail = rs.getString("owner_email"),
                        publishIntent = rs.getString("publish_intent"),
                        qualityScore = rs.getInt("quality_score"),
                        minutesSpent = rs.getInt("minutes_spent"),
                        comment = comment,
                        commentUnreadable = commentUnreadable,
                        submittedAt = rs.getTimestamp("submitted_at").toInstant(),
                    )
                }.list()
        return AdminFeedbackPage(items, total)
    }
}

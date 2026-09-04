package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionNotificationStore
import kr.easydoc.application.conversion.ConversionNotificationTarget
import kr.easydoc.application.mail.EmailAddress
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * `conversions.notified_at`([V5__conversion_notified_at.sql])로 완료 메일 발송 여부를
 * 추적한다. 스키마는 `V1__initial_schema.sql`(documents·users)·V5(notified_at)가 정한다.
 */
class JdbcConversionNotificationStore(private val jdbc: JdbcClient) : ConversionNotificationStore {
    override fun findTarget(conversionId: UUID): ConversionNotificationTarget? =
        jdbc
            .sql(
                """
                SELECT d.title, u.email, c.notified_at IS NOT NULL AS already_notified
                FROM conversions c
                JOIN documents d ON d.id = c.document_id
                JOIN users u ON u.id = d.user_id
                WHERE c.id = :id
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ ->
                ConversionNotificationTarget(
                    documentTitle = rs.getString("title"),
                    ownerEmail = EmailAddress.of(rs.getString("email")),
                    alreadyNotified = rs.getBoolean("already_notified"),
                )
            }.optional()
            .orElse(null)

    /** 원자적 CAS — 아직 알림을 보내지 않은 행만 표시한다. 갱신됐으면 `true`. */
    override fun markNotified(conversionId: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions SET notified_at = now()
                WHERE id = :id AND notified_at IS NULL
                """.trimIndent(),
            ).param("id", conversionId)
            .update() > 0
}

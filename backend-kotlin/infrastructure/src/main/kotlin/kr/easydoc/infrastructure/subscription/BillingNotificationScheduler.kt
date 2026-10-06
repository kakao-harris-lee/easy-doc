package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/** SMTP has no idempotency API. Uncertain sends stay for review instead of being blindly sent twice. */
@Component
@Profile("worker & !migrate")
class BillingNotificationScheduler(
    private val jdbc: JdbcClient,
    private val accounts: CreditAccountRepository,
    private val users: UserRepository,
    private val sender: MailSender,
    private val properties: PaymentProperties,
) {
    @Scheduled(fixedDelayString = "\${easydoc.payment.notification-delay-ms:60000}")
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        if (properties.provider !in setOf("toss_test", "toss_live")) return
        if (properties.autoChargeEnabled) queueUpcoming()
        jdbc
            .sql(
                "UPDATE billing_notifications SET state='manual_review' " +
                    "WHERE environment=:environment AND state='sending' " +
                    "AND attempted_at < now()-interval '10 minutes'",
            ).param("environment", properties.provider)
            .update()
        repeat(BATCH_SIZE) {
            val job = claim() ?: return
            try {
                val owner = accounts.ownerOf(job.workspace)
                val user = owner?.let(users::findById)
                val state =
                    if (job.environment != properties.provider) {
                        "manual_review"
                    } else if (user == null) {
                        "failed"
                    } else {
                        when (
                            sender.send(
                                notification(user.email, job),
                            )
                        ) {
                            is MailDelivery.Sent -> "sent"
                            is MailDelivery.Rejected -> "failed"
                        }
                    }
                finish(job.id, state)
            } catch (_: RuntimeException) {
                finish(job.id, "manual_review")
            }
        }
    }

    private fun queueUpcoming() {
        jdbc
            .sql(
                """
                INSERT INTO billing_notifications(workspace_id,event_key,event_type,environment)
                SELECT workspace_id,'upcoming:'||workspace_id||':'||cycle_ends_at,'upcoming',provider
                FROM workspace_subscriptions WHERE provider=:provider AND status='active'
                  AND cycle_ends_at > now() AND cycle_ends_at <= now()+interval '7 days'
                ON CONFLICT (event_key) DO NOTHING
                """.trimIndent(),
            ).param("provider", properties.provider)
            .update()
    }

    private fun claim(): Job? =
        jdbc
            .sql(
                """
                UPDATE billing_notifications SET state='sending',attempted_at=now()
                WHERE id=(SELECT id FROM billing_notifications WHERE state='pending' AND environment=:environment
                          ORDER BY id
                          FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,workspace_id,event_type,environment
                """.trimIndent(),
            ).param("environment", properties.provider)
            .query {
                row,
                _,
                ->
                Job(
                    row.getLong("id"),
                    row.getObject("workspace_id", UUID::class.java),
                    row.getString("event_type"),
                    row.getString("environment"),
                )
            }.optional()
            .orElse(null)

    private fun finish(
        id: Long,
        state: String,
    ) {
        jdbc
            .sql(
                "UPDATE billing_notifications SET state=:state,sent_at=CASE WHEN :state='sent' THEN now() END " +
                    "WHERE id=:id AND state='sending'",
            ).param("state", state)
            .param("id", id)
            .update()
    }

    private fun message(event: String): String =
        when (event) {
            "upcoming" -> {
                "다음 정기결제가 7일 이내에 예정되어 있습니다. 이용 현황에서 금액과 결제일을 확인하고 갱신을 중단할 수 있습니다."
            }

            "charge_paid", "renewal_paid" -> {
                "정기결제가 승인되었습니다. 이용 현황에서 결제 금액, 이용 기간과 크레딧을 확인해 주세요."
            }

            "refund_paid" -> {
                "환불이 승인되었습니다. 이용 현황에서 환불 금액을 확인해 주세요. 크레딧 조정은 관리자 처리 상태를 확인해 주세요."
            }

            "charge_failed", "renewal_failed" -> {
                "카드 결제에 실패했습니다. 이용 현황에서 재시도 예정 시각과 카드 재등록 안내를 확인해 주세요. " +
                    "승인 전에는 새 크레딧이 지급되지 않습니다."
            }

            "canceling" -> {
                "다음 갱신이 중단되었습니다. 이미 승인된 이용 기간은 유지됩니다."
            }

            "expired", "suspended", "past_due" -> {
                "구독 이용 기간이 종료되었거나 갱신이 정지되었습니다. 이용 현황에서 상태를 확인해 주세요."
            }

            else -> {
                "결제 처리에 확인이 필요합니다. 새 결제를 시작하지 말고 이용 현황을 확인하거나 관리자에게 문의해 주세요."
            }
        }

    private fun notification(
        email: String,
        job: Job,
    ): OutboundMail {
        val test = job.environment == "toss_test"
        val subject = if (test) "[테스트 결제] EASY-DOC 정기결제 안내" else "EASY-DOC 정기결제 안내"
        val prefix = if (test) "테스트 환경의 결제 알림입니다. 실제 요금은 청구되지 않습니다.\n\n" else ""
        return OutboundMail(EmailAddress.of(email), subject, prefix + message(job.event))
    }

    private data class Job(
        val id: Long,
        val workspace: UUID,
        val event: String,
        val environment: String,
    )

    private companion object {
        const val BATCH_SIZE = 50
    }
}

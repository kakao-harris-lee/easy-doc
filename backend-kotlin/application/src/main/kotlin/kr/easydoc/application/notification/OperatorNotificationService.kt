package kr.easydoc.application.notification

import kr.easydoc.application.auth.UserRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class OperatorNotificationService(
    private val enabled: Boolean,
    private val users: UserRepository,
    private val port: OperatorNotificationPort,
) : OperatorNotifications {
    private val log = LoggerFactory.getLogger(OperatorNotificationService::class.java)

    override fun notify(event: OperatorNotificationEvent) {
        if (!enabled) return
        try {
            val email =
                when (event) {
                    is OperatorNotificationEvent.MemberSignedUp -> {
                        event.email
                    }

                    is OperatorNotificationEvent.MemberDeleted -> {
                        event.email
                    }

                    is OperatorNotificationEvent.Payment -> {
                        users.findById(event.userId)?.email
                            ?: error("operator notification owner missing")
                    }
                }
            port.send(render(event, email))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            when (event) {
                is OperatorNotificationEvent.Payment -> {
                    log.warn(
                        "운영 알림 발송 실패: eventType={} orderId={} 예외={}",
                        event.eventType,
                        event.orderId,
                        failure::class.java.simpleName,
                    )
                }

                else -> {
                    log.warn(
                        "운영 알림 발송 실패: eventType={} userId={} 예외={}",
                        event.eventType,
                        event.userId,
                        failure::class.java.simpleName,
                    )
                }
            }
        }
    }

    private fun render(
        event: OperatorNotificationEvent,
        email: String,
    ): String {
        val title =
            when (event) {
                is OperatorNotificationEvent.MemberSignedUp -> "회원 가입"
                is OperatorNotificationEvent.MemberDeleted -> "회원 탈퇴"
                is OperatorNotificationEvent.Payment -> title(event.kind)
            }
        // 실결제 환경으로 확인된 값이 아니면 모두 테스트로 표시한다(fail-closed).
        val testPrefix =
            if (event is OperatorNotificationEvent.Payment && event.environment != LIVE_ENVIRONMENT) {
                "[TEST] "
            } else {
                ""
            }
        return buildString {
            appendLine("$testPrefix$title")
            appendLine("event: ${event.eventType}")
            appendLine("email: ${maskEmail(email)}")
            appendLine("userId: ${event.userId}")
            appendLine("occurredAt: ${SEOUL_FORMATTER.format(event.occurredAt)}")
            if (event is OperatorNotificationEvent.MemberSignedUp) appendLine("route: ${event.route}")
            if (event is OperatorNotificationEvent.Payment) {
                appendLine("planId: ${event.planId}")
                appendLine("amount: ${event.amountKrw} KRW")
                appendLine("orderId: ${event.orderId}")
                appendLine("workspaceId: ${event.workspaceId}")
                append("environment: ${event.environment}")
            }
        }
    }

    private fun maskEmail(email: String): String {
        val at = email.lastIndexOf('@')
        require(at > 0 && at < email.lastIndex) { "invalid notification email" }
        return email.first() + "***" + email.substring(at)
    }

    private fun title(kind: OperatorPaymentKind): String =
        when (kind) {
            OperatorPaymentKind.PAYMENT_APPROVED -> "결제 승인"
            OperatorPaymentKind.RENEWAL_APPROVED -> "정기 결제 승인"
            OperatorPaymentKind.PAYMENT_FAILED -> "결제 실패"
            OperatorPaymentKind.RENEWAL_FAILED -> "정기 결제 실패"
            OperatorPaymentKind.REFUND_APPROVED -> "환불 승인"
            OperatorPaymentKind.SUBSCRIPTION_CANCELING -> "구독 해지 예정"
            OperatorPaymentKind.SUBSCRIPTION_EXPIRED -> "구독 만료"
            OperatorPaymentKind.SUBSCRIPTION_PAST_DUE -> "구독 연체"
            OperatorPaymentKind.SUBSCRIPTION_SUSPENDED -> "구독 정지"
            OperatorPaymentKind.PAYMENT_MANUAL_REVIEW -> "결제 수동 확인 필요"
        }

    private companion object {
        // 결제 환경 식별자는 주문·세션 행에 저장되는 고정 값이다(`toss_live`/`toss_test`/`stub`).
        const val LIVE_ENVIRONMENT = "toss_live"

        val SEOUL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_ZONED_DATE_TIME.withZone(ZoneId.of("Asia/Seoul"))
    }
}

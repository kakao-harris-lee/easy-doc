package kr.easydoc.application.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class OperatorNotificationServiceTest {
    @Test
    fun `회원과 결제 메시지는 마스킹 식별자 서울 시각과 테스트 표시를 지킨다`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val workspaceId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val orderId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val occurredAt = Instant.parse("2026-03-05T00:00:00Z")
        val sent = mutableListOf<String>()
        val users = NotificationUserRepository(User(userId, "hello@example.test", occurredAt, hasPassword = true))
        val service = OperatorNotificationService(true, users) { sent += it }

        service.notify(OperatorNotificationEvent.MemberSignedUp(userId, occurredAt, "h@example.test", "email"))
        service.notify(
            OperatorNotificationEvent.Payment(
                userId,
                occurredAt,
                OperatorPaymentKind.PAYMENT_APPROVED,
                workspaceId,
                orderId,
                "start",
                99000,
                "toss_test",
            ),
        )
        service.notify(
            OperatorNotificationEvent.Payment(
                userId,
                occurredAt,
                OperatorPaymentKind.PAYMENT_APPROVED,
                workspaceId,
                orderId,
                "start",
                99000,
                "toss_live",
            ),
        )

        assertThat(sent[0]).contains(
            "회원 가입",
            "event: member_signed_up",
            "email: h***@example.test",
            "userId: $userId",
            "occurredAt: 2026-03-05T09:00:00+09:00[Asia/Seoul]",
            "route: email",
        )
        assertThat(sent[1])
            .contains(
                "[TEST] 결제 승인",
                "email: h***@example.test",
                "userId: $userId",
                "planId: start",
                "amount: 99000 KRW",
                "orderId: $orderId",
                "workspaceId: $workspaceId",
                "environment: toss_test",
            ).doesNotContain("hello@example.test")
        assertThat(sent[2]).startsWith("결제 승인").doesNotContain("[TEST]")
    }

    @Test
    fun `실결제 환경이 아니면 알 수 없는 환경 값도 테스트로 표시한다`() {
        val userId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val occurredAt = Instant.parse("2026-03-05T00:00:00Z")
        val sent = mutableListOf<String>()
        val users = NotificationUserRepository(User(userId, "hello@example.test", occurredAt, hasPassword = true))
        val service = OperatorNotificationService(true, users) { sent += it }

        listOf("toss_live", "toss_test", "toss-test", "").forEach { environment ->
            service.notify(
                OperatorNotificationEvent.Payment(
                    userId,
                    occurredAt,
                    OperatorPaymentKind.PAYMENT_APPROVED,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "start",
                    99000,
                    environment,
                ),
            )
        }

        assertThat(sent[0]).startsWith("결제 승인").doesNotContain("[TEST]")
        assertThat(sent[1]).startsWith("[TEST] 결제 승인")
        assertThat(sent[2]).startsWith("[TEST] 결제 승인")
        assertThat(sent[3]).startsWith("[TEST] 결제 승인")
    }

    @Test
    fun `비활성화면 사용자 조회 메시지 생성 발송과 로그를 모두 생략한다`() {
        val users = NotificationUserRepository()
        var sends = 0
        val service = OperatorNotificationService(false, users) { sends++ }
        val malformed =
            OperatorNotificationEvent.MemberSignedUp(
                UUID.randomUUID(),
                Instant.EPOCH,
                "not-an-email",
                "email",
            )

        val logs = captureLogs { service.notify(malformed) }

        assertThat(users.findByIdCount).isZero()
        assertThat(sends).isZero()
        assertThat(logs).isEmpty()
    }

    @Test
    fun `조회와 발송 실패는 삼키고 정제된 WARN 한 줄만 남긴다`() {
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val users = NotificationUserRepository()
        val secretMessage = "body-token-chat-full-email@example.test"
        val service = OperatorNotificationService(true, users) { throw IllegalStateException(secretMessage) }
        val event =
            OperatorNotificationEvent.Payment(
                userId,
                Instant.EPOCH,
                OperatorPaymentKind.RENEWAL_FAILED,
                UUID.randomUUID(),
                orderId,
                "start",
                99000,
                "toss_live",
            )

        val missingLogs = captureLogs { service.notify(event) }
        users.user = User(userId, "full-email@example.test", Instant.EPOCH, hasPassword = true)
        val sendLogs = captureLogs { service.notify(event) }

        assertThat(missingLogs).singleElement().satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains("renewal_failed", orderId.toString(), "IllegalStateException")
            assertThat(it.throwableProxy).isNull()
        })
        val rendered = sendLogs.joinToString("\n") { it.formattedMessage }
        assertThat(rendered).contains("IllegalStateException")
        assertThat(rendered).doesNotContain(secretMessage, "full-email@example.test", "chat", "token")
    }

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(OperatorNotificationService::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.TRACE
        try {
            block()
        } finally {
            logger.level = previousLevel
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }
}

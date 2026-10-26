package kr.easydoc.infrastructure.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.notification.OperatorNotificationEvent
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.application.notification.OperatorPaymentKind
import kr.easydoc.application.subscription.PaymentEnvironments
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncOperatorNotificationsTest {
    @Test
    fun `위임이 막혀 있어도 호출자는 즉시 돌아온다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate =
            OperatorNotifications {
                entered.countDown()
                release.awaitOrFail()
            }
        val notifications = AsyncOperatorNotifications(delegate, QUEUE_CAPACITY, SHUTDOWN_TIMEOUT)

        try {
            notifications.notify(signUp("first@example.test"))
            assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

            // 위임이 아직 release 를 기다리는 동안 호출자가 여기까지 왔다는 것이 비동기성의 증거다.
            notifications.notify(signUp("second@example.test"))

            assertThat(release.count).isEqualTo(1)
        } finally {
            release.countDown()
            notifications.close()
        }
    }

    @Test
    fun `큐가 가득 차면 던지지 않고 버리며 추적용 식별자만 남긴다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = ConcurrentLinkedQueue<OperatorNotificationEvent>()
        val delegate =
            OperatorNotifications { event ->
                entered.countDown()
                release.awaitOrFail()
                received += event
            }
        val notifications = AsyncOperatorNotifications(delegate, 1, SHUTDOWN_TIMEOUT)
        val running = signUp("running@example.test")
        val queued = signUp("queued@example.test")
        // 버려지는 쪽을 결제 실패로 둔다 — 추적이 가장 급한 종류라 주문 번호가 로그에 남아야 한다.
        val dropped = refundFailed()

        val logs =
            captureLogs {
                notifications.notify(running)
                assertThat(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                notifications.notify(queued)
                notifications.notify(dropped)
            }
        release.countDown()
        notifications.close()

        assertThat(received).containsExactly(running, queued)
        assertThat(logs).singleElement().satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains("refund_failed", dropped.orderId.toString())
            assertThat(it.formattedMessage).doesNotContain("@")
            assertThat(it.throwableProxy).isNull()
        })
    }

    @Test
    fun `위임 예외는 호출자로 번지지 않고 정제된 WARN 한 줄만 남긴다`() {
        val secretMessage = "body-token-chat-full-email@example.test"
        val delivered = CountDownLatch(1)
        val delegate =
            OperatorNotifications {
                delivered.countDown()
                error(secretMessage)
            }
        val notifications = AsyncOperatorNotifications(delegate, QUEUE_CAPACITY, SHUTDOWN_TIMEOUT)

        val logs =
            captureLogs {
                notifications.notify(signUp("boom@example.test"))
                assertThat(delivered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
                notifications.close()
            }

        assertThat(logs).singleElement().satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains("member_signed_up", "IllegalStateException")
            assertThat(it.formattedMessage).doesNotContain(secretMessage, "boom@example.test")
            assertThat(it.throwableProxy).isNull()
        })
    }

    @Test
    fun `종료는 큐에 남은 이벤트를 비우고 그 뒤 제출은 WARN 한 줄만 남기고 버린다`() {
        val gate = CountDownLatch(1)
        val received = ConcurrentLinkedQueue<OperatorNotificationEvent>()
        val delegate =
            OperatorNotifications { event ->
                gate.awaitOrFail()
                received += event
            }
        val notifications = AsyncOperatorNotifications(delegate, QUEUE_CAPACITY, SHUTDOWN_TIMEOUT)
        val queued = listOf(signUp("one@example.test"), signUp("two@example.test"), signUp("three@example.test"))

        val logs =
            captureLogs {
                queued.forEach(notifications::notify)
                gate.countDown()
                notifications.close()

                assertThat(received).containsExactlyElementsOf(queued)

                notifications.notify(signUp("after-close@example.test"))
            }

        assertThat(received).hasSameSizeAs(queued)
        assertThat(logs).singleElement().satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains("member_signed_up")
            assertThat(it.formattedMessage).doesNotContain("after-close@example.test")
            assertThat(it.throwableProxy).isNull()
        })
    }

    private fun signUp(email: String): OperatorNotificationEvent =
        OperatorNotificationEvent.MemberSignedUp(UUID.randomUUID(), Instant.EPOCH, email, "email")

    private fun refundFailed(): OperatorNotificationEvent.Payment =
        OperatorNotificationEvent.Payment(
            UUID.randomUUID(),
            Instant.EPOCH,
            OperatorPaymentKind.REFUND_FAILED,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "start",
            99000,
            PaymentEnvironments.TOSS_TEST,
        )

    private fun CountDownLatch.awaitOrFail() {
        check(await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "대기 시간을 넘겼다" }
    }

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(AsyncOperatorNotifications::class.java) as Logger
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

    private companion object {
        const val QUEUE_CAPACITY = 8
        const val AWAIT_SECONDS = 5L

        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

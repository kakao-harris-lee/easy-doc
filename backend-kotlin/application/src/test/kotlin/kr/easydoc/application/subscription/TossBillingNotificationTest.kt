package kr.easydoc.application.subscription

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.notification.NotificationUserRepository
import kr.easydoc.application.notification.OperatorNotificationEvent
import kr.easydoc.application.notification.OperatorNotificationService
import kr.easydoc.application.notification.OperatorPaymentKind
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/** 운영자 알림 — 발송 시점, 중복 억제, 로그에 남기지 않는 값. */
class TossBillingNotificationTest {
    @Test
    fun `scheduled renewal creation sends no operator notification`() {
        val f = TossBillingFixture()
        f.store.orders.clear()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")

        f.service.runDue()

        assertThat(
            f.store.orders.values
                .single()
                .kind,
        ).isEqualTo("renewal")
        assertThat(f.gateway.charges).isZero()
        assertThat(f.gateway.lookups).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `release only retry sends no operator notification`() {
        val f = TossBillingFixture()
        val nextAttempt = f.now.plus(Duration.ofHours(1))
        f.store.orders[f.order.id] = f.order.copy(status = "scheduled", nextAttemptAt = nextAttempt)

        f.service.process(f.order.id)

        assertThat(f.store.releases).containsExactly(f.order.id to nextAttempt)
        assertThat(f.gateway.charges).isZero()
        assertThat(f.gateway.lookups).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `charge 승인은 커밋 후 한 번이고 재처리는 미발송이다`() {
        val f = TossBillingFixture()
        f.gateway.payment = f.payment(f.now)

        f.service.process(f.order.id)
        f.service.process(f.order.id)
        f.service.recheck(f.order.id)

        val event = f.notifications.events.single() as OperatorNotificationEvent.Payment
        assertThat(event.kind).isEqualTo(OperatorPaymentKind.PAYMENT_APPROVED)
        assertThat(event.userId).isEqualTo(f.owner)
        assertThat(event.orderId).isEqualTo(f.order.id)
        assertThat(event.workspaceId).isEqualTo(f.workspace)
        assertThat(event.planId).isEqualTo("start")
        assertThat(event.amountKrw).isEqualTo(99000)
        assertThat(event.environment).isEqualTo(PaymentEnvironments.TOSS_TEST)
    }

    @Test
    fun `서로 다른 renewal 재시도 주문 실패는 각각 알린다`() {
        val f = TossBillingFixture()
        f.gateway.decline = true
        f.store.orders[f.order.id] = f.order.copy(kind = "renewal")
        f.service.process(f.order.id)
        val next =
            f.store.orders.values
                .single { it.status == "scheduled" }
        f.store.orders[next.id] = next.copy(nextAttemptAt = f.now)

        f.service.process(next.id)

        val events = f.notifications.events.filterIsInstance<OperatorNotificationEvent.Payment>()
        assertThat(events.map { it.kind }).containsExactly(
            OperatorPaymentKind.RENEWAL_FAILED,
            OperatorPaymentKind.RENEWAL_FAILED,
        )
        assertThat(events.map { it.orderId }).doesNotHaveDuplicates()
    }

    @Test
    fun `환불 거절은 한 번만 알리고 webhook 재전달에도 중복되지 않는다`() {
        val f = TossBillingFixture()
        val originalPayment = f.payment(f.now)
        val original = f.order.copy(status = "paid", payment = originalPayment)
        val operation =
            BillingOrder(
                UUID.randomUUID(),
                f.workspace,
                original.planId,
                1000,
                f.now,
                original.cycleEndsAt,
                kind = "refund",
                originalId = original.id,
                previousRemaining = 99000,
            )
        f.store.orders[original.id] = original
        f.store.orders[operation.id] = operation
        f.gateway.payment = originalPayment
        f.gateway.refundDeclines = true

        f.service.process(operation.id)
        f.service.process(operation.id)

        val event = f.notifications.events.single() as OperatorNotificationEvent.Payment
        assertThat(event.kind).isEqualTo(OperatorPaymentKind.REFUND_FAILED)
        assertThat(event.orderId).isEqualTo(operation.id)
        assertThat(event.amountKrw).isEqualTo(1000)
        assertThat(f.store.orders[operation.id]?.status).isEqualTo("failed")
        assertThat(f.store.orders[original.id]?.status).isEqualTo("paid")
    }

    @Test
    fun `환불은 원 결제가 아니라 환불 작업 주문의 실제 paid 전이를 알린다`() {
        val f = TossBillingFixture()
        val originalPayment = f.payment(f.now)
        val original = f.order.copy(status = "paid", payment = originalPayment)
        val operation =
            BillingOrder(
                UUID.randomUUID(),
                f.workspace,
                original.planId,
                1000,
                f.now,
                original.cycleEndsAt,
                kind = "refund",
                originalId = original.id,
                previousRemaining = 99000,
            )
        f.store.orders[original.id] = original
        f.store.orders[operation.id] = operation
        f.gateway.payment = originalPayment.copy(status = "PARTIAL_CANCELED", remainingAmount = 98000)

        f.service.recheck(operation.id)
        f.service.recheck(operation.id)

        val event = f.notifications.events.single() as OperatorNotificationEvent.Payment
        assertThat(event.kind).isEqualTo(OperatorPaymentKind.REFUND_APPROVED)
        assertThat(event.orderId).isEqualTo(operation.id)
        assertThat(event.amountKrw).isEqualTo(1000)
    }

    @Test
    fun `manual review notifies once and notification failure preserves payment`() {
        val review = TossBillingFixture()
        review.store.orders[review.order.id] = review.order.copy(createdAt = review.now.minus(Duration.ofDays(15)))
        review.service.process(review.order.id)
        review.service.process(review.order.id)
        assertThat(review.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
            .containsExactly(OperatorPaymentKind.PAYMENT_MANUAL_REVIEW)

        val approved = TossBillingFixture()
        approved.gateway.payment = approved.payment(approved.now)
        val bestEffort =
            OperatorNotificationService(
                NotificationUserRepository(
                    User(approved.owner, "billing@example.test", approved.now, hasPassword = true),
                ),
            ) { throw IllegalStateException("sensitive provider failure") }
        approved.serviceWith(bestEffort).process(approved.order.id)
        assertThat(approved.store.orders[approved.order.id]?.status).isEqualTo("paid")
    }

    @Test
    fun `canceling과 expired는 실제 전이만 알리고 기준 주문을 유지한다`() {
        val f = TossBillingFixture()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
        f.store.orders[f.order.id] = f.order.copy(status = "paid")

        f.service.stopRenewal(f.owner, f.workspace)
        f.service.stopRenewal(f.owner, f.workspace)
        f.service.runDue()

        val events = f.notifications.events.filterIsInstance<OperatorNotificationEvent.Payment>()
        assertThat(events.map { it.kind }).containsExactly(
            OperatorPaymentKind.SUBSCRIPTION_CANCELING,
            OperatorPaymentKind.SUBSCRIPTION_EXPIRED,
        )
        assertThat(events.map { it.orderId }).containsOnly(f.order.id)
        assertThat(events.map { it.planId }).containsOnly("start")
        assertThat(events.map { it.amountKrw }).containsOnly(99000)
    }

    @Test
    fun `cancel 알림은 revoke 전이고 공통 판정은 suspended를 지원한다`() {
        val f = TossBillingFixture()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
        f.store.orders[f.order.id] = f.order.copy(status = "paid")
        var revokedAtNotify = -1
        f.notifications.onNotify = { revokedAtNotify = f.gateway.revoked.size }

        f.service.cancel(f.owner, f.workspace)
        val suspended = f.service.subscriptionEventsForTransition(f.current!!, "suspended")
        val repeated = f.service.subscriptionEventsForTransition(f.current!!, "canceling")

        assertThat(revokedAtNotify).isZero()
        assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
            .containsExactly(OperatorPaymentKind.SUBSCRIPTION_CANCELING)
        assertThat(suspended.map { it.kind }).containsExactly(OperatorPaymentKind.SUBSCRIPTION_SUSPENDED)
        assertThat(repeated).isEmpty()
    }

    @Test
    fun `카드 대기 종료는 renewal 실패와 past_due를 각각 알리고 후속 정리는 실패를 반복하지 않는다`() {
        val f = TossBillingFixture()
        f.store.orders[f.order.id] =
            f.order.copy(
                kind = "renewal",
                status = "scheduled",
                firstFailureAt = f.now.minus(Duration.ofDays(15)),
            )
        f.store.currentSession = requireNotNull(f.store.currentSession).copy(state = "needs_card")
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")

        f.service.process(f.order.id)

        assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
            .containsExactly(OperatorPaymentKind.SUBSCRIPTION_PAST_DUE, OperatorPaymentKind.RENEWAL_FAILED)

        f.notifications.events.clear()
        f.current = requireNotNull(f.current).copy(status = "active")
        f.store.orders[f.order.id] = requireNotNull(f.store.orders[f.order.id]).copy(status = "suspend_pending")
        f.store.currentSession = requireNotNull(f.store.currentSession).copy(state = "active")
        f.service.process(f.order.id)
        assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
            .containsOnly(OperatorPaymentKind.SUBSCRIPTION_PAST_DUE)
    }

    @Test
    fun `최신 승인 주문이 없으면 구독 알림만 생략하고 비대상 작업은 알리지 않는다`() {
        val f = TossBillingFixture()
        f.store.orders.clear()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")

        val logs =
            captureTossLogs {
                assertThat(f.service.subscriptionEventsForTransition(f.current!!, "expired")).isEmpty()
            }
        f.service.begin(f.owner, f.workspace, "start", "replace_card")
        f.service.billingDeleted(Secret("card"))
        f.service.retryCardDeletion(f.workspace)

        assertThat(logs).hasSize(1)
        val log = logs.single()
        assertThat(log.level).isEqualTo(Level.WARN)
        assertThat(log.formattedMessage).contains("subscription_expired", f.owner.toString())
        assertThat(log.formattedMessage).doesNotContain("billing@example.test", "\"text\"", "sendMessage")
        assertThat(log.throwableProxy).isNull()
        assertThat(f.current?.status).isEqualTo("active")
        assertThat(f.notifications.events).isEmpty()
    }

    private fun captureTossLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(TossBillingService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
            return appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}

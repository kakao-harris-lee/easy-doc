package kr.easydoc.application.subscription

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.application.notification.NotificationUserRepository
import kr.easydoc.application.notification.OperatorNotificationEvent
import kr.easydoc.application.notification.OperatorNotificationService
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.application.notification.OperatorPaymentKind
import kr.easydoc.application.notification.RecordingOperatorNotifications
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TossBillingServiceTest {
    @Test
    fun `closed purchases reject ordinary workspace before card registration`() {
        val f = Fixture(purchase = false, autoCharge = false)
        f.store.orders.clear()
        f.store.currentSession = null

        assertThatThrownBy { f.service.begin(f.owner, f.workspace, "start") }
            .isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
            .hasMessage("신규 카드 등록이 중단되어 있습니다")
        assertThat(f.store.currentSession).isNull()
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `closed purchases allow only the review workspace to register a card`() {
        val f = Fixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        f.store.orders.clear()
        f.store.currentSession = null

        val session = f.service.begin(f.owner, f.workspace, "start")

        assertThat(session.workspaceId).isEqualTo(f.workspace)
        assertThat(session.environment).isEqualTo(PaymentEnvironments.TOSS_TEST)
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `review allowlist permits initial charge but does not enable automatic renewal`() {
        val f = Fixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        f.service.process(f.order.id)
        assertThat(f.gateway.charges).isEqualTo(1)

        val renewal = Fixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        renewal.store.orders[renewal.order.id] = renewal.order.copy(kind = "renewal")
        renewal.service.process(renewal.order.id)
        assertThat(renewal.gateway.charges).isZero()
    }

    @Test
    fun `production purchase opens Start overview registration and initial approval for ordinary workspace`() {
        val f = Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        f.store.orders.clear()
        f.store.currentSession = null

        val overview = f.overview()
        assertThat(overview.purchaseEnabled).isTrue()
        assertThat(overview.autoChargeEnabled).isTrue()
        assertThat(overview.plans.map { it.id }).containsExactly("start")
        assertThat(overview.plans.single().monthlyPrice).isEqualTo(99_000)
        assertThat(overview.plans.single().allowance).isEqualTo(50)

        val session =
            f.service.begin(
                f.owner,
                f.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        assertThat(session.workspaceId).isEqualTo(f.workspace)
        assertThat(session.environment).isEqualTo(PaymentEnvironments.TOSS_LIVE)

        val approval = Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        approval.service.process(approval.order.id)
        assertThat(approval.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `production ignores review allowlist in both directions`() {
        val closed = Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = false, reviewWorkspace = true)
        closed.store.orders.clear()
        closed.store.currentSession = null
        assertThat(closed.overview().purchaseEnabled).isFalse()
        assertThatThrownBy {
            closed.service.begin(
                closed.owner,
                closed.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)

        val closedApproval =
            Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = false, reviewWorkspace = true)
        closedApproval.service.process(closedApproval.order.id)
        assertThat(closedApproval.gateway.charges).isZero()

        val otherWorkspace =
            Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        assertThat(otherWorkspace.overview().purchaseEnabled).isTrue()
    }

    @Test
    fun `live purchases stay closed until the published terms take effect`() {
        val justBefore = LegalSnapshot.EFFECTIVE_AT.minusSeconds(1)

        val registration =
            Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, now = justBefore)
        registration.store.orders.clear()
        registration.store.currentSession = null
        assertThat(registration.service.purchaseAllowed(registration.workspace)).isFalse()
        assertThat(registration.overview().purchaseEnabled).isFalse()
        assertThatThrownBy {
            registration.service.begin(
                registration.owner,
                registration.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)

        val charge = Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, now = justBefore)
        charge.service.process(charge.order.id)
        assertThat(charge.gateway.charges).isZero()

        val onEffective =
            Fixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, now = LegalSnapshot.EFFECTIVE_AT)
        assertThat(onEffective.service.purchaseAllowed(onEffective.workspace)).isTrue()
        onEffective.service.process(onEffective.order.id)
        assertThat(onEffective.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `test billing purchase rules are untouched by the legal effective instant`() {
        val justBefore = LegalSnapshot.EFFECTIVE_AT.minusSeconds(1)

        val review = Fixture(purchase = false, autoCharge = false, reviewWorkspace = true, now = justBefore)
        assertThat(review.service.purchaseAllowed(review.workspace)).isTrue()
        review.service.process(review.order.id)
        assertThat(review.gateway.charges).isEqualTo(1)

        val open = Fixture(purchase = true, now = justBefore)
        assertThat(open.service.purchaseAllowed(open.workspace)).isTrue()
    }

    @Test
    fun `production keeps existing Start only plan validation`() {
        val f = Fixture(environment = PaymentEnvironments.TOSS_LIVE)
        f.store.orders.clear()
        f.store.currentSession = null
        assertThatThrownBy {
            f.service.begin(f.owner, f.workspace, "basic", consentVersion = "start-monthly-v1")
        }.isInstanceOf(kr.easydoc.core.exceptions.InvalidInputException::class.java)
            .hasMessage("알 수 없는 구독 플랜입니다")
    }

    @Test
    fun `recovery cutoff uses the injected clock and retry window`() {
        val now = Instant.parse("2026-09-28T00:00:00Z")
        val retryWindow = Duration.ofHours(2)
        val store = RecordingStore()
        val service =
            TossBillingService(
                store = store,
                subscriptions = EmptySubscriptionStore,
                accounts = NoopCreditAccountRepository,
                credits = CreditAccountService(NoopCreditAccountRepository, enforced = false),
                transaction = DirectTransaction,
                gateway = NeverGateway,
                enabled = true,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                zone = ZoneOffset.UTC,
                users = UnusedUserRepository,
                timing = TossBillingTiming(retryWindow = retryWindow),
                operatorNotifications = OperatorNotifications { },
            )

        service.runDue()

        assertThat(store.retryCutoff).isEqualTo(now.minus(retryWindow))
    }

    @Test
    fun `scheduled renewal creation sends no operator notification`() {
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val review = Fixture()
        review.store.orders[review.order.id] = review.order.copy(createdAt = review.now.minus(Duration.ofDays(15)))
        review.service.process(review.order.id)
        review.service.process(review.order.id)
        assertThat(review.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
            .containsExactly(OperatorPaymentKind.PAYMENT_MANUAL_REVIEW)

        val approved = Fixture()
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
    fun `approved time starts full month even after delayed result and duplicate processing`() {
        val f = Fixture()
        val approved = Instant.parse("2026-02-28T18:00:00Z")
        f.gateway.payment = f.payment(approved)
        f.service.process(f.order.id)
        f.service.process(f.order.id)
        assertThat(f.current?.cycleEndsAt).isEqualTo(Instant.parse("2026-03-28T18:00:00Z"))
        assertThat(f.saves).isEqualTo(1)
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).hasSize(1)
    }

    @Test
    fun `definitive decline schedules different attempt after one hour without revoking card`() {
        val f = Fixture()
        f.gateway.decline = true
        f.store.orders[f.order.id] = f.order.copy(kind = "renewal")
        f.service.process(f.order.id)
        val next =
            f.store.orders.values
                .single { it.status == "scheduled" }
        assertThat(next.id).isNotEqualTo(f.order.id)
        assertThat(next.cycleId).isEqualTo(f.order.id)
        assertThat(next.nextAttemptAt).isEqualTo(f.now.plusSeconds(3600))
        assertThat(f.store.currentSession?.billingKey).isNotNull()
        assertThat(f.current).isNull()
        f.service.process(next.id)
        assertThat(f.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `uncertain result beyond retry window requires manual review and never creates another charge`() {
        val f = Fixture()
        f.store.orders[f.order.id] = f.order.copy(createdAt = f.now.minus(Duration.ofDays(15)))
        f.service.process(f.order.id)
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("manual_review")
        assertThat(f.gateway.charges).isZero()
        assertThat(f.store.orders).hasSize(1)
    }

    @Test
    fun `disabled charging still reconciles an approval but cannot initiate charge`() {
        val f = Fixture(autoCharge = false, purchase = false)
        f.service.process(f.order.id)
        assertThat(f.gateway.charges).isZero()
        f.gateway.payment = f.payment(f.now)
        f.service.process(f.order.id)
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("paid")
    }

    @Test
    fun `environment mismatch cannot call gateway`() {
        val f = Fixture()
        f.store.orders[f.order.id] = f.order.copy(environment = PaymentEnvironments.TOSS_LIVE)
        f.service.process(f.order.id)
        assertThat(f.gateway.lookups).isZero()
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `failed replacement restores existing credential without changing subscription`() {
        val f = Fixture()
        val session =
            requireNotNull(f.store.currentSession).copy(
                state = "authorizing",
                billingKey = null,
                purpose = "replace_card",
                previousBillingKey = Secret("old-card"),
                previousCustomer = UUID.randomUUID(),
            )
        f.store.currentSession = session
        f.gateway.decline = true
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                f.service.complete(f.owner, f.workspace, session.id, session.customer, Secret("auth"), false)
            }.isInstanceOf(TossDeclined::class.java)
        assertThat(f.store.currentSession?.billingKey).isEqualTo(Secret("old-card"))
        assertThat(f.store.currentSession?.state).isEqualTo("active")
        assertThat(f.saves).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `operator recheck cannot initiate an absent charge`() {
        val f = Fixture()
        org.assertj.core.api.Assertions
            .assertThatThrownBy { f.service.recheck(f.order.id) }
            .isInstanceOf(TossUncertain::class.java)
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `replacement pauses a scheduled renewal then charges only the new card`() {
        val f = Fixture()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
        f.store.orders[f.order.id] = f.order.copy(status = "scheduled", firstFailureAt = f.now)
        val previous = requireNotNull(f.store.currentSession)
        val session = f.service.begin(f.owner, f.workspace, "start", "replace_card")
        f.service.process(f.order.id)
        assertThat(f.gateway.charges).isZero()
        f.service.complete(f.owner, f.workspace, session.id, session.customer, Secret("auth"), false)
        assertThat(f.current?.cycleEndsAt).isEqualTo(f.now)
        assertThat(f.gateway.charges).isZero()
        f.service.process(f.order.id)
        assertThat(f.gateway.lastCard).isEqualTo(Secret("new-card"))
        f.service.retryCardDeletion(f.workspace)
        assertThat(f.gateway.revoked).containsExactly(previous.billingKey)
        assertThat(f.store.currentSession?.billingKey).isEqualTo(Secret("new-card"))
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `abandoned card replacement can restart while preserving original card`() {
        val f = Fixture()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
        f.store.orders.clear()
        val first = f.service.begin(f.owner, f.workspace, "start", "replace_card")
        val second = f.service.begin(f.owner, f.workspace, "start", "replace_card")
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.previousBillingKey).isEqualTo(Secret("card"))
        assertThat(f.gateway.revoked).isEmpty()
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `an in flight attempt prevents card replacement and old key deletion`() {
        val f = Fixture()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
        f.store.orders[f.order.id] = f.order.copy(status = "processing")
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                f.service.begin(f.owner, f.workspace, "start", "replace_card")
            }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
        assertThat(f.store.currentSession?.billingKey).isEqualTo(Secret("card"))
        assertThat(f.gateway.revoked).isEmpty()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `initial decline does not automatically retry and card can be registered after cleanup`() {
        val f = Fixture()
        f.gateway.decline = true
        f.service.process(f.order.id)
        assertThat(f.store.orders).hasSize(1)
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("failed")
        assertThat(f.store.currentSession?.state).isEqualTo("revoking")
        f.service.retryCardDeletion(f.workspace)
        val next = f.service.begin(f.owner, f.workspace, "start")
        assertThat(next.state).isEqualTo("authorizing")
        assertThat(next.previousBillingKey).isNull()
        assertThat(f.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `stale issuing session requires manual review and administrator can queue key cleanup`() {
        val f = Fixture()
        f.store.orders.clear()
        f.store.currentSession =
            requireNotNull(f.store.currentSession).copy(
                state = "issuing",
                authKey = Secret("auth"),
                billingKey = null,
                expiresAt = f.now.minus(Duration.ofDays(15)),
            )
        f.service.runDue()
        assertThat(f.store.currentSession?.state).isEqualTo("manual_review")
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                f.service.begin(f.owner, f.workspace, "start")
            }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
        f.service.stopRenewal(f.owner, f.workspace)
        assertThat(f.store.currentSession?.state).isEqualTo("revoking")
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `Seoul month ends preserve a full month across leap and non leap February`() {
        listOf(
            "2024-01-31T14:30:00Z" to "2024-02-29T14:30:00Z",
            "2025-01-31T14:30:00Z" to "2025-02-28T14:30:00Z",
            "2024-02-29T15:30:00Z" to "2024-03-31T15:30:00Z",
        ).forEach { (approved, ends) ->
            val f = Fixture(zone = java.time.ZoneId.of("Asia/Seoul"))
            f.gateway.payment = f.payment(Instant.parse(approved))
            f.service.process(f.order.id)
            assertThat(f.current?.cycleEndsAt).isEqualTo(Instant.parse(ends))
        }
    }

    @Test
    fun `missing operator lookup leaves scheduled retry and card repair available`() {
        val f = Fixture()
        f.store.orders[f.order.id] = f.order.copy(status = "scheduled", firstFailureAt = f.now)
        org.assertj.core.api.Assertions
            .assertThatThrownBy { f.service.recheck(f.order.id) }
            .isInstanceOf(TossUncertain::class.java)
        assertThat(f.store.claims).isZero()
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("scheduled")
        assertThat(f.store.replacementBlocked(f.workspace)).isFalse()
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `previous card cleanup remains possible after current card deletion signal`() {
        val f = Fixture()
        f.store.currentSession =
            requireNotNull(f.store.currentSession).copy(
                previousBillingKey = Secret("old-card"),
                previousCustomer = UUID.randomUUID(),
            )
        f.gateway.revokeFails = true
        f.service.retryCardDeletion(f.workspace)
        f.service.billingDeleted(Secret("card"))
        assertThat(f.store.currentSession?.state).isEqualTo("needs_card")
        f.gateway.revokeFails = false
        f.service.retryCardDeletion(f.workspace)
        assertThat(f.store.currentSession?.previousBillingKey).isNull()
        assertThat(f.store.currentSession?.billingKey).isEqualTo(Secret("card"))
        assertThat(f.store.currentSession?.state).isEqualTo("needs_card")
        assertThat(f.gateway.revoked).containsExactly(Secret("old-card"))
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `past due card replacement waits for actual renewal approval before reactivation`() {
        val f = Fixture(autoCharge = false)
        f.store.orders.clear()
        f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "past_due", f.now, "toss_test")
        val session = f.service.begin(f.owner, f.workspace, "start", "replace_card")
        f.service.complete(f.owner, f.workspace, session.id, session.customer, Secret("auth"), false)
        assertThat(f.current?.status).isEqualTo("past_due")
        assertThat(f.saves).isZero()
        assertThat(f.store.orders[session.id]?.kind).isEqualTo("renewal")
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).isEmpty()
        f.gateway.payment = f.payment(f.now).copy(orderId = session.id)
        f.service.process(session.id)
        assertThat(f.current?.status).isEqualTo("active")
        assertThat(f.saves).isEqualTo(1)
        assertThat(f.notifications.events).hasSize(1)
        f.service.process(session.id)
        assertThat(f.saves).isEqualTo(1)
        assertThat(f.notifications.events).hasSize(1)
    }

    @Test
    fun `canceling과 expired는 실제 전이만 알리고 기준 주문을 유지한다`() {
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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

    private class Fixture(
        val autoCharge: Boolean = true,
        val purchase: Boolean = true,
        reviewWorkspace: Boolean = false,
        val environment: String = PaymentEnvironments.TOSS_TEST,
        val zone: java.time.ZoneId = ZoneOffset.UTC,
        // 실결제 픽스처는 약관 시행 시각을 기본으로 둔다. 그 전의 toss_live 구매는 닫혀 있다.
        val now: Instant =
            if (environment == PaymentEnvironments.TOSS_LIVE) {
                LegalSnapshot.EFFECTIVE_AT
            } else {
                Instant.parse("2026-03-05T00:00:00Z")
            },
    ) {
        val owner: UUID = UUID.randomUUID()
        val workspace: UUID = UUID.randomUUID()
        val order =
            BillingOrder(
                UUID.randomUUID(),
                workspace,
                "start",
                99000,
                now,
                now.plus(Duration.ofDays(30)),
                environment = environment,
            )
        val store =
            RecordingStore().also {
                it.orders[order.id] = order
                it.currentSession =
                    BillingSession(
                        workspace,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "start",
                        now.plusSeconds(3600),
                        billingKey = Secret("card"),
                        state = "active",
                        environment = environment,
                    )
            }
        val gateway = RecordingGateway()
        val notifications = RecordingOperatorNotifications()
        val transaction = DirectTransaction
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val timing = TossBillingTiming()
        val allowedWorkspaces = if (reviewWorkspace) setOf(workspace) else emptySet()
        var current: Subscription? = null
        var saves = 0
        private val subscriptions =
            object : SubscriptionStore by EmptySubscriptionStore {
                override fun find(workspaceId: UUID): Subscription? = current

                override fun save(subscription: Subscription) {
                    current = subscription
                    saves++
                }

                override fun due(now: Instant): List<Subscription> = listOfNotNull(current)
            }
        private val accounts =
            object : CreditAccountRepository by NoopCreditAccountRepository {
                override fun ownerOf(workspaceId: UUID): UUID = owner
            }
        val credits = CreditAccountService(accounts, enforced = false)
        val users =
            object : UserRepository by UnusedUserRepository {
                override fun findById(id: UUID): User =
                    User(
                        id,
                        "billing@example.test",
                        now,
                        emailVerifiedAt = now,
                        phoneVerifiedAt = now,
                        hasPassword = true,
                    )
            }
        val service = serviceWith(notifications)

        fun serviceWith(operatorNotifications: OperatorNotifications): TossBillingService =
            TossBillingService(
                store = store,
                subscriptions = subscriptions,
                accounts = accounts,
                credits = credits,
                transaction = transaction,
                gateway = gateway,
                enabled = true,
                clock = clock,
                zone = zone,
                users = users,
                timing = timing,
                environment = environment,
                purchaseEnabled = purchase,
                autoChargeEnabled = autoCharge,
                allowedWorkspaces = allowedWorkspaces,
                operatorNotifications = operatorNotifications,
            )

        fun overview(): SubscriptionOverview =
            SubscriptionService(
                subscriptions,
                CreditAccountService(accounts, enforced = false),
                DirectTransaction,
                object : PaymentGateway {
                    override fun charge(
                        orderId: UUID,
                        amount: Int,
                        fail: Boolean,
                    ): Boolean = error("stub gateway must not be called")
                },
                false,
                clock,
                zone,
                users,
                service,
            ).read(owner, workspace)

        fun payment(approved: Instant) =
            TossPayment(
                Secret("payment"),
                order.id,
                "DONE",
                99000,
                99000,
                Secret("receipt"),
                approved,
            )
    }

    private class RecordingGateway : TossGateway by NeverGateway {
        var payment: TossPayment? = null
        var decline = false
        var refundDeclines = false
        var charges = 0
        var lookups = 0
        var lastCard: Secret? = null
        val revoked = mutableListOf<Secret>()
        var revokeFails = false

        override fun revoke(billingKey: Secret) {
            if (revokeFails) throw TossUncertain()
            revoked.add(billingKey)
        }

        override fun refund(
            paymentKey: Secret,
            amount: Int,
            operation: UUID,
        ): TossPayment {
            if (refundDeclines) throw TossDeclined()
            error("gateway must not be called")
        }

        override fun issueCard(
            authKey: Secret,
            customerKey: Secret,
            session: UUID,
        ): IssuedBillingCard = IssuedBillingCard(issue(authKey, customerKey, session), "1234")

        override fun find(order: UUID): TossPayment? {
            lookups++
            return payment
        }

        override fun issue(
            authKey: Secret,
            customerKey: Secret,
            session: UUID,
        ): Secret {
            if (decline) throw TossDeclined()
            return Secret("new-card")
        }

        override fun charge(
            billingKey: Secret,
            customerKey: Secret,
            order: UUID,
            amount: Int,
            plan: String,
            fail: Boolean,
        ): TossPayment {
            charges++
            lastCard = billingKey
            if (decline) throw TossDeclined()
            throw TossUncertain()
        }
    }

    private class RecordingStore : TossBillingStore {
        var retryCutoff: Instant? = null
        val orders = mutableMapOf<UUID, BillingOrder>()
        val releases = mutableListOf<Pair<UUID, Instant>>()
        var currentSession: BillingSession? = null
        var claims = 0

        override fun sessionByBillingKey(
            key: Secret,
            environment: String,
        ): BillingSession? = currentSession?.takeIf { it.billingKey == key && it.environment == environment }

        override fun session(workspace: UUID): BillingSession? = currentSession

        override fun saveSession(session: BillingSession) {
            currentSession = session
        }

        override fun order(id: UUID): BillingOrder? = orders[id]

        override fun latestOrder(workspace: UUID): BillingOrder? =
            orders.values
                .filter { it.workspaceId == workspace && it.kind != "refund" }
                .maxWithOrNull(compareBy<BillingOrder> { it.createdAt }.thenBy { it.attempt })

        override fun saveOrder(order: BillingOrder) {
            orders[order.id] = order
        }

        override fun pending(workspace: UUID): Boolean =
            orders.values.any {
                it.status in listOf("pending", "processing", "scheduled", "manual_review", "suspend_pending")
            }

        override fun replacementBlocked(workspace: UUID): Boolean =
            orders.values.any {
                it.status in listOf("pending", "processing", "manual_review")
            }

        override fun claim(
            id: UUID,
            now: Instant,
        ): BillingOrder? {
            claims++
            return orders[id]
        }

        override fun release(
            id: UUID,
            nextAttempt: Instant,
        ) {
            releases += id to nextAttempt
        }

        override fun candidates(now: Instant): List<UUID> = emptyList()

        override fun requestSync(id: UUID) = Unit

        override fun savePayment(
            order: BillingOrder,
            payment: TossPayment,
        ) = Unit

        override fun cleanupCandidates(): List<UUID> = emptyList()

        override fun authorizationCandidates(retryCutoff: Instant): List<UUID> {
            this.retryCutoff = retryCutoff
            return listOfNotNull(currentSession?.takeIf { it.state == "issuing" }?.workspaceId)
        }
    }

    private object EmptySubscriptionStore : SubscriptionStore {
        override fun lockOwned(
            ownerId: UUID,
            workspaceId: UUID,
        ) = Unit

        override fun find(workspaceId: UUID): Subscription? = null

        override fun save(subscription: Subscription) = Unit

        override fun payments(workspaceId: UUID): List<SubscriptionPayment> = emptyList()

        override fun payment(
            workspaceId: UUID,
            id: UUID,
        ): SubscriptionPayment? = null

        override fun record(payment: SubscriptionPayment) = Unit

        override fun due(now: Instant): List<Subscription> = emptyList()
    }

    private object DirectTransaction : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private object NeverGateway : TossGateway {
        override fun issue(
            authKey: Secret,
            customerKey: Secret,
            session: UUID,
        ): Secret = error("gateway must not be called")

        override fun charge(
            billingKey: Secret,
            customerKey: Secret,
            order: UUID,
            amount: Int,
            plan: String,
            fail: Boolean,
        ): TossPayment = error("gateway must not be called")

        override fun find(order: UUID): TossPayment? = error("gateway must not be called")

        override fun refund(
            paymentKey: Secret,
            amount: Int,
            operation: UUID,
        ): TossPayment = error("gateway must not be called")

        override fun revoke(billingKey: Secret) = error("gateway must not be called")
    }

    private object UnusedUserRepository : UserRepository {
        override fun findByEmail(email: String): StoredUser? = error("user repository must not be called")

        override fun findById(id: UUID): User? = error("user repository must not be called")

        override fun exists(id: UUID): Boolean = error("user repository must not be called")

        override fun lockForUpdate(id: UUID): User? = error("user repository must not be called")

        override fun create(
            email: String,
            passwordHash: PasswordHash,
        ): User = error("user repository must not be called")

        override fun createWithoutPassword(
            email: String,
            emailVerified: Boolean,
        ): User = error("user repository must not be called")

        override fun updatePasswordHash(
            userId: UUID,
            passwordHash: PasswordHash,
        ) = error("user repository must not be called")

        override fun markEmailVerified(userId: UUID): Boolean = error("user repository must not be called")
    }
}

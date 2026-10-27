package kr.easydoc.application.subscription

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TossBillingServiceTest {
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
    fun `approved time starts full month even after delayed result and duplicate processing`() {
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
        f.store.orders[f.order.id] = f.order.copy(createdAt = f.now.minus(Duration.ofDays(15)))
        f.service.process(f.order.id)
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("manual_review")
        assertThat(f.gateway.charges).isZero()
        assertThat(f.store.orders).hasSize(1)
    }

    @Test
    fun `disabled charging still reconciles an approval but cannot initiate charge`() {
        val f = TossBillingFixture(autoCharge = false, purchase = false)
        f.service.process(f.order.id)
        assertThat(f.gateway.charges).isZero()
        f.gateway.payment = f.payment(f.now)
        f.service.process(f.order.id)
        assertThat(f.store.orders[f.order.id]?.status).isEqualTo("paid")
    }

    @Test
    fun `environment mismatch cannot call gateway`() {
        val f = TossBillingFixture()
        f.store.orders[f.order.id] = f.order.copy(environment = PaymentEnvironments.TOSS_LIVE)
        f.service.process(f.order.id)
        assertThat(f.gateway.lookups).isZero()
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `failed replacement restores existing credential without changing subscription`() {
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
        org.assertj.core.api.Assertions
            .assertThatThrownBy { f.service.recheck(f.order.id) }
            .isInstanceOf(TossUncertain::class.java)
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `replacement pauses a scheduled renewal then charges only the new card`() {
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
            val f = TossBillingFixture(zone = java.time.ZoneId.of("Asia/Seoul"))
            f.gateway.payment = f.payment(Instant.parse(approved))
            f.service.process(f.order.id)
            assertThat(f.current?.cycleEndsAt).isEqualTo(Instant.parse(ends))
        }
    }

    @Test
    fun `missing operator lookup leaves scheduled retry and card repair available`() {
        val f = TossBillingFixture()
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
        val f = TossBillingFixture()
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
        val f = TossBillingFixture(autoCharge = false)
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
}

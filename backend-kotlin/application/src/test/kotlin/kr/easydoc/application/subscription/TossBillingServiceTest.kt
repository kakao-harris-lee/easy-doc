package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
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
            )

        service.runDue()

        assertThat(store.retryCutoff).isEqualTo(now.minus(retryWindow))
    }

    private class RecordingStore : TossBillingStore {
        var retryCutoff: Instant? = null

        override fun session(workspace: UUID): BillingSession? = null

        override fun saveSession(session: BillingSession) = Unit

        override fun order(id: UUID): BillingOrder? = null

        override fun saveOrder(order: BillingOrder) = Unit

        override fun pending(workspace: UUID): Boolean = false

        override fun claim(
            id: UUID,
            now: Instant,
        ): BillingOrder? = null

        override fun release(
            id: UUID,
            nextAttempt: Instant,
        ) = Unit

        override fun candidates(now: Instant): List<UUID> = emptyList()

        override fun requestSync(id: UUID) = Unit

        override fun savePayment(
            order: BillingOrder,
            payment: TossPayment,
        ) = Unit

        override fun cleanupCandidates(): List<UUID> = emptyList()

        override fun authorizationCandidates(retryCutoff: Instant): List<UUID> {
            this.retryCutoff = retryCutoff
            return emptyList()
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

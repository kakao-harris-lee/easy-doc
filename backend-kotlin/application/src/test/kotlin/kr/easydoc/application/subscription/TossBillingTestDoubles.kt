package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.application.notification.RecordingOperatorNotifications
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// `TossBillingService` 테스트가 공유하는 대역이다. `TossBillingServiceTest` 안에 쌓여 있던 것을
// 그대로 옮겼고, 테스트가 세 클래스로 나뉘면서 같은 픽스처를 쓰게 됐다.

/** 구독 하나·주문 하나·활성 세션 하나를 든 기본 상태. 시각은 고정 `Clock` 이다. */
internal class TossBillingFixture(
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

internal class RecordingGateway : TossGateway by NeverGateway {
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

internal class RecordingStore : TossBillingStore {
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

internal object EmptySubscriptionStore : SubscriptionStore {
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

internal object DirectTransaction : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

internal object NeverGateway : TossGateway {
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

internal object UnusedUserRepository : UserRepository {
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

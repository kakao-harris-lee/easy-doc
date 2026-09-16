package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.PhoneNotVerifiedException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class SubscriptionServiceTest {
    private val workspace = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val now = Instant.parse("2026-01-31T03:00:00Z")
    private val store = MemoryStore()
    private var grants = 0
    private val credits =
        CreditAccountService(
            object : kr.easydoc.application.credit.CreditAccountRepository by NoopCreditAccountRepository {
                override fun setAllowance(
                    workspaceId: UUID,
                    ownerUserId: UUID,
                    allowance: Int,
                    cycleEndsAt: Instant,
                    renews: Boolean,
                    reason: CreditReason,
                    note: String?,
                    actorUserId: UUID?,
                ): Int {
                    grants++
                    assertThat(renews).isFalse()
                    return allowance
                }
            },
            false,
        )
    private val transaction =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun service(
        at: Instant = now,
        decline: Boolean = false,
        enabled: Boolean = true,
        emailVerified: Boolean = true,
        phoneVerified: Boolean = true,
    ) = SubscriptionService(
        store,
        credits,
        transaction,
        object : PaymentGateway {
            override fun charge(
                orderId: UUID,
                amount: Int,
                fail: Boolean,
            ) = !fail && !decline
        },
        enabled,
        Clock.fixed(at, ZoneOffset.UTC),
        ZoneId.of("Asia/Seoul"),
        EligibleUserRepository(owner, emailVerified, phoneVerified),
    )

    @Test
    fun `결제는 이메일과 휴대폰 인증을 모두 요구한다`() {
        assertThatThrownBy {
            service(emailVerified = false).checkout(owner, workspace, "start", UUID.randomUUID(), false)
        }.isInstanceOf(EmailNotVerifiedException::class.java)
        assertThatThrownBy {
            service(phoneVerified = false).checkout(owner, workspace, "start", UUID.randomUUID(), false)
        }.isInstanceOf(PhoneNotVerifiedException::class.java)
        assertThat(store.payments).isEmpty()
    }

    @Test
    fun `successful checkout applies one allowance and retry does not replenish it`() {
        val id = UUID.randomUUID()
        val result = service().checkout(owner, workspace, "start", id, false)
        assertThat(result.plans).containsExactly(SubscriptionPlan("start", "Start", 50, 99_000))
        assertThat(result.subscription?.cycleEndsAt).isEqualTo(Instant.parse("2026-02-28T03:00:00Z"))
        service().checkout(owner, workspace, "start", id, false)
        assertThat(grants).isEqualTo(1)
        assertThat(store.payments).hasSize(1)
        assertThatThrownBy {
            service().checkout(owner, workspace, "start", id, true)
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `decline records failure without subscription or allowance`() {
        val result = service().checkout(owner, workspace, "start", UUID.randomUUID(), true)
        assertThat(result.subscription).isNull()
        assertThat(result.payments.single().status).isEqualTo("failed")
        assertThat(grants).isZero()
    }

    @Test
    fun `only Start is available for test checkout`() {
        assertThat(service().read(owner, workspace).plans)
            .containsExactly(SubscriptionPlan("start", "Start", 50, 99_000))
        assertThatThrownBy {
            service().checkout(owner, workspace, "basic", UUID.randomUUID(), false)
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            service().checkout(owner, workspace, "pro", UUID.randomUUID(), false)
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `active subscription cannot be bought again with a new order`() {
        service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        assertThatThrownBy {
            service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        }.isInstanceOf(ConflictException::class.java)
        assertThat(grants).isEqualTo(1)
    }

    @Test
    fun `cancellation preserves paid period and ends without renewal charge`() {
        service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        service().cancel(owner, workspace)
        assertThat(store.current?.status).isEqualTo("canceling")
        assertThat(grants).isEqualTo(1)
        service(now.plusSeconds(40L * 86400)).renewDue()
        assertThat(store.current?.status).isEqualTo("expired")
        assertThat(store.payments).hasSize(1)
        assertThat(grants).isEqualTo(2)
    }

    @Test
    fun `renewal charges once and replaces allowance even after missed months`() {
        service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        val later = service(Instant.parse("2026-05-02T03:00:00Z"))
        later.renewDue()
        later.renewDue()
        assertThat(grants).isEqualTo(2)
        assertThat(store.payments).hasSize(2)
        assertThat(store.current?.cycleEndsAt).isAfter(Instant.parse("2026-05-02T03:00:00Z"))
    }

    @Test
    fun `failed renewal closes allowance and is not repeatedly charged`() {
        service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        val later = service(now.plusSeconds(40L * 86400), decline = true)
        later.renewDue()
        later.renewDue()
        assertThat(store.current?.status).isEqualTo("past_due")
        assertThat(store.payments).hasSize(2)
        assertThat(store.payments.last().status).isEqualTo("failed")
        assertThat(grants).isEqualTo(2)
    }

    @Test
    fun `disabling mock ends existing cycles without charging or freezing allowance`() {
        service().checkout(owner, workspace, "start", UUID.randomUUID(), false)
        service(now.plusSeconds(40L * 86400), enabled = false).renewDue()
        assertThat(store.current?.status).isEqualTo("expired")
        assertThat(store.payments).hasSize(1)
        assertThat(grants).isEqualTo(2)
    }

    private inner class MemoryStore : SubscriptionStore {
        var current: Subscription? = null
        val payments = mutableListOf<SubscriptionPayment>()

        override fun lockOwned(
            ownerId: UUID,
            workspaceId: UUID,
        ) = Unit

        override fun find(workspaceId: UUID) = current

        override fun save(subscription: Subscription) {
            current = subscription
        }

        override fun payments(workspaceId: UUID) = payments.toList().reversed()

        override fun payment(
            workspaceId: UUID,
            id: UUID,
        ) = payments.find { it.id == id }

        override fun record(payment: SubscriptionPayment) {
            payments.add(payment)
        }

        override fun due(now: Instant) =
            current?.takeIf { it.status in listOf("active", "canceling") && it.cycleEndsAt <= now }?.let { listOf(it) }
                ?: emptyList()
    }
}

private class EligibleUserRepository(
    private val owner: UUID,
    emailVerified: Boolean,
    phoneVerified: Boolean,
) : UserRepository {
    private val user =
        User(
            id = owner,
            email = "owner@example.test",
            createdAt = Instant.EPOCH,
            emailVerifiedAt = Instant.EPOCH.takeIf { emailVerified },
            hasPassword = true,
            phoneVerifiedAt = Instant.EPOCH.takeIf { phoneVerified },
        )

    override fun findById(id: UUID): User? = user.takeIf { id == owner }

    override fun findByEmail(email: String): StoredUser? = error("not used")

    override fun exists(id: UUID): Boolean = error("not used")

    override fun lockForUpdate(id: UUID): User? = error("not used")

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("not used")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = error("not used")

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("not used")

    override fun markEmailVerified(userId: UUID): Boolean = error("not used")
}

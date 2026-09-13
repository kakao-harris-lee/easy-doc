package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 서버가 허용하는 테스트 결제 플랜. 실제 청구는 test/stub 프로필에서만 가능하다. */
data class SubscriptionPlan(
    val id: String,
    val name: String,
    val allowance: Int,
    val monthlyPrice: Int,
) {
    override fun toString(): String = "SubscriptionPlan(id=$id, allowance=$allowance, monthlyPrice=$monthlyPrice)"
}

data class Subscription(
    val workspaceId: UUID,
    val ownerId: UUID,
    val planId: String,
    val allowance: Int,
    val monthlyPrice: Int,
    val status: String,
    val cycleEndsAt: Instant,
    val provider: String = "stub",
)

data class SubscriptionPayment(
    val id: UUID,
    val workspaceId: UUID,
    val planId: String,
    val amount: Int,
    val status: String,
    val createdAt: Instant,
    val simulatedFailure: Boolean,
    val provider: String = "stub",
    val refundedAmount: Int = 0,
)

data class SubscriptionOverview(
    val mockEnabled: Boolean,
    val plans: List<SubscriptionPlan>,
    val subscription: Subscription?,
    val payments: List<SubscriptionPayment>,
    val tossEnabled: Boolean = false,
    val pending: Boolean = false,
    val billingState: String? = null,
)

interface PaymentGateway {
    /** Stable order id is the idempotency key. No card details are collected by the stub. */
    fun charge(
        orderId: UUID,
        amount: Int,
        fail: Boolean,
    ): Boolean
}

interface SubscriptionStore {
    /** Lock the owned workspace before reading or mutating subscription state; other owners get 404. */
    fun lockOwned(
        ownerId: UUID,
        workspaceId: UUID,
    )

    fun find(workspaceId: UUID): Subscription?

    fun save(subscription: Subscription)

    fun payments(workspaceId: UUID): List<SubscriptionPayment>

    fun payment(
        workspaceId: UUID,
        id: UUID,
    ): SubscriptionPayment?

    fun record(payment: SubscriptionPayment)

    fun due(now: Instant): List<Subscription>
}

@Suppress("LongParameterList")
class SubscriptionService(
    private val store: SubscriptionStore,
    private val credits: CreditAccountService,
    private val transaction: TransactionRunner,
    private val gateway: PaymentGateway,
    private val enabled: Boolean,
    private val clock: Clock,
    private val zone: ZoneId,
    private val toss: TossBillingService? = null,
) {
    private val plans = listOf(SubscriptionPlan("start", "Start", 50, 99_000))

    fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): SubscriptionOverview =
        transaction.inTransaction {
            store.lockOwned(ownerId, workspaceId)
            overview(workspaceId)
        }

    fun checkout(
        ownerId: UUID,
        workspaceId: UUID,
        planId: String,
        orderId: UUID,
        fail: Boolean,
    ): SubscriptionOverview =
        transaction.inTransaction {
            store.lockOwned(ownerId, workspaceId)
            requireEnabled()
            val plan = plans.find { it.id == planId } ?: throw InvalidInputException("알 수 없는 구독 플랜입니다")
            val previous = store.payment(workspaceId, orderId)
            if (previous != null) {
                if (previous.planId != planId ||
                    previous.simulatedFailure != fail
                ) {
                    throw ConflictException("같은 주문 번호로 다른 결제를 요청할 수 없습니다")
                }
                return@inTransaction overview(workspaceId)
            }
            val current = store.find(workspaceId)
            if (current?.status in
                listOf("active", "canceling")
            ) {
                throw ConflictException("이용 중인 구독이 있습니다. 종료 후 새 플랜을 선택하세요")
            }
            val now = clock.instant()
            val paid = gateway.charge(orderId, plan.monthlyPrice, fail)
            store.record(
                SubscriptionPayment(
                    orderId,
                    workspaceId,
                    planId,
                    plan.monthlyPrice,
                    if (paid) "paid" else "failed",
                    now,
                    fail,
                ),
            )
            if (paid) {
                val subscription =
                    Subscription(
                        workspaceId,
                        ownerId,
                        planId,
                        plan.allowance,
                        plan.monthlyPrice,
                        "active",
                        nextMonth(now),
                    )
                store.save(subscription)
                applyAllowance(subscription)
            }
            overview(workspaceId)
        }

    fun cancel(
        ownerId: UUID,
        workspaceId: UUID,
    ): SubscriptionOverview {
        if (toss?.owns(workspaceId) == true) {
            toss.cancel(ownerId, workspaceId)
            return read(ownerId, workspaceId)
        }
        return transaction.inTransaction {
            store.lockOwned(ownerId, workspaceId)
            requireEnabled()
            val current = store.find(workspaceId) ?: throw ConflictException("이용 중인 구독이 없습니다")
            if (current.status == "active") store.save(current.copy(status = "canceling"))
            overview(workspaceId)
        }
    }

    /** Stub-only renewal. Each workspace and allowance change commits atomically with its payment record. */
    fun renewDue() {
        val now = clock.instant()
        store.due(now).filter { it.provider == "stub" }.forEach { candidate ->
            transaction.inTransaction {
                store.lockOwned(candidate.ownerId, candidate.workspaceId)
                val current = store.find(candidate.workspaceId) ?: return@inTransaction
                if (current.cycleEndsAt > now || current.status !in listOf("active", "canceling")) return@inTransaction
                if (current.status == "canceling" || !enabled) {
                    expire(current, "expired")
                } else {
                    val id =
                        UUID.nameUUIDFromBytes(
                            "${current.workspaceId}:${current.cycleEndsAt}".toByteArray(Charsets.UTF_8),
                        )
                    val paid = gateway.charge(id, current.monthlyPrice, false)
                    store.record(
                        SubscriptionPayment(
                            id,
                            current.workspaceId,
                            current.planId,
                            current.monthlyPrice,
                            if (paid) "paid" else "failed",
                            now,
                            false,
                        ),
                    )
                    if (paid) {
                        // No back charging for missed months; open one new monthly period from processing time.
                        val renewed = current.copy(cycleEndsAt = nextMonth(now))
                        store.save(renewed)
                        applyAllowance(renewed)
                    } else {
                        expire(current, "past_due")
                    }
                }
            }
        }
    }

    private fun expire(
        current: Subscription,
        status: String,
    ) {
        store.save(current.copy(status = status))
        credits.setAllowance(
            current.workspaceId,
            current.ownerId,
            0,
            current.cycleEndsAt,
            false,
            CreditReason.CYCLE_END,
            "mock subscription ended",
        )
    }

    private fun applyAllowance(subscription: Subscription) {
        credits.setAllowance(
            subscription.workspaceId,
            subscription.ownerId,
            subscription.allowance,
            subscription.cycleEndsAt,
            false,
            CreditReason.PLAN_MONTHLY,
            "mock subscription",
        )
    }

    private fun nextMonth(at: Instant): Instant = at.atZone(zone).plusMonths(1).toInstant()

    private fun requireEnabled() {
        if (!enabled) throw ConflictException("테스트 결제가 활성화되어 있지 않습니다")
    }

    private fun overview(workspaceId: UUID) =
        SubscriptionOverview(
            enabled,
            plans,
            store.find(workspaceId),
            store.payments(workspaceId),
            toss?.enabled == true,
            toss?.pending(workspaceId) == true,
            toss?.state(workspaceId),
        )
}

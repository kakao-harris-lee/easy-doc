package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/** Only test keys may reach this service. Network calls never hold the workspace transaction open. */
@Suppress("LongParameterList", "TooManyFunctions")
class TossBillingService(
    private val store: TossBillingStore,
    private val subscriptions: SubscriptionStore,
    private val accounts: CreditAccountRepository,
    private val credits: CreditAccountService,
    private val transaction: TransactionRunner,
    private val gateway: TossGateway,
    val enabled: Boolean,
    private val clock: Clock,
    private val zone: ZoneId,
    val clientKey: Secret = Secret.EMPTY,
    private val users: kr.easydoc.application.auth.UserRepository,
) {
    fun owns(workspace: UUID): Boolean =
        subscriptions.find(workspace)?.let { it.provider == "toss_test" }
            ?: (enabled && store.session(workspace) != null)

    fun pending(workspace: UUID): Boolean = store.pending(workspace)

    fun state(workspace: UUID): String? = store.session(workspace)?.state

    fun begin(
        owner: UUID,
        workspace: UUID,
        planId: String,
    ): BillingSession =
        owned(owner, workspace) {
            requireEnabled()
            val ownerUser = users.findById(owner)
            requirePaymentEligible(ownerUser, PaymentAction.CARD_REGISTRATION)
            plan(planId)
            if (store.pending(workspace)) conflict("처리 중인 결제가 있습니다. 결과를 먼저 확인하세요")
            val subscription = subscriptions.find(workspace)
            if (subscription?.status == "active") conflict("기존 카드의 갱신을 중단한 뒤 새 카드를 등록하세요")
            if (subscription != null && subscription.status == "canceling" &&
                subscription.cycleEndsAt > clock.instant()
            ) {
                if (subscription.planId != planId || subscription.provider != "toss_test") {
                    conflict("현재 이용 기간에는 같은 플랜의 카드만 변경할 수 있습니다")
                }
            }
            val previous = store.session(workspace)
            if (previous?.authKey != null && previous.state == "issuing") conflict("처리 중인 카드 등록 결과를 먼저 확인하세요")
            if (previous?.billingKey != null) conflict("이전 카드 연결 해제를 처리 중입니다. 잠시 후 다시 시도하세요")
            BillingSession(
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                planId,
                clock.instant().plusSeconds(SESSION_TTL_SECONDS),
            ).also(store::saveSession)
        }

    @Suppress("CyclomaticComplexMethod") // Session binding and replay checks stay together.
    fun complete(
        owner: UUID,
        workspace: UUID,
        id: UUID,
        customer: UUID,
        auth: Secret,
        fail: Boolean,
    ) {
        val session =
            owned(owner, workspace) {
                requireEnabled()
                val current = store.session(workspace) ?: conflict("카드 등록을 다시 시작하세요")
                if (current.id != id || current.customer != customer) conflict("카드 등록 정보가 일치하지 않습니다")
                if (current.authKey != null &&
                    (current.authKey != auth || current.simulateFailure != fail)
                ) {
                    conflict("이미 처리한 카드 등록 요청입니다")
                }
                if (current.state == "active") return@owned current
                if (current.state !in listOf("authorizing", "issuing") ||
                    (current.authKey == null && current.expiresAt < clock.instant())
                ) {
                    conflict("카드 등록 시간이 만료되었습니다. 다시 시작하세요")
                }
                if (auth.reveal().isBlank() ||
                    auth.reveal().length > AUTH_KEY_MAX_LENGTH
                ) {
                    throw InvalidInputException("잘못된 카드 등록 값입니다")
                }
                current.copy(authKey = auth, simulateFailure = fail, state = "issuing").also(store::saveSession)
            }
        if (session.state == "active") {
            process(session.id)
            return
        }
        if (Duration.between(session.expiresAt, clock.instant()).toDays() >= RETRY_MAX_DAYS) {
            conflict("카드 등록 결과를 관리자에게 문의하세요")
        }
        val key = gateway.issue(auth, Secret(customer.toString()), id)
        owned(owner, workspace) {
            val current = store.session(workspace) ?: conflict("카드 등록을 다시 시작하세요")
            if (current.id != id || current.state !in listOf("issuing", "active")) conflict("카드 등록 상태가 바뀌었습니다")
            if (current.state == "active") return@owned
            store.saveSession(current.copy(billingKey = key, state = "active", authKey = null))
            val subscription = subscriptions.find(workspace)
            if (subscription?.status == "canceling" && subscription.cycleEndsAt > clock.instant()) {
                subscriptions.save(subscription.copy(status = "active"))
                return@owned // Card replacement preserves the paid period and never grants or charges again.
            }
            val selected = plan(current.planId)
            val now = clock.instant()
            store.saveOrder(
                BillingOrder(
                    id,
                    workspace,
                    selected.id,
                    selected.monthlyPrice,
                    now,
                    now.atZone(zone).plusMonths(1).toInstant(),
                    simulateFailure = current.simulateFailure,
                ),
            )
        }
        process(id)
    }

    fun cancel(
        owner: UUID,
        workspace: UUID,
    ) {
        owned(owner, workspace) {
            requireEnabled()
            if (store.pending(workspace)) conflict("처리 중인 결제 결과를 확인한 후 해지하세요")
            val session = store.session(workspace)
            if (session?.state == "issuing") {
                conflict("처리 중인 카드 등록 결과를 확인한 후 해지하세요")
            }
            subscriptions.find(workspace)?.let {
                if (it.status ==
                    "active"
                ) {
                    subscriptions.save(it.copy(status = "canceling"))
                }
            }
            store.session(workspace)?.let { store.saveSession(it.copy(state = "revoking", authKey = null)) }
        }
        revoke(workspace)
    }

    fun receipt(
        owner: UUID,
        workspace: UUID,
        id: UUID,
    ): Secret =
        owned(owner, workspace) {
            val order =
                store.order(id)?.takeIf { it.workspaceId == workspace && it.kind == "charge" }
                    ?: throw NotFoundException("결제 내역이 없습니다")
            order.payment?.receipt ?: throw NotFoundException("영수증이 아직 없습니다")
        }

    fun refund(
        owner: UUID,
        workspace: UUID,
        originalId: UUID,
        operation: UUID,
        amount: Int,
    ) {
        owned(owner, workspace) {
            requireEnabled()
            val previous = store.order(operation)
            if (previous != null) {
                if (previous.workspaceId != workspace || previous.originalId != originalId ||
                    previous.amount != amount
                ) {
                    conflict("다른 환불 요청에 사용된 주문 번호입니다")
                }
                return@owned
            }
            if (store.pending(workspace)) conflict("처리 중인 결제가 있습니다")
            val original =
                store.order(originalId)?.takeIf { it.workspaceId == workspace && it.kind == "charge" }
                    ?: throw NotFoundException("결제 내역이 없습니다")
            val payment = original.payment ?: conflict("승인된 결제가 없습니다")
            if (amount <= 0 || amount > payment.remainingAmount) throw InvalidInputException("환불 가능 금액을 확인하세요")
            store.saveOrder(
                BillingOrder(
                    operation,
                    workspace,
                    original.planId,
                    amount,
                    clock.instant(),
                    original.cycleEndsAt,
                    "refund",
                    originalId,
                    payment.remainingAmount,
                ),
            )
        }
        process(operation)
    }

    /** A webhook is only a hint. Amount and status always come from an authenticated Toss GET. */
    fun requestSync(id: UUID) {
        if (enabled) store.requestSync(id)
    }

    fun runDue() {
        if (!enabled) {
            subscriptions.due(clock.instant()).filter { it.provider == "toss_test" }.forEach { candidate ->
                owned(candidate.ownerId, candidate.workspaceId) {
                    val current = subscriptions.find(candidate.workspaceId) ?: return@owned
                    if (current.cycleEndsAt <= clock.instant()) expire(current, "expired")
                }
            }
            return
        }
        recoverAuthorizations()
        subscriptions.due(clock.instant()).filter { it.provider == "toss_test" }.forEach { candidate ->
            owned(candidate.ownerId, candidate.workspaceId) {
                val current = subscriptions.find(candidate.workspaceId) ?: return@owned
                if (current.cycleEndsAt > clock.instant() || store.pending(current.workspaceId)) return@owned
                if (current.status == "canceling") {
                    expire(current, "expired")
                    return@owned
                }
                if (current.status != "active") return@owned
                val id = UUID.nameUUIDFromBytes("toss:${current.workspaceId}:${current.cycleEndsAt}".toByteArray())
                if (store.order(id) == null) {
                    val now = clock.instant()
                    store.saveOrder(
                        BillingOrder(
                            id,
                            current.workspaceId,
                            current.planId,
                            current.monthlyPrice,
                            now,
                            now.atZone(zone).plusMonths(1).toInstant(),
                        ),
                    )
                }
            }
        }
        store.candidates(clock.instant()).forEach(::process)
        store.cleanupCandidates().forEach(::revoke)
    }

    private fun recoverAuthorizations() {
        store.authorizationCandidates().forEach { workspace ->
            val session = store.session(workspace) ?: return@forEach
            val auth = session.authKey ?: return@forEach
            if (Duration.between(session.expiresAt, clock.instant()).toDays() >= RETRY_MAX_DAYS) return@forEach
            val owner = accounts.ownerOf(workspace) ?: return@forEach
            try {
                complete(owner, workspace, session.id, session.customer, auth, session.simulateFailure)
            } catch (
                _: TossUncertain,
            ) {
                // The same session and idempotency key are retried.
            } catch (_: TossDeclined) {
                owned(owner, workspace) {
                    val current = store.session(workspace) ?: return@owned
                    if (current.id == session.id && current.state == "issuing") {
                        store.saveSession(current.copy(state = "revoking", authKey = null))
                    }
                }
            }
        }
    }

    fun process(id: UUID) {
        if (!enabled) return
        val order = transaction.inTransaction { store.claim(id, clock.instant()) } ?: return
        try {
            val payment = if (order.kind == "refund") refundResult(order) else chargeResult(order)
            finish(order, payment)
        } catch (_: TossDeclined) {
            failed(order)
        } catch (_: TossUncertain) {
            transaction.inTransaction { store.release(id, clock.instant().plusSeconds(RETRY_SECONDS)) }
        }
    }

    @Suppress("ThrowsCount") // Missing durable credentials must stop an external charge.
    private fun chargeResult(order: BillingOrder): TossPayment {
        gateway.find(order.id)?.let { return it }
        if (order.status !in listOf("pending", "processing") ||
            Duration.between(order.createdAt, clock.instant()).toDays() >= RETRY_MAX_DAYS
        ) {
            throw TossUncertain()
        }
        val session = store.session(order.workspaceId) ?: throw TossUncertain()
        val key = session.billingKey ?: throw TossUncertain()
        return gateway.charge(
            key,
            Secret(session.customer.toString()),
            order.id,
            order.amount,
            order.planId,
            order.simulateFailure,
        )
    }

    @Suppress("ThrowsCount") // Each reconciliation mismatch stops an external refund.
    private fun refundResult(order: BillingOrder): TossPayment {
        val original = store.order(requireNotNull(order.originalId)) ?: throw TossUncertain()
        val payment = gateway.find(original.id) ?: throw TossUncertain()
        validate(original, payment)
        val remaining = order.previousRemaining - order.amount
        if (payment.remainingAmount == remaining) return payment
        if (payment.remainingAmount != order.previousRemaining ||
            Duration.between(order.createdAt, clock.instant()).toDays() >= RETRY_MAX_DAYS
        ) {
            throw TossUncertain()
        }
        return gateway.refund(payment.key, order.amount, order.id)
    }

    @Suppress("ThrowsCount") // Ownership and persisted state are rechecked under the lock.
    private fun finish(
        order: BillingOrder,
        payment: TossPayment,
    ) {
        val original =
            if (order.kind ==
                "refund"
            ) {
                store.order(requireNotNull(order.originalId)) ?: throw TossUncertain()
            } else {
                order
            }
        validate(original, payment)
        if (order.kind == "refund" &&
            payment.remainingAmount != order.previousRemaining - order.amount
        ) {
            throw TossUncertain()
        }
        val owner = accounts.ownerOf(order.workspaceId) ?: throw TossUncertain()
        owned(owner, order.workspaceId) {
            val latest = store.order(original.id) ?: throw TossUncertain()
            // Finalization is atomic with allowance replacement. Replays and webhooks cannot replenish credits.
            if (latest.status in listOf("pending", "processing") && payment.status == "DONE") {
                val selected = plan(original.planId)
                val subscription =
                    Subscription(
                        order.workspaceId,
                        owner,
                        selected.id,
                        selected.allowance,
                        original.amount,
                        "active",
                        original.cycleEndsAt,
                        "toss_test",
                    )
                subscriptions.save(subscription)
                credits.setAllowance(
                    subscription.workspaceId,
                    owner,
                    selected.allowance,
                    subscription.cycleEndsAt,
                    false,
                    CreditReason.PLAN_MONTHLY,
                    "Toss test subscription",
                )
            }
            // A slower status lookup must never undo a refund already reconciled in another transaction.
            val effective = latest.payment?.takeIf { it.remainingAmount < payment.remainingAmount } ?: payment
            store.saveOrder(latest.copy(status = "paid", payment = effective))
            store.savePayment(original, effective)
            if (order.kind == "refund") store.saveOrder(order.copy(status = "paid", payment = payment))
        }
    }

    private fun failed(order: BillingOrder) {
        val owner = accounts.ownerOf(order.workspaceId) ?: return
        owned(owner, order.workspaceId) {
            val current = store.order(order.id) ?: return@owned
            if (current.status !in listOf("pending", "processing")) return@owned
            store.saveOrder(current.copy(status = "failed"))
            if (order.kind == "charge") {
                subscriptions.record(
                    SubscriptionPayment(
                        order.id,
                        order.workspaceId,
                        order.planId,
                        order.amount,
                        "failed",
                        order.createdAt,
                        order.simulateFailure,
                        "toss_test",
                    ),
                )
                subscriptions.find(order.workspaceId)?.let { expire(it, "past_due") }
                store.session(order.workspaceId)?.let { store.saveSession(it.copy(state = "revoking", authKey = null)) }
            }
        }
    }

    private fun revoke(workspace: UUID) {
        val session = store.session(workspace)?.takeIf { it.state == "revoking" } ?: return
        try {
            session.billingKey?.let(gateway::revoke)
            val owner = accounts.ownerOf(workspace) ?: return
            owned(owner, workspace) {
                val current = store.session(workspace) ?: return@owned
                if (current.id == session.id && current.state == "revoking") {
                    store.saveSession(current.copy(state = "revoked", billingKey = null, authKey = null))
                }
            }
        } catch (_: TossUncertain) {
            // The durable revocation is retried by the worker.
        } catch (_: TossDeclined) {
            // Keep the credential until deletion is confirmed.
        }
    }

    private fun validate(
        order: BillingOrder,
        payment: TossPayment,
    ) {
        if (payment.orderId != order.id || payment.amount != order.amount ||
            payment.remainingAmount !in 0..order.amount
        ) {
            throw TossUncertain()
        }
        if (
            payment.key.reveal().isBlank() ||
            payment.status !in listOf("DONE", "CANCELED", "PARTIAL_CANCELED")
        ) {
            throw TossUncertain()
        }
    }

    private fun expire(
        subscription: Subscription,
        status: String,
    ) {
        subscriptions.save(subscription.copy(status = status))
        credits.setAllowance(
            subscription.workspaceId,
            subscription.ownerId,
            0,
            subscription.cycleEndsAt,
            false,
            CreditReason.CYCLE_END,
            "Toss subscription ended",
        )
    }

    private fun <T> owned(
        owner: UUID,
        workspace: UUID,
        block: () -> T,
    ): T =
        transaction.inTransaction {
            subscriptions.lockOwned(owner, workspace)
            block()
        }

    private fun requireEnabled() {
        if (!enabled) conflict("토스 테스트 결제가 활성화되어 있지 않습니다")
    }

    private fun plan(id: String): SubscriptionPlan =
        when (id) {
            "start" -> SubscriptionPlan(id, "Start", START_CREDITS, START_PRICE)
            else -> throw InvalidInputException("알 수 없는 구독 플랜입니다")
        }

    private companion object {
        const val SESSION_TTL_SECONDS = 600L
        const val AUTH_KEY_MAX_LENGTH = 300
        const val RETRY_SECONDS = 30L
        const val RETRY_MAX_DAYS = 14
        const val START_CREDITS = 50
        const val START_PRICE = 99_000
    }

    private fun conflict(message: String): Nothing = throw ConflictException(message)
}

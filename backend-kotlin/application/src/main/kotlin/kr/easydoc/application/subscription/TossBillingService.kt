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
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Persisted payment environment is checked before every provider operation.
 * Recovery paths share one workspace lock and transaction boundary.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
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
    private val timing: TossBillingTiming = TossBillingTiming(),
    val environment: String = "toss_test",
    val purchaseEnabled: Boolean = enabled,
    val autoChargeEnabled: Boolean = enabled,
    val allowedWorkspaces: Set<UUID> = emptySet(),
) {
    fun owns(workspace: UUID): Boolean =
        subscriptions.find(workspace)?.let { it.provider in listOf("toss_test", "toss_live") }
            ?: (enabled && store.session(workspace) != null)

    fun cardLastFour(workspace: UUID): String? =
        store.session(workspace)?.let {
            it.cardLastFour
                ?: it.previousCardLastFour
        }

    fun latestOrder(workspace: UUID): BillingOrder? = store.latestOrder(workspace)

    fun pending(workspace: UUID): Boolean = store.pending(workspace) || store.session(workspace)?.state == "issuing"

    fun state(workspace: UUID): String? = store.session(workspace)?.state

    fun begin(
        owner: UUID,
        workspace: UUID,
        planId: String,
        purpose: String = "purchase",
        consentVersion: String? = null,
    ): BillingSession =
        owned(owner, workspace) {
            requireEnabled()
            validateRegistrationRequest(
                purpose,
                consentVersion,
                environment,
                purchaseEnabled || workspace in allowedWorkspaces,
            )
            val ownerUser = users.findById(owner)
            requirePaymentEligible(ownerUser, PaymentAction.CARD_REGISTRATION)
            plan(planId)
            val blocked =
                if (purpose ==
                    "replace_card"
                ) {
                    store.replacementBlocked(workspace)
                } else {
                    store.pending(workspace)
                }
            if (blocked) conflict("처리 중인 결제가 있습니다. 결과를 먼저 확인하세요")
            val subscription = subscriptions.find(workspace)
            validateRegistrationSubscription(subscription, purpose, planId, environment, clock.instant())
            var previous = store.session(workspace)
            if (previous?.state == "authorizing" && previous.purpose == "replace_card") {
                restoreReplacement(owner, workspace, previous.id)
                previous = store.session(workspace)
            }
            validatePreviousSession(previous, purpose, environment)
            BillingSession(
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                planId,
                clock.instant().plus(timing.sessionTtl),
                environment = environment,
                purpose = purpose,
                consentVersion = consentVersion ?: previous?.consentVersion,
                consentAt = consentVersion?.let { clock.instant() } ?: previous?.consentAt,
                previousBillingKey = previous?.billingKey,
                previousCustomer = previous?.customer,
                previousCardLastFour = previous?.cardLastFour,
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
                val next = validateBillingCallback(current, id, customer, auth, fail, environment, clock.instant())
                if (next != current) store.saveSession(next)
                next
            }
        if (session.state == "active") {
            process(session.id)
            return
        }
        if (retryWindowElapsed(session.expiresAt)) {
            conflict("카드 등록 결과를 관리자에게 문의하세요")
        }
        val key =
            try {
                gateway.issueCard(auth, Secret(customer.toString()), id)
            } catch (failure: TossDeclined) {
                restoreReplacement(owner, workspace, id)
                throw failure
            }
        owned(owner, workspace) {
            val current = store.session(workspace) ?: conflict("카드 등록을 다시 시작하세요")
            if (current.id != id || current.state !in listOf("issuing", "active")) conflict("카드 등록 상태가 바뀌었습니다")
            if (current.state == "active") return@owned
            store.saveSession(
                current.copy(billingKey = key.key, cardLastFour = key.lastFour, state = "active", authKey = null),
            )
            val subscription = subscriptions.find(workspace)
            if (current.purpose == "replace_card" && subscription != null) {
                replaceSubscriptionCard(current, subscription)
                return@owned // A paid period is preserved; a suspended subscription waits for a new approval.
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
                    environment = environment,
                ),
            )
        }
        process(id)
    }

    private fun replaceSubscriptionCard(
        session: BillingSession,
        subscription: Subscription,
    ) {
        if (subscription.status == "past_due") {
            val now = clock.instant()
            store.saveOrder(
                BillingOrder(
                    session.id,
                    session.workspaceId,
                    session.planId,
                    subscription.monthlyPrice,
                    now,
                    now.atZone(zone).plusMonths(1).toInstant(),
                    kind = "renewal",
                    environment = session.environment,
                ),
            )
        } else {
            subscriptions.save(subscription.copy(status = "active"))
            store.latestOrder(session.workspaceId)?.takeIf { it.status == "scheduled" }?.let {
                store.saveOrder(it.copy(nextAttemptAt = clock.instant()))
            }
        }
    }

    /** Stop future collection while preserving credentials needed to resolve an in-flight result. */
    fun stopRenewal(
        owner: UUID,
        workspace: UUID,
    ) {
        owned(owner, workspace) {
            subscriptions.find(workspace)?.let { subscriptions.save(it.copy(status = "canceling")) }
            store.stopScheduled(workspace)
            store.session(workspace)?.let {
                if (it.state == "issuing") conflict("카드 등록 결과를 먼저 확인하세요")
                store.saveSession(
                    it.copy(state = if (store.replacementBlocked(workspace)) "cancel_requested" else "revoking"),
                )
            }
        }
    }

    fun cancel(
        owner: UUID,
        workspace: UUID,
    ) {
        owned(owner, workspace) {
            if (store.replacementBlocked(workspace)) conflict("처리 중인 결제 결과를 확인한 후 해지하세요")
            store.stopScheduled(workspace)
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
                store.order(id)?.takeIf { it.workspaceId == workspace && it.kind in listOf("charge", "renewal") }
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
        prepareRefund(owner, workspace, originalId, operation, amount)
        process(operation)
    }

    fun prepareRefund(
        owner: UUID,
        workspace: UUID,
        originalId: UUID,
        operation: UUID,
        amount: Int,
    ) {
        owned(owner, workspace) {
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
                store
                    .order(
                        originalId,
                    )?.takeIf { it.workspaceId == workspace && it.kind in listOf("charge", "renewal") }
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
                    environment = original.environment,
                ),
            )
        }
    }

    /** A webhook is only a hint. Amount and status always come from an authenticated Toss GET. */
    fun requestSync(id: UUID) {
        store.requestSync(id)
    }

    /** Operator lookup never creates an external charge or refund. */
    fun recheck(id: UUID) {
        val order = store.order(id) ?: return
        if (order.environment != environment) throw TossUncertain()
        val target = order.originalId ?: order.id
        val payment = gateway.find(target) ?: throw TossUncertain()
        finish(order, payment)
    }

    /** A matching deletion signal blocks future charges, never removes the paid allowance. */
    fun billingDeleted(key: Secret) {
        val session = store.sessionByBillingKey(key, environment) ?: return
        val owner = accounts.ownerOf(session.workspaceId) ?: return
        owned(owner, session.workspaceId) {
            val current = store.session(session.workspaceId) ?: return@owned
            if (current.billingKey == key && current.environment == environment && current.state == "active") {
                store.saveSession(current.copy(state = "needs_card"))
            }
        }
    }

    private fun restoreReplacement(
        owner: UUID,
        workspace: UUID,
        id: UUID,
    ) {
        owned(owner, workspace) {
            val current = store.session(workspace) ?: return@owned
            if (current.id == id && current.previousBillingKey != null &&
                current.state in listOf("authorizing", "issuing")
            ) {
                store.saveSession(
                    current.copy(
                        billingKey = current.previousBillingKey,
                        customer = requireNotNull(current.previousCustomer),
                        cardLastFour = current.previousCardLastFour,
                        previousCardLastFour = null,
                        previousBillingKey = null,
                        previousCustomer = null,
                        authKey = null,
                        state = "active",
                        purpose = "purchase",
                    ),
                )
            }
        }
    }

    fun runDue() {
        store.expiredReplacements(clock.instant()).forEach { workspace ->
            val owner = accounts.ownerOf(workspace) ?: return@forEach
            val session = store.session(workspace) ?: return@forEach
            restoreReplacement(owner, workspace, session.id)
        }
        if (enabled) recoverAuthorizations()
        subscriptions.due(clock.instant()).filter { it.provider == environment }.forEach { candidate ->
            owned(candidate.ownerId, candidate.workspaceId) {
                val current = subscriptions.find(candidate.workspaceId) ?: return@owned
                if (current.cycleEndsAt > clock.instant() || store.pending(current.workspaceId)) return@owned
                if (current.status == "canceling") {
                    expire(current, "expired")
                    return@owned
                }
                if (current.status != "active" || !enabled || !autoChargeEnabled) return@owned
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
                            environment = environment,
                            kind = "renewal",
                        ),
                    )
                }
            }
        }
        store.candidates(clock.instant()).forEach(::process)
        store.cleanupCandidates().forEach(::revoke)
    }

    private fun recoverAuthorizations() {
        val retryCutoff = clock.instant().minus(timing.retryWindow)
        store.authorizationCandidates(retryCutoff).forEach { workspace ->
            val session = store.session(workspace) ?: return@forEach
            val auth = session.authKey ?: return@forEach
            val owner = accounts.ownerOf(workspace) ?: return@forEach
            if (retryWindowElapsed(session.expiresAt)) {
                owned(owner, workspace) {
                    val current = store.session(workspace) ?: return@owned
                    if (current.id == session.id && current.state == "issuing") {
                        store.saveSession(current.copy(state = "manual_review"))
                    }
                }
                return@forEach
            }
            try {
                complete(owner, workspace, session.id, session.customer, auth, session.simulateFailure)
            } catch (
                _: TossUncertain,
            ) {
                // The same session and idempotency key are retried.
            } catch (_: TossDeclined) {
                rejectAuthorization(owner, workspace, session.id)
            }
        }
    }

    private fun rejectAuthorization(
        owner: UUID,
        workspace: UUID,
        id: UUID,
    ) {
        owned(owner, workspace) {
            val current = store.session(workspace) ?: return@owned
            if (current.id == id && current.state == "issuing") {
                if (current.previousBillingKey != null) {
                    restoreReplacement(owner, workspace, current.id)
                } else {
                    store.saveSession(current.copy(state = "revoking", authKey = null))
                }
            }
        }
    }

    private fun waitForCard(
        existing: BillingOrder,
        owner: UUID,
    ): Boolean {
        if (existing.status != "scheduled" || store.session(existing.workspaceId)?.state != "needs_card") return false
        owned(owner, existing.workspaceId) {
            if (store.session(existing.workspaceId)?.state != "needs_card" ||
                store.order(existing.id)?.status != "scheduled"
            ) {
                return@owned
            }
            val first = existing.firstFailureAt ?: existing.createdAt
            val suspendAt = first.plus(SUSPENSION_DELAY)
            if (clock.instant() >= suspendAt) {
                subscriptions.find(existing.workspaceId)?.let { expire(it, "past_due") }
                store.saveOrder(existing.copy(status = "failed"))
            } else {
                store.release(existing.id, suspendAt)
            }
        }
        return true
    }

    private fun deferClaimedOrder(order: BillingOrder): Boolean {
        val next =
            when {
                order.environment != environment -> clock.instant().plus(timing.retryInterval)

                order.status in listOf("pending", "scheduled", "suspend_pending") &&
                    order.nextAttemptAt > clock.instant() -> order.nextAttemptAt

                else -> null
            }
        if (next != null) transaction.inTransaction { store.release(order.id, next) }
        return next != null
    }

    @Suppress("ReturnCount") // Guard exits release claimed work or stop unsafe external effects.
    fun process(id: UUID) {
        val existing = store.order(id) ?: return
        val owner = accounts.ownerOf(existing.workspaceId) ?: return
        if (waitForCard(existing, owner)) return
        val order = owned(owner, existing.workspaceId) { store.claim(id, clock.instant()) } ?: return
        if (deferClaimedOrder(order)) return
        try {
            if (order.status == "suspend_pending") {
                owned(owner, order.workspaceId) {
                    subscriptions.find(order.workspaceId)?.let { expire(it, "past_due") }
                    store.saveOrder(order.copy(status = "failed"))
                }
                return
            }
            val payment = if (order.kind == "refund") refundResult(order) else chargeResult(order)
            finish(order, payment)
        } catch (failure: TossDeclined) {
            failed(order, failure.requiresCard)
        } catch (_: TossUncertain) {
            transaction.inTransaction {
                if (retryWindowElapsed(order.createdAt) &&
                    order.status in listOf("pending", "processing", "scheduled")
                ) {
                    store.saveOrder(order.copy(status = "manual_review"))
                } else {
                    store.release(id, clock.instant().plus(timing.retryInterval))
                }
            }
        }
    }

    private fun chargeAllowed(order: BillingOrder): Boolean =
        enabled &&
            when (order.kind) {
                "renewal" -> autoChargeEnabled
                "charge" -> purchaseEnabled || order.workspaceId in allowedWorkspaces
                else -> false
            }

    @Suppress("ThrowsCount") // Missing durable credentials must stop an external charge.
    private fun chargeResult(order: BillingOrder): TossPayment {
        gateway.find(order.id)?.let { return it }
        if (order.status !in listOf("pending", "processing", "scheduled") ||
            retryWindowElapsed(order.createdAt)
        ) {
            throw TossUncertain()
        }
        if (!chargeAllowed(order)) throw TossUncertain()
        val session = store.session(order.workspaceId) ?: throw TossUncertain()
        if (session.environment != order.environment) throw TossUncertain()
        if (session.state in listOf("needs_card", "revoked", "revoking", "cancel_requested")) throw TossUncertain()
        val replacing = session.purpose == "replace_card" && session.state != "active"
        if (replacing) throw TossUncertain()
        val key = session.billingKey ?: throw TossUncertain()
        val customer = session.customer
        return gateway.charge(
            key,
            Secret(customer.toString()),
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
            retryWindowElapsed(order.createdAt)
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
            if (latest.status in listOf("pending", "processing", "scheduled", "manual_review") &&
                payment.status == "DONE"
            ) {
                grantApprovedPeriod(original, payment, owner)
            }
            // A slower status lookup must never undo a refund already reconciled in another transaction.
            val effective = latest.payment?.takeIf { it.remainingAmount < payment.remainingAmount } ?: payment
            val endsAt = (effective.approvedAt ?: clock.instant()).atZone(zone).plusMonths(1).toInstant()
            store.saveOrder(
                latest.copy(
                    status = "paid",
                    payment = effective,
                    cycleEndsAt = endsAt,
                    nextAttemptAt = clock.instant().plus(Duration.ofDays(1)),
                ),
            )
            store.savePayment(original, effective)
            store.session(order.workspaceId)?.takeIf { it.state == "cancel_requested" }?.let {
                store.saveSession(it.copy(state = "revoking", authKey = null))
            }
            if (order.kind == "refund") store.saveOrder(order.copy(status = "paid", payment = payment))
        }
    }

    private fun grantApprovedPeriod(
        original: BillingOrder,
        payment: TossPayment,
        owner: UUID,
    ) {
        val selected = plan(original.planId)
        val subscription =
            Subscription(
                original.workspaceId,
                owner,
                selected.id,
                selected.allowance,
                original.amount,
                if (subscriptions.find(original.workspaceId)?.status == "canceling" ||
                    store.session(original.workspaceId)?.state == "cancel_requested"
                ) {
                    "canceling"
                } else {
                    "active"
                },
                (payment.approvedAt ?: clock.instant()).atZone(zone).plusMonths(1).toInstant(),
                original.environment,
            )
        subscriptions.save(subscription)
        credits.setPaidAllowance(
            subscription.workspaceId,
            owner,
            selected.allowance,
            subscription.cycleEndsAt,
            CreditReason.PLAN_MONTHLY,
            "Toss subscription",
            original.id,
        )
    }

    private fun failed(
        order: BillingOrder,
        requiresCard: Boolean,
    ) {
        val owner = accounts.ownerOf(order.workspaceId) ?: return
        owned(owner, order.workspaceId) {
            val current = store.order(order.id) ?: return@owned
            if (current.status !in listOf("pending", "processing", "scheduled")) return@owned
            store.saveOrder(current.copy(status = "failed"))
            if (order.kind in listOf("charge", "renewal")) {
                if (requiresCard) {
                    store.session(order.workspaceId)?.let {
                        store.saveSession(it.copy(state = "needs_card"))
                    }
                }
                subscriptions.record(
                    SubscriptionPayment(
                        order.id,
                        order.workspaceId,
                        order.planId,
                        order.amount,
                        "failed",
                        order.createdAt,
                        order.simulateFailure,
                        order.environment,
                    ),
                )
                if (order.kind == "charge") {
                    store.session(order.workspaceId)?.let {
                        store.saveSession(it.copy(state = "revoking", authKey = null))
                    }
                    return@owned
                }
                val first = order.firstFailureAt ?: clock.instant()
                val delays = RETRY_DELAYS
                if (order.attempt < delays.size) {
                    val next = first.plus(delays[order.attempt])
                    store.saveOrder(
                        order.copy(
                            id = UUID.nameUUIDFromBytes("${order.cycleId}:${order.attempt + 1}".toByteArray()),
                            status = "scheduled",
                            payment = null,
                            attempt = order.attempt + 1,
                            firstFailureAt = first,
                            createdAt = clock.instant(),
                            nextAttemptAt = next,
                        ),
                    )
                } else {
                    store.saveOrder(
                        order.copy(
                            status = "suspend_pending",
                            firstFailureAt = first,
                            nextAttemptAt = first.plus(SUSPENSION_DELAY),
                        ),
                    )
                }
            }
        }
    }

    fun retryCardDeletion(workspace: UUID) = revoke(workspace)

    @Suppress("ReturnCount") // Environment and durable cleanup-state guards prevent deleting an unrelated key.
    private fun revoke(workspace: UUID) {
        val session = store.session(workspace) ?: return
        if (session.environment != environment) return
        val previous = session.previousBillingKey
        if (session.state != "revoking" &&
            !(session.state in listOf("active", "needs_card") && previous != null)
        ) {
            return
        }
        try {
            if (session.state == "revoking") session.billingKey?.let(gateway::revoke)
            previous?.let(gateway::revoke)
            val owner = accounts.ownerOf(workspace) ?: return
            owned(owner, workspace) {
                val current = store.session(workspace) ?: return@owned
                if (current.id == session.id && current.state == session.state) {
                    store.saveSession(
                        if (current.state == "revoking") {
                            current.copy(
                                state = "revoked",
                                cardLastFour = null,
                                previousCardLastFour = null,
                                billingKey = null,
                                authKey = null,
                                previousBillingKey = null,
                                previousCustomer = null,
                            )
                        } else {
                            current.copy(
                                previousBillingKey = null,
                                previousCustomer = null,
                                previousCardLastFour = null,
                            )
                        },
                    )
                }
            }
        } catch (_: TossUncertain) {
            // The durable revocation is retried by the worker.
        } catch (_: TossDeclined) {
            // Keep the credential until deletion is confirmed.
        }
    }

    @Suppress("ThrowsCount") // Each mismatch rejects an untrusted provider result independently.
    private fun validate(
        order: BillingOrder,
        payment: TossPayment,
    ) {
        if (order.environment == "toss_live" && payment.approvedAt == null) throw TossUncertain()
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
        if (!enabled) conflict("토스 결제가 활성화되어 있지 않습니다")
    }

    private fun plan(id: String): SubscriptionPlan = SubscriptionPlanCatalog.require(id)

    private fun retryWindowElapsed(start: Instant): Boolean =
        Duration.between(start, clock.instant()) >= timing.retryWindow

    private companion object {
        const val SUSPENSION_DAYS = 14L
        const val SECOND_RETRY_DAYS = 3L
        const val THIRD_RETRY_DAYS = 7L
        val SUSPENSION_DELAY: Duration = Duration.ofDays(SUSPENSION_DAYS)
        val RETRY_DELAYS: List<Duration> =
            listOf(Duration.ofHours(1), Duration.ofDays(SECOND_RETRY_DAYS), Duration.ofDays(THIRD_RETRY_DAYS))
    }

    private fun conflict(message: String): Nothing = throw ConflictException(message)
}

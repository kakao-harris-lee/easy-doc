package kr.easydoc.application.admin

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossBillingStore
import kr.easydoc.application.subscription.TossUncertain
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Suppress("LongParameterList")
data class AdminRefundCommand(
    val operationId: UUID,
    val workspaceId: UUID,
    val paymentId: UUID,
    val actorUserId: UUID,
    val amount: Int,
    val recoveryCredits: BigDecimal,
    val stopRenewal: Boolean,
    val reason: String,
    val expectedRevision: Long,
) {
    override fun toString(): String = "AdminRefundCommand(operationId=$operationId, reason=[MASKED])"
}

data class AdminRefundOperation(
    val command: AdminRefundCommand,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminBillingAction(
    val workspace: UUID,
    val actor: UUID,
    val id: UUID,
    val action: String,
    val reason: String,
    val target: UUID?,
) {
    override fun toString(): String = "AdminBillingAction(id=$id, action=$action, reason=[MASKED])"
}

data class AdminBillingActionView(
    val id: UUID,
    val action: String,
    val status: String,
    val reason: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "AdminBillingActionView(id=$id, status=$status, reason=[MASKED])"
}

data class AdminBillingOrder(
    val id: UUID,
    val kind: String,
    val status: String,
    val amount: Int,
    val createdAt: Instant,
    val environment: String,
    val needsReview: Boolean,
    val canSync: Boolean = false,
    val syncBlockedReason: String? = null,
)

@Suppress("TooManyFunctions") // Refund and management action recovery share one workspace transaction boundary.
interface AdminBillingStore {
    fun lock(workspace: UUID)

    fun operation(id: UUID): AdminRefundOperation?

    fun reserve(command: AdminRefundCommand)

    fun settle(
        operation: AdminRefundOperation,
        succeeded: Boolean,
    )

    fun pending(): List<UUID>

    fun operations(workspace: UUID): List<AdminRefundOperation>

    fun orders(workspace: UUID): List<AdminBillingOrder>

    @Suppress("LongParameterList") // Identity, actor and target are all required to bind immutable audit replay.
    fun audit(
        workspace: UUID,
        actor: UUID,
        operation: UUID,
        action: String,
        reason: String,
        target: UUID?,
        expectedRevision: Long? = null,
    ): Boolean

    fun pendingActions(): List<AdminBillingAction>

    fun finishAction(id: UUID)

    fun actions(workspace: UUID): List<AdminBillingActionView>
}

@Suppress("LongParameterList", "TooManyFunctions") // One facade coordinates reads, commands and durable recovery.
class AdminBillingService(
    private val store: AdminBillingStore,
    private val billing: TossBillingService,
    private val orders: TossBillingStore,
    private val accounts: CreditAccountRepository,
    private val transaction: TransactionRunner,
) {
    fun orders(workspace: UUID): List<AdminBillingOrder> {
        owner(workspace)
        return store.orders(workspace).map {
            val allowed = it.environment == billing.environment && it.status != "scheduled"
            it.copy(canSync = allowed, syncBlockedReason = if (allowed) null else "현재 결제 환경과 상태를 확인하세요")
        }
    }

    fun operations(workspace: UUID): List<AdminRefundOperation> = store.operations(workspace)

    fun actions(workspace: UUID): List<AdminBillingActionView> = store.actions(workspace)

    fun refund(input: AdminRefundCommand): AdminRefundOperation {
        validate(input)
        val command = input.copy(recoveryCredits = input.recoveryCredits.setScale(1))
        transaction.inTransaction {
            store.lock(command.workspaceId)
            val previous = store.operation(command.operationId)
            if (previous != null) {
                if (previous.command != command) throw ConflictException("같은 요청 번호로 다른 환불을 요청할 수 없습니다")
            } else {
                // Both preparation and reservation commit before the first external request.
                billing.prepareRefund(
                    owner(command.workspaceId),
                    command.workspaceId,
                    command.paymentId,
                    command.operationId,
                    command.amount,
                )
                store.reserve(command)
            }
        }
        reconcile(command.operationId)
        return checkNotNull(store.operation(command.operationId))
    }

    fun recover() {
        store.pending().forEach { safelyRecover { reconcile(it) } }
        store.pendingActions().forEach { safelyRecover { performAction(it) } }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun safelyRecover(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            LoggerFactory
                .getLogger(
                    javaClass,
                ).warn("Billing operation recovery deferred; processing remaining operations")
        }
    }

    private fun reconcile(id: UUID) {
        val operation = store.operation(id) ?: return
        if (operation.status != "pending") return
        if (orders.order(id)?.status !in setOf("paid", "failed")) billing.process(id)
        transaction.inTransaction {
            store.lock(operation.command.workspaceId)
            val current = store.operation(id) ?: return@inTransaction
            if (current.status != "pending") return@inTransaction
            when (orders.order(id)?.status) {
                "paid" -> store.settle(current, true)
                "failed" -> store.settle(current, false)
            }
        }
    }

    fun action(
        workspace: UUID,
        actor: UUID,
        request: UUID,
        action: String,
        reason: String,
        target: UUID? = null,
        expectedRevision: Long? = null,
    ) {
        requireReason(reason)
        if (expectedRevision != null && expectedRevision < 0) throw InvalidInputException("확인 버전을 확인하세요")
        val pending =
            transaction.inTransaction {
                store.lock(workspace)
                if (target != null &&
                    orders.order(target)?.workspaceId != workspace
                ) {
                    throw NotFoundException("결제 내역이 없습니다")
                }
                store.audit(workspace, actor, request, action, reason, target, expectedRevision)
            }
        if (pending) performAction(AdminBillingAction(workspace, actor, request, action, reason, target))
    }

    private fun performAction(command: AdminBillingAction) {
        try {
            when (command.action) {
                "sync" -> billing.recheck(checkNotNull(command.target))
                "stop_renewal" -> billing.stopRenewal(owner(command.workspace), command.workspace)
                "retry_card_deletion" -> billing.retryCardDeletion(command.workspace)
                else -> throw InvalidInputException("알 수 없는 관리 작업입니다")
            }
        } catch (_: TossUncertain) {
            return // The accepted operation stays pending for worker reconciliation.
        }
        val session = orders.session(command.workspace)
        val deletionPending = session?.state == "revoking" || session?.previousBillingKey != null
        if (command.action != "retry_card_deletion" || !deletionPending) {
            transaction.inTransaction { store.finishAction(command.id) }
        }
    }

    private fun owner(workspace: UUID): UUID =
        accounts.ownerOf(workspace) ?: throw NotFoundException("작업 공간을 찾을 수 없습니다")

    private fun validate(command: AdminRefundCommand) {
        requireReason(command.reason)
        if (command.amount <= 0 || command.expectedRevision < 0 || command.recoveryCredits.signum() < 0) {
            throw InvalidInputException("환불 금액, 회수량과 확인 버전을 확인하세요")
        }
        try {
            command.recoveryCredits.setScale(1, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) {
            throw InvalidInputException("회수 크레딧은 0.1 단위여야 합니다")
        }
    }

    private fun requireReason(reason: String) {
        if (reason.isBlank() || reason.length > MAX_REASON_LENGTH) {
            throw InvalidInputException("처리 사유를 1~${MAX_REASON_LENGTH}자로 입력하세요")
        }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 200
    }
}

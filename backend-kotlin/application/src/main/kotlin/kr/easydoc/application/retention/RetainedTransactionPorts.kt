package kr.easydoc.application.retention

import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 법정 보존 원장의 분류. 보존 기간은 DB 레코드의 [retainedUntil]에 고정한다. */
enum class RetainedRecordType(val wireName: String) {
    PAYMENT("payment"),
    REFUND("refund"),
    SUPPLY("supply"),
    CONSENT("consent"),
    COMPLAINT("complaint"),

    ;

    companion object {
        fun fromWire(value: String): RetainedRecordType =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown retained record type: $value")
    }
}

data class RetainedTransactionSummary(
    val id: UUID,
    val type: RetainedRecordType,
    val occurredAt: Instant?,
    val retainedUntil: Instant,
    val paymentId: UUID?,
    val environment: String?,
    val manualReview: Boolean = false,
)

@kr.easydoc.core.privacy.UserContent
data class RetainedTransactionDecision(
    val operationId: UUID,
    val type: String,
    val actorUserId: UUID?,
    val status: String,
    val amount: Int?,
    val recoveryCredits: BigDecimal?,
    val reason: String,
    val stopRenewal: Boolean?,
    val receivedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "RetainedTransactionDecision(operationId=$operationId, content=[REDACTED])"
}

@kr.easydoc.core.privacy.UserContent
data class RetainedTransactionDetail(
    val summary: RetainedTransactionSummary,
    /** JSON object text; the API layer parses it without leaking ciphertext. */
    val payloadJson: String,
    val decisions: List<RetainedTransactionDecision>,
) {
    override fun toString(): String = "RetainedTransactionDetail(id=${summary.id}, content=[REDACTED])"
}

data class RetainedTransactionFilter(
    val type: RetainedRecordType? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

@kr.easydoc.core.privacy.UserContent
data class RefundDecisionCommand(
    val operationId: UUID,
    val status: String,
    val amount: Int,
    val recoveryCredits: BigDecimal,
    val reason: String,
    val stopRenewal: Boolean,
    val receivedAt: Instant,
) {
    override fun toString(): String = "RefundDecisionCommand(operationId=$operationId, content=[REDACTED])"
}

@kr.easydoc.core.privacy.UserContent
data class ComplaintDecisionCommand(
    val operationId: UUID,
    val status: String,
    val reason: String,
    val receivedAt: Instant,
) {
    override fun toString(): String = "ComplaintDecisionCommand(operationId=$operationId, content=[REDACTED])"
}

/** 탈퇴와 결제 확정이 법정 원장에 요구하는 저장 경계. 구현은 한 DB 트랜잭션에 참여한다. */
interface RetainedTransactionArchiver {
    /** 사용자 삭제 전에 모든 라이브 확정 거래를 멱등적으로 보존하고, 누락이면 실패한다. */
    fun archiveForUser(userId: UUID)

    /** 결제 확정/환불 확정 시점에 호출한다. 호출부의 트랜잭션에 참여해야 한다. */
    fun archivePayment(
        order: BillingOrder,
        payment: TossPayment,
        previousRefundedAmount: Int,
        customerEmail: String?,
        consent: BillingSession?,
    )
}

interface RetainedTransactionAdminRepository {
    fun list(
        filter: RetainedTransactionFilter,
        page: Int,
        size: Int,
    ): Map<String, Any?>

    fun find(id: UUID): RetainedTransactionDetail?

    fun refundDecision(
        id: UUID,
        actor: UUID,
        command: RefundDecisionCommand,
    ): RetainedTransactionDetail

    fun complaintDecision(
        id: UUID,
        actor: UUID,
        command: ComplaintDecisionCommand,
    ): RetainedTransactionDetail
}

interface RetainedTransactionPurgeStore {
    fun purge(
        expiredBefore: Instant,
        batchSize: Int,
        dryRun: Boolean,
    ): Int
}

/** 관리자 원장 조회/심사 입력의 유스케이스 경계. */
class RetainedTransactionAdminService(
    private val repository: RetainedTransactionAdminRepository,
    private val transaction: kr.easydoc.application.auth.TransactionRunner,
    private val clock: java.time.Clock,
) {
    fun list(
        filter: RetainedTransactionFilter,
        page: Int,
        size: Int,
    ): Map<String, Any?> = repository.list(filter, page, size)

    fun find(id: UUID): RetainedTransactionDetail =
        repository.find(id) ?: throw kr.easydoc.core.exceptions
            .NotFoundException("보존 거래 기록을 찾을 수 없습니다")

    fun refundDecision(
        id: UUID,
        actor: UUID,
        command: RefundDecisionCommand,
    ): RetainedTransactionDetail {
        validateRefund(command)
        return transaction.inTransaction { repository.refundDecision(id, actor, command) }
    }

    fun complaintDecision(
        id: UUID,
        actor: UUID,
        command: ComplaintDecisionCommand,
    ): RetainedTransactionDetail {
        validateComplaint(command)
        return transaction.inTransaction { repository.complaintDecision(id, actor, command) }
    }

    @Suppress("ThrowsCount") // Distinct invalid decision fields return actionable validation errors.
    private fun validateRefund(command: RefundDecisionCommand) {
        requireReason(command.reason)
        if (command.status !in REFUND_STATUSES || command.operationId == UUID(0, 0) ||
            command.receivedAt > clock.instant()
        ) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("환불 심사 정보를 확인하세요")
        }
        if (command.status == "approved" && command.amount <= 0) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("승인된 환불 금액을 확인하세요")
        }
        if (command.status == "rejected" && command.amount != 0) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("거절된 환불 금액은 0이어야 합니다")
        }
        if (command.amount < 0 || command.recoveryCredits.signum() < 0 ||
            command.recoveryCredits.stripTrailingZeros().scale() > 1
        ) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("환불 금액과 회수 크레딧을 확인하세요")
        }
    }

    private fun validateComplaint(command: ComplaintDecisionCommand) {
        requireReason(command.reason)
        if (command.status !in COMPLAINT_STATUSES || command.operationId == UUID(0, 0) ||
            command.receivedAt > clock.instant()
        ) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("민원 심사 정보를 확인하세요")
        }
    }

    private fun requireReason(reason: String) {
        if (reason.isBlank() || reason.length > MAX_REASON_LENGTH) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("처리 사유를 1~${MAX_REASON_LENGTH}자로 입력하세요")
        }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 200
        val REFUND_STATUSES = setOf("approved", "rejected")
        val COMPLAINT_STATUSES = setOf("resolved", "rejected")
    }
}

data class RetainedTransactionPurgePolicy(
    val enabled: Boolean,
    val dryRun: Boolean,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "보존 거래 파기 배치 크기가 1보다 작습니다" }
    }
}

data class RetainedTransactionPurgeResult(
    val enabled: Boolean,
    val dryRun: Boolean,
    val deleted: Int,
)

class PurgeRetainedTransactionRecords(
    private val store: RetainedTransactionPurgeStore,
    private val transaction: kr.easydoc.application.auth.TransactionRunner,
    private val policy: RetainedTransactionPurgePolicy,
    private val clock: java.time.Clock,
) {
    fun run(): RetainedTransactionPurgeResult {
        if (!policy.enabled) return RetainedTransactionPurgeResult(false, policy.dryRun, 0)
        val deleted =
            transaction.inTransaction {
                store.purge(clock.instant(), policy.batchSize, policy.dryRun)
            }
        return RetainedTransactionPurgeResult(true, policy.dryRun, deleted)
    }
}

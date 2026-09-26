package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 블록을 그대로 실행하되 **예외가 나가면 작업 표를 되돌린다** — PostgreSQL 의 전부-아니면-전무를
 * 모사한다. 이 되돌림이 없으면 「잔액 부족 402 는 작업 행을 남기지 않는다」 같은 단언이 대역에서만
 * 거짓이 되어, 접수의 잠금 순서를 바꾸는 편집이 실제 동작과 무관하게 빨개진다.
 */
internal class DirectTransaction(private val jobs: FakeActionGuideJobs? = null) : TransactionRunner {
    var depth = 0

    // 도메인 예외는 전부 RuntimeException 이고, 어느 갈래로 끊기든 되돌림은 같다 — 갈래를 좁히면
    // 새 예외 타입이 조용히 되돌림을 건너뛴다.
    @Suppress("TooGenericExceptionCaught")
    override fun <T> inTransaction(block: () -> T): T {
        val snapshot = jobs?.rows?.toMap()
        depth += 1
        return try {
            block()
        } catch (failure: RuntimeException) {
            if (snapshot != null) {
                jobs.rows.clear()
                jobs.rows.putAll(snapshot)
            }
            throw failure
        } finally {
            depth -= 1
        }
    }
}

internal open class FakeActionGuideJobs(var context: ActionGuideJobContext? = defaultContext()) :
    ActionGuideJobRepository {
    val rows = linkedMapOf<UUID, StoredActionGuideJob>()
    var acquired: ActionGuideJobAcquire = ActionGuideJobAcquire.Empty
    var held = true
    var currentInput = true
    var currentInputCheck: (() -> Boolean)? = null
    var terminalWriteSucceeds = true

    /** 저장소가 끊긴 정산 쓰기. `false` 반환(경쟁에 졌다)과 달리 예외가 밖으로 나간다. */
    var terminalWriteFailure: RuntimeException? = null
    val terminalWrites = mutableListOf<Pair<ActionGuideJobStatus, ActionGuideJobFailureCode?>>()

    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideJobContext? = context

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredActionGuideJob? =
        rows.values.singleOrNull {
            it.ownerId == ownerId && it.conversionId == conversionId && it.requestId == requestId
        }

    /**
     * 잠금 밖의 경쟁을 흉내 내는 자리 — INSERT 직전에 다른 트랜잭션이 행을 먼저 넣은 상황을
     * 테스트가 여기서 만든다(저장소의 `RequestConflict` 갈래).
     */
    var beforeInsert: (() -> Unit)? = null

    // 갈래마다 끊는다 — 저장소(JdbcActionGuideJobRepository.insert)와 같은 형태여야 서비스 계약을
    // 이 대역으로 확인할 수 있다.
    @Suppress("ReturnCount")
    override fun insert(job: StoredActionGuideJob): ActionGuideJobInsert {
        // D04: 문서당 상한은 provider 호출이 실제로 시작된 작업만 센다. 저장소(JdbcActionGuideJobRepository)의
        // `provider_started_at IS NOT NULL` 집계와 같은 규칙이어야 서비스 계약을 이 대역으로 확인할 수 있다.
        val started =
            rows.values.count {
                it.ownerId == job.ownerId && it.conversionId == job.conversionId && it.providerStartedAt != null
            }
        if (started >= MAX_PROVIDER_STARTED_ATTEMPTS) return ActionGuideJobInsert.AttemptLimit
        beforeInsert?.invoke()
        // 갈래 순서도 저장소와 같다 — INSERT 가 막히면 같은 요청 키가 먼저인지를 보고, 아니면
        // 계정당 활성 작업 하나(`uq_action_guide_jobs_active_owner`)에 걸린 것이다.
        if (findByRequestId(job.ownerId, job.conversionId, job.requestId) != null) {
            return ActionGuideJobInsert.RequestConflict
        }
        if (rows.values.any { it.ownerId == job.ownerId && it.status.active }) {
            return ActionGuideJobInsert.ActiveConflict
        }
        rows[job.jobId] = job
        return ActionGuideJobInsert.Inserted(job)
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideJob? = rows[jobId]?.takeIf { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob? =
        rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId && it.status.active }

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob? = rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun findActiveOwnedForOperation(
        ownerId: UUID,
        conversionId: UUID,
        operation: ActionGuideOperation,
    ): StoredActionGuideJob? =
        rows.values.lastOrNull {
            it.ownerId == ownerId && it.conversionId == conversionId && it.operation == operation && it.status.active
        }

    override fun findLatestOwnedForOperation(
        ownerId: UUID,
        conversionId: UUID,
        operation: ActionGuideOperation,
    ): StoredActionGuideJob? =
        rows.values.lastOrNull {
            it.ownerId == ownerId && it.conversionId == conversionId && it.operation == operation
        }

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): ActionGuideJobAcquire = acquired

    override fun lockIfHeld(lease: ActionGuideJobLease): StoredActionGuideJob? = if (held) rows[lease.jobId] else null

    override fun hasCurrentInput(
        lease: ActionGuideJobLease,
        basedOnContentRevision: Long,
    ): Boolean = currentInputCheck?.invoke() ?: currentInput

    override fun markProviderStarted(
        lease: ActionGuideJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean {
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] =
            current.copy(
                status = ActionGuideJobStatus.RUNNING,
                executionId = executionId,
                providerStartedAt = startedAt,
                updatedAt = startedAt,
            )
        return true
    }

    override fun markSucceeded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, ActionGuideJobStatus.SUCCEEDED, null, updatedAt)

    override fun markFailed(
        lease: ActionGuideJobLease,
        failureCode: ActionGuideJobFailureCode,
        updatedAt: Instant,
    ): Boolean = terminal(lease, ActionGuideJobStatus.FAILED, failureCode, updatedAt)

    override fun markSuperseded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, ActionGuideJobStatus.SUPERSEDED, null, updatedAt)

    @Suppress("ReturnCount")
    private fun terminal(
        lease: ActionGuideJobLease,
        status: ActionGuideJobStatus,
        failureCode: ActionGuideJobFailureCode?,
        updatedAt: Instant,
    ): Boolean {
        terminalWriteFailure?.let { throw it }
        if (!terminalWriteSucceeds) return false
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] = current.copy(status = status, failureCode = failureCode, updatedAt = updatedAt)
        terminalWrites += status to failureCode
        return true
    }
}

internal class RecordingActionGuideCredits : ActionGuideCreditPort {
    var available = BigDecimal("7.0")
    var reservation: ActionGuideCreditReservation = ActionGuideCreditReservation.Reserved(available)
    var reserveCalls = 0
    var consumes = 0
    var releases = 0

    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal = available

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): ActionGuideCreditReservation {
        reserveCalls += 1
        return reservation
    }

    override fun consume(job: StoredActionGuideJob) {
        consumes += 1
    }

    override fun release(job: StoredActionGuideJob) {
        releases += 1
    }
}

internal class RecordingActionGuideLedger : ActionGuideLlmCallLedger {
    var starts = 0
    var completes = 0
    var unknowns = 0

    override fun start(
        job: StoredActionGuideJob,
        executionId: UUID,
        startedAt: Instant,
    ) {
        starts += 1
    }

    override fun complete(
        job: StoredActionGuideJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) {
        completes += 1
    }

    override fun markOutcomeUnknown(
        job: StoredActionGuideJob,
        executionId: UUID,
        recoveredAt: Instant,
    ) {
        unknowns += 1
    }
}

internal fun defaultContext() = ActionGuideJobContext(WORKSPACE, DOCUMENT, CONVERSION, 3, null, 1_500, completed = true)

internal fun storedJob(
    status: ActionGuideJobStatus = ActionGuideJobStatus.QUEUED,
    requestId: UUID = REQUEST,
    executionId: UUID? = null,
    providerStartedAt: Instant? = null,
    jobId: UUID = JOB,
) = StoredActionGuideJob(
    jobId,
    OWNER,
    WORKSPACE,
    DOCUMENT,
    CONVERSION,
    requestId,
    null,
    3,
    BigDecimal("2.0"),
    status,
    null,
    executionId,
    providerStartedAt,
    NOW,
    NOW,
)

internal val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
internal val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
internal val DOCUMENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
internal val CONVERSION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
internal val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")
internal val JOB: UUID = UUID.fromString("00000000-0000-0000-0000-000000000006")
internal val EXECUTION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000007")
internal val NOW: Instant = Instant.parse("2026-09-18T00:00:00Z")

/** D04(개선 로드맵 §6)의 문서당 시도 상한. 저장소의 `MAX_ATTEMPTS_PER_CONVERSION`과 같은 값이다. */
internal const val MAX_PROVIDER_STARTED_ATTEMPTS: Int = 3

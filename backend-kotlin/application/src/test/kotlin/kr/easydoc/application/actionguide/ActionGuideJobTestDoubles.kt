package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.llm.LlmCallRecord
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class DirectTransaction : TransactionRunner {
    var depth = 0

    override fun <T> inTransaction(block: () -> T): T {
        depth += 1
        return try {
            block()
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

    override fun insert(job: StoredActionGuideJob): ActionGuideJobInsert {
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

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
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
        if (!terminalWriteSucceeds) return false
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] = current.copy(status = status, failureCode = failureCode, updatedAt = updatedAt)
        terminalWrites += status to failureCode
        return true
    }
}

internal class RecordingActionGuideCredits : ActionGuideCreditPort {
    var available = 7
    var reservation: ActionGuideCreditReservation = ActionGuideCreditReservation.Reserved(available)
    var reserveCalls = 0
    var consumes = 0
    var releases = 0

    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): Int = available

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
) = StoredActionGuideJob(
    JOB,
    OWNER,
    WORKSPACE,
    DOCUMENT,
    CONVERSION,
    requestId,
    null,
    3,
    2,
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

package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import java.time.Clock
import java.util.UUID

enum class ActionGuideJobOutcome {
    IDLE,
    COMPLETED,
    FAILED,
    RECOVERED_UNKNOWN,
    DROPPED,
}

/**
 * 별도 행동 안내문 worker 수직 흐름.
 *
 * provider 호출 시작은 트랜잭션에서 먼저 영속화하고, 호출 자체만 트랜잭션 밖에서 수행한다.
 * 시작된 리스가 만료되면 자동 재호출하지 않고 [ActionGuideJobFailureCode.OUTCOME_UNKNOWN]으로 정산한다.
 */
@Suppress("LongParameterList") // worker의 저장·원장·예약 경계를 생성자에서 명시적으로 조립한다.
class ProcessActionGuideJob(
    private val jobs: ActionGuideJobRepository,
    private val credits: ActionGuideCreditPort,
    private val ledger: ActionGuideLlmCallLedger,
    private val runner: ActionGuideJobRunner,
    private val transaction: TransactionRunner,
    private val policy: ActionGuideJobWorkerPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun processNext(): ActionGuideJobOutcome =
        when (val acquired = transaction.inTransaction { jobs.acquire(policy.owner, policy.leaseDuration) }) {
            ActionGuideJobAcquire.Empty -> ActionGuideJobOutcome.IDLE
            is ActionGuideJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is ActionGuideJobAcquire.Held -> run(acquired.lease)
        }

    /** 기능을 끈 동안 미시작 예약을 반환하되, 이미 시작한 호출은 불명확 정산한다. */
    fun drainNext(): ActionGuideJobOutcome =
        when (val acquired = transaction.inTransaction { jobs.acquire(policy.owner, policy.leaseDuration) }) {
            ActionGuideJobAcquire.Empty -> ActionGuideJobOutcome.IDLE
            is ActionGuideJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is ActionGuideJobAcquire.Held -> supersedeUnstarted(acquired.lease)
        }

    private fun run(lease: ActionGuideJobLease): ActionGuideJobOutcome =
        when (val start = start(lease)) {
            StartResult.Dropped -> ActionGuideJobOutcome.DROPPED
            StartResult.Superseded -> ActionGuideJobOutcome.COMPLETED
            is StartResult.Started -> runStarted(lease, start)
        }

    private fun runStarted(
        lease: ActionGuideJobLease,
        started: StartResult.Started,
    ): ActionGuideJobOutcome {
        val record =
            try {
                runner.run(started.job).also {
                    require(it.purpose == LlmCallPurpose.ACTION_GUIDE) {
                        "행동 안내문 runner가 다른 원장 목적을 반환했습니다"
                    }
                }
            } catch (_: RuntimeException) {
                return settleUnknown(lease, started.executionId, ActionGuideJobOutcome.FAILED)
            }
        return settle(lease, started.executionId, record)
    }

    private fun start(lease: ActionGuideJobLease): StartResult =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction StartResult.Dropped
            if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision)) {
                if (!jobs.markSuperseded(lease, clock.instant())) return@inTransaction StartResult.Dropped
                credits.release(job)
                return@inTransaction StartResult.Superseded
            }
            val executionId = UUID.randomUUID()
            val startedAt = clock.instant()
            if (!jobs.markProviderStarted(lease, executionId, startedAt)) return@inTransaction StartResult.Dropped
            ledger.start(job, executionId, startedAt)
            StartResult.Started(job, executionId)
        }

    private fun settle(
        lease: ActionGuideJobLease,
        executionId: UUID,
        record: kr.easydoc.core.llm.LlmCallRecord,
    ): ActionGuideJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction ActionGuideJobOutcome.DROPPED
            when (record.outcome) {
                LlmCallOutcome.COMPLETED -> {
                    if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision)) {
                        if (!jobs.markSuperseded(lease, clock.instant())) {
                            return@inTransaction ActionGuideJobOutcome.DROPPED
                        }
                        ledger.complete(job, executionId, record)
                        credits.release(job)
                        ActionGuideJobOutcome.COMPLETED
                    } else {
                        if (!jobs.markSucceeded(lease, clock.instant())) {
                            return@inTransaction ActionGuideJobOutcome.DROPPED
                        }
                        ledger.complete(job, executionId, record)
                        credits.consume(job)
                        ActionGuideJobOutcome.COMPLETED
                    }
                }

                LlmCallOutcome.PROVIDER_ERROR -> {
                    if (!jobs.markFailed(lease, ActionGuideJobFailureCode.GENERATION_FAILED, clock.instant())) {
                        return@inTransaction ActionGuideJobOutcome.DROPPED
                    }
                    ledger.complete(job, executionId, record)
                    credits.release(job)
                    ActionGuideJobOutcome.FAILED
                }

                LlmCallOutcome.IN_PROGRESS,
                LlmCallOutcome.OUTCOME_UNKNOWN,
                -> {
                    settleUnknownHeld(lease, job, executionId, ActionGuideJobOutcome.FAILED)
                }
            }
        }

    private fun recoverUnknown(lease: ActionGuideJobLease): ActionGuideJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction ActionGuideJobOutcome.DROPPED
            val executionId = job.executionId ?: return@inTransaction ActionGuideJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, ActionGuideJobOutcome.RECOVERED_UNKNOWN)
        }

    private fun supersedeUnstarted(lease: ActionGuideJobLease): ActionGuideJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction ActionGuideJobOutcome.DROPPED
            if (job.providerStartedAt != null || job.executionId != null) {
                val executionId = job.executionId ?: return@inTransaction ActionGuideJobOutcome.DROPPED
                return@inTransaction settleUnknownHeld(
                    lease,
                    job,
                    executionId,
                    ActionGuideJobOutcome.RECOVERED_UNKNOWN,
                )
            }
            if (!jobs.markSuperseded(lease, clock.instant())) return@inTransaction ActionGuideJobOutcome.DROPPED
            credits.release(job)
            ActionGuideJobOutcome.COMPLETED
        }

    private fun settleUnknown(
        lease: ActionGuideJobLease,
        executionId: UUID,
        outcome: ActionGuideJobOutcome,
    ): ActionGuideJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction ActionGuideJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, outcome)
        }

    private fun settleUnknownHeld(
        lease: ActionGuideJobLease,
        job: StoredActionGuideJob,
        executionId: UUID,
        outcome: ActionGuideJobOutcome,
    ): ActionGuideJobOutcome =
        if (jobs.markFailed(lease, ActionGuideJobFailureCode.OUTCOME_UNKNOWN, clock.instant())) {
            ledger.markOutcomeUnknown(job, executionId, clock.instant())
            credits.release(job)
            outcome
        } else {
            ActionGuideJobOutcome.DROPPED
        }

    private sealed interface StartResult {
        data object Dropped : StartResult

        data object Superseded : StartResult

        data class Started(
            val job: StoredActionGuideJob,
            val executionId: UUID,
        ) : StartResult
    }
}

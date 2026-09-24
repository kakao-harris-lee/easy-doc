package kr.easydoc.application.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

enum class IllustrationSuggestionJobOutcome {
    IDLE,
    COMPLETED,
    FAILED,
    RECOVERED_UNKNOWN,
    DEAD_LETTERED,
    DROPPED,
}

/**
 * 그림 제안 분석 worker 수직 흐름 — R2 `ProcessActionGuideJob` 과 같은 경계를 새 기능에 다시 세운다.
 *
 * 분석 입력 확정과 provider 호출 시작은 같은 트랜잭션에서 먼저 영속화하고, 호출 자체만 트랜잭션
 * 밖에서 수행한다. 시작된 리스가 만료되면 자동 재호출하지 않고
 * [IllustrationSuggestionJobFailureCode.OUTCOME_UNKNOWN] 으로 정산한다.
 *
 * `LongParameterList` — worker 의 저장·원장·예약 경계를 생성자에서 명시적으로 조립한다.
 * `TooManyFunctions` — 늘어난 것은 책임이 아니라 정산 갈래다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ProcessIllustrationSuggestionJob(
    private val jobs: IllustrationSuggestionJobRepository,
    private val credits: IllustrationSuggestionCreditPort,
    private val ledger: IllustrationSuggestionLlmCallLedger,
    private val results: IllustrationSuggestionResultRepository,
    private val cipher: ContentCipher,
    private val runner: IllustrationSuggestionJobRunner,
    private val transaction: TransactionRunner,
    private val policy: IllustrationSuggestionJobWorkerPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ProcessIllustrationSuggestionJob::class.java)

    fun processNext(): IllustrationSuggestionJobOutcome =
        when (val acquired = transaction.inTransaction { acquire() }) {
            IllustrationSuggestionJobAcquire.Empty -> IllustrationSuggestionJobOutcome.IDLE
            is IllustrationSuggestionJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is IllustrationSuggestionJobAcquire.DeadLettered -> deadLetter(acquired.lease)
            is IllustrationSuggestionJobAcquire.Held -> run(acquired.lease)
        }

    /**
     * 기능을 끈 동안 미시작 예약을 반환하되, 이미 시작한 호출은 불명확 정산한다.
     * 리스 재획득 상한을 넘긴 작업도 여기서는 실패가 아니라 같은 미시작 반환으로 끝낸다.
     */
    fun drainNext(): IllustrationSuggestionJobOutcome =
        when (val acquired = transaction.inTransaction { acquire() }) {
            IllustrationSuggestionJobAcquire.Empty -> IllustrationSuggestionJobOutcome.IDLE
            is IllustrationSuggestionJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is IllustrationSuggestionJobAcquire.DeadLettered -> supersedeUnstarted(acquired.lease)
            is IllustrationSuggestionJobAcquire.Held -> supersedeUnstarted(acquired.lease)
        }

    private fun acquire(): IllustrationSuggestionJobAcquire =
        jobs.acquire(policy.owner, policy.leaseDuration, policy.maxLeaseAttempts)

    private fun run(lease: IllustrationSuggestionJobLease): IllustrationSuggestionJobOutcome =
        when (val start = start(lease)) {
            StartResult.Dropped -> IllustrationSuggestionJobOutcome.DROPPED
            StartResult.Superseded -> IllustrationSuggestionJobOutcome.COMPLETED
            StartResult.PreparationFailed -> IllustrationSuggestionJobOutcome.FAILED
            is StartResult.Started -> runStarted(lease, start)
        }

    private fun runStarted(
        lease: IllustrationSuggestionJobLease,
        started: StartResult.Started,
    ): IllustrationSuggestionJobOutcome {
        val result =
            try {
                started.call.call().also {
                    require(it.record.purpose == LlmCallPurpose.ILLUSTRATION_SUGGESTION) {
                        "그림 제안 runner가 다른 원장 목적을 반환했습니다"
                    }
                }
            } catch (_: RuntimeException) {
                return settleUnknown(lease, started.executionId, IllustrationSuggestionJobOutcome.FAILED)
            }
        return settle(lease, started.executionId, result)
    }

    // 갈래를 좁히면 이 방어가 무너진다 — 입력을 만들다 나온 **어떤** 실패도 작업을 running 으로
    // 남겨선 안 된다. 되돌릴 수 있는 저장소 실패만 [failPreparation] 의 정산 쓰기가 다시 걸러낸다.
    @Suppress("TooGenericExceptionCaught")
    private fun start(lease: IllustrationSuggestionJobLease): StartResult =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction StartResult.Dropped
            // 입력 확정까지 변환 행 잠금 안에서 끝낸다. 시작을 커밋한 뒤에 입력을 읽으면 그 사이의
            // 본문 수정이 호출 없는 OUTCOME_UNKNOWN이 되어 쓰지도 않은 시도를 상한에서 깎는다.
            if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision)) {
                return@inTransaction supersede(lease, job)
            }
            val call =
                try {
                    runner.prepare(job)
                } catch (failure: RuntimeException) {
                    return@inTransaction failPreparation(lease, job, failure)
                }
            if (call == null) return@inTransaction supersede(lease, job)
            val executionId = UUID.randomUUID()
            val startedAt = clock.instant()
            if (!jobs.markProviderStarted(lease, executionId, startedAt)) return@inTransaction StartResult.Dropped
            ledger.start(job, executionId, startedAt)
            StartResult.Started(executionId, call)
        }

    @Suppress("LongMethod")
    private fun settle(
        lease: IllustrationSuggestionJobLease,
        executionId: UUID,
        result: IllustrationSuggestionRunResult,
    ): IllustrationSuggestionJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            when (result) {
                is IllustrationSuggestionRunResult.Valid -> {
                    require(result.record.outcome == LlmCallOutcome.COMPLETED)
                    if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision)) {
                        if (!jobs.markSuperseded(lease, clock.instant())) {
                            return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                        }
                        ledger.complete(job, executionId, result.record)
                        credits.release(job)
                        IllustrationSuggestionJobOutcome.COMPLETED
                    } else {
                        if (!jobs.markSucceeded(lease, clock.instant())) {
                            return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                        }
                        storeResult(job, result)
                        ledger.complete(job, executionId, result.record)
                        // 제안 0건('제안 없음')도 정상 결과라 소비한다(명세 §3).
                        credits.consume(job)
                        IllustrationSuggestionJobOutcome.COMPLETED
                    }
                }

                is IllustrationSuggestionRunResult.Invalid -> {
                    require(result.record.outcome == LlmCallOutcome.COMPLETED)
                    if (!jobs.markFailed(
                            lease,
                            IllustrationSuggestionJobFailureCode.RESULT_INVALID,
                            clock.instant(),
                        )
                    ) {
                        return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                    }
                    ledger.complete(job, executionId, result.record)
                    credits.release(job)
                    IllustrationSuggestionJobOutcome.FAILED
                }

                is IllustrationSuggestionRunResult.ProviderFailed -> {
                    require(result.record.outcome == LlmCallOutcome.PROVIDER_ERROR)
                    if (!jobs.markFailed(
                            lease,
                            IllustrationSuggestionJobFailureCode.GENERATION_FAILED,
                            clock.instant(),
                        )
                    ) {
                        return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                    }
                    ledger.complete(job, executionId, result.record)
                    credits.release(job)
                    IllustrationSuggestionJobOutcome.FAILED
                }
            }
        }

    private fun storeResult(
        job: StoredIllustrationSuggestionJob,
        result: IllustrationSuggestionRunResult.Valid,
    ) {
        val resultId = UUID.randomUUID()
        val payload =
            cipher.encrypt(
                PlainBody(IllustrationSuggestionStorageCodec.encode(result.suggestions)),
                resultId,
                EncryptedField.ILLUSTRATION_SUGGESTION_RESULT,
            )
        if (!results.insertResult(
                job,
                StoredIllustrationSuggestionResult(
                    resultId,
                    job.jobId,
                    job.conversionId,
                    job.basedOnContentRevision,
                    payload,
                    clock.instant(),
                ),
            )
        ) {
            throw StorageException(RESULT_STORAGE_FAILED_MESSAGE)
        }
    }

    /** 입력이 더 이상 유효하지 않다 — 호출 없이 예약을 반환한다. 시도 상한에 세지 않는다. */
    private fun supersede(
        lease: IllustrationSuggestionJobLease,
        job: StoredIllustrationSuggestionJob,
    ): StartResult {
        if (!jobs.markSuperseded(lease, clock.instant())) return StartResult.Dropped
        credits.release(job)
        return StartResult.Superseded
    }

    /**
     * 입력을 만들지 못한 실패를 provider 시작 없이 끝낸다 — 복호화 실패·저장 본문 없음처럼 다시
     * 시도해도 같은 결과다. 예외를 그대로 올리면 작업이 `running` 으로 남아 리스가 만료될 때마다
     * 같은 자리에서 다시 깨지고, 그동안 예약도 worker slot 도 풀리지 않는다.
     *
     * 되돌릴 수 있는 저장소 실패는 여기서 종료 상태로 굳지 않는다 — PostgreSQL 은 문장 하나가
     * 실패하면 트랜잭션 전체를 중단하므로, 그 경우 아래 정산 쓰기도 함께 실패해 예외가 그대로
     * 올라가고 트랜잭션이 되돌려져 다음 리스에서 다시 시도된다.
     */
    private fun failPreparation(
        lease: IllustrationSuggestionJobLease,
        job: StoredIllustrationSuggestionJob,
        failure: RuntimeException,
    ): StartResult {
        if (!jobs.markFailed(lease, IllustrationSuggestionJobFailureCode.GENERATION_FAILED, clock.instant())) {
            return StartResult.Dropped
        }
        credits.release(job)
        // 예외 메시지는 남기지 않는다(본문이 섞일 수 있다). 갈래는 타입 이름으로 충분하다.
        log.info(
            "그림 제안 분석 입력을 만들지 못해 호출 없이 실패로 끝낸다: jobId={}, failure={}",
            job.jobId,
            failure::class.java.simpleName,
        )
        return StartResult.PreparationFailed
    }

    /**
     * provider 를 시작하지 못한 채 리스만 계속 다시 얻은 작업을 그 자리에서 끝낸다.
     *
     * 「미시작」은 획득 트랜잭션이 본 사실이다. 정산 트랜잭션에서 다시 확인해 시작 표시가 보이면
     * 불명확 회수에 넘긴다 — 호출 결과를 모르는 작업을 `generation_failed` 로 굳히면 원장 없이
     * 사라진 호출이 생긴다.
     */
    private fun deadLetter(lease: IllustrationSuggestionJobLease): IllustrationSuggestionJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            if (job.providerStartedAt != null || job.executionId != null) {
                val executionId =
                    job.executionId ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                return@inTransaction settleUnknownHeld(
                    lease,
                    job,
                    executionId,
                    IllustrationSuggestionJobOutcome.RECOVERED_UNKNOWN,
                )
            }
            if (!jobs.markFailed(lease, IllustrationSuggestionJobFailureCode.GENERATION_FAILED, clock.instant())) {
                return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            }
            credits.release(job)
            log.warn(
                "그림 제안 작업이 리스 재획득 상한을 넘겨 호출 없이 실패로 끝낸다: jobId={}, attempts={}",
                job.jobId,
                lease.fence,
            )
            IllustrationSuggestionJobOutcome.DEAD_LETTERED
        }

    private fun recoverUnknown(lease: IllustrationSuggestionJobLease): IllustrationSuggestionJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            val executionId = job.executionId ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, IllustrationSuggestionJobOutcome.RECOVERED_UNKNOWN)
        }

    private fun supersedeUnstarted(lease: IllustrationSuggestionJobLease): IllustrationSuggestionJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            if (job.providerStartedAt != null || job.executionId != null) {
                val executionId =
                    job.executionId ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
                return@inTransaction settleUnknownHeld(
                    lease,
                    job,
                    executionId,
                    IllustrationSuggestionJobOutcome.RECOVERED_UNKNOWN,
                )
            }
            if (!jobs.markSuperseded(lease, clock.instant())) {
                return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            }
            credits.release(job)
            IllustrationSuggestionJobOutcome.COMPLETED
        }

    private fun settleUnknown(
        lease: IllustrationSuggestionJobLease,
        executionId: UUID,
        outcome: IllustrationSuggestionJobOutcome,
    ): IllustrationSuggestionJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationSuggestionJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, outcome)
        }

    private fun settleUnknownHeld(
        lease: IllustrationSuggestionJobLease,
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        outcome: IllustrationSuggestionJobOutcome,
    ): IllustrationSuggestionJobOutcome =
        if (jobs.markFailed(lease, IllustrationSuggestionJobFailureCode.OUTCOME_UNKNOWN, clock.instant())) {
            ledger.markOutcomeUnknown(job, executionId, clock.instant())
            credits.release(job)
            outcome
        } else {
            IllustrationSuggestionJobOutcome.DROPPED
        }

    private sealed interface StartResult {
        data object Dropped : StartResult

        data object Superseded : StartResult

        /** 입력을 만들지 못해 provider 시작 없이 실패로 끝냈다. */
        data object PreparationFailed : StartResult

        /**
         * 확정된 호출을 트랜잭션 밖으로 넘기는 자리다. 값 비교도 복사도 쓰지 않아 `data` 가
         * 필요 없고, 호출을 든 뒤로는 `data class` 를 쓸 수도 없다 — `GeneratedToStringProbes`
         * 가 주 생성자 없는 [IllustrationSuggestionProviderCall] 자리를 채우지 못한다.
         */
        class Started(
            val executionId: UUID,
            val call: IllustrationSuggestionProviderCall,
        ) : StartResult
    }
}

const val RESULT_STORAGE_FAILED_MESSAGE: String = "그림 제안 결과를 저장할 수 없습니다"

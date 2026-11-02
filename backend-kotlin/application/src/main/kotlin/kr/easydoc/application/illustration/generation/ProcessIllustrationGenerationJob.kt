package kr.easydoc.application.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.illustration.generation.ILLUSTRATION_GENERATION_SIZE
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

enum class IllustrationGenerationJobOutcome {
    IDLE,
    COMPLETED,
    FAILED,
    RECOVERED_UNKNOWN,
    DEAD_LETTERED,
    DROPPED,
}

@Suppress("LongParameterList", "TooManyFunctions")
class ProcessIllustrationGenerationJob(
    private val jobs: IllustrationGenerationJobRepository,
    private val credits: IllustrationGenerationCreditPort,
    private val ledger: IllustrationGenerationLlmCallLedger,
    private val assets: GeneratedIllustrationAssetRepository,
    private val cipher: ContentCipher,
    private val runner: IllustrationGenerationJobRunner,
    private val transaction: TransactionRunner,
    private val policy: IllustrationGenerationJobWorkerPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ProcessIllustrationGenerationJob::class.java)

    fun processNext(): IllustrationGenerationJobOutcome =
        when (val acquired = transaction.inTransaction { acquire() }) {
            IllustrationGenerationJobAcquire.Empty -> IllustrationGenerationJobOutcome.IDLE
            is IllustrationGenerationJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is IllustrationGenerationJobAcquire.DeadLettered -> deadLetter(acquired.lease)
            is IllustrationGenerationJobAcquire.Held -> run(acquired.lease)
        }

    fun drainNext(): IllustrationGenerationJobOutcome =
        when (val acquired = transaction.inTransaction { acquire() }) {
            IllustrationGenerationJobAcquire.Empty -> IllustrationGenerationJobOutcome.IDLE
            is IllustrationGenerationJobAcquire.RecoverUnknown -> recoverUnknown(acquired.lease)
            is IllustrationGenerationJobAcquire.DeadLettered -> supersedeUnstarted(acquired.lease)
            is IllustrationGenerationJobAcquire.Held -> supersedeUnstarted(acquired.lease)
        }

    private fun acquire(): IllustrationGenerationJobAcquire =
        jobs.acquire(policy.owner, policy.leaseDuration, policy.maxLeaseAttempts)

    private fun run(lease: IllustrationGenerationJobLease): IllustrationGenerationJobOutcome =
        when (val start = start(lease)) {
            StartResult.Dropped -> IllustrationGenerationJobOutcome.DROPPED
            StartResult.Superseded -> IllustrationGenerationJobOutcome.COMPLETED
            StartResult.PreparationFailed -> IllustrationGenerationJobOutcome.FAILED
            is StartResult.Started -> runStarted(lease, start)
        }

    private fun runStarted(
        lease: IllustrationGenerationJobLease,
        started: StartResult.Started,
    ): IllustrationGenerationJobOutcome {
        val result =
            try {
                started.call.call().also {
                    require(it.record.purpose == LlmCallPurpose.ILLUSTRATION_GENERATION) {
                        "그림 생성 runner가 다른 원장 목적을 반환했습니다"
                    }
                }
            } catch (_: RuntimeException) {
                return settleUnknown(lease, started.executionId, IllustrationGenerationJobOutcome.FAILED)
            }
        return settle(lease, started.executionId, result)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun start(lease: IllustrationGenerationJobLease): StartResult =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction StartResult.Dropped
            if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision, job.suggestionResultId)) {
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

    private fun settle(
        lease: IllustrationGenerationJobLease,
        executionId: UUID,
        result: IllustrationGenerationRunResult,
    ): IllustrationGenerationJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            when (result) {
                is IllustrationGenerationRunResult.Succeeded -> {
                    require(result.record.outcome == LlmCallOutcome.COMPLETED)
                    if (!jobs.hasCurrentInput(lease, job.basedOnContentRevision, job.suggestionResultId)) {
                        if (!jobs.markSuperseded(lease, clock.instant())) {
                            return@inTransaction IllustrationGenerationJobOutcome.DROPPED
                        }
                        ledger.complete(job, executionId, result.record)
                        credits.release(job)
                    } else {
                        storeAsset(job, result)
                        if (!jobs.markSucceeded(lease, clock.instant())) {
                            return@inTransaction IllustrationGenerationJobOutcome.DROPPED
                        }
                        ledger.complete(job, executionId, result.record)
                        credits.consume(job)
                    }
                    IllustrationGenerationJobOutcome.COMPLETED
                }

                is IllustrationGenerationRunResult.Failed -> {
                    require(result.record.outcome == LlmCallOutcome.PROVIDER_ERROR)
                    if (!jobs.markFailed(lease, result.failureCode, clock.instant())) {
                        return@inTransaction IllustrationGenerationJobOutcome.DROPPED
                    }
                    ledger.complete(job, executionId, result.record)
                    credits.release(job)
                    IllustrationGenerationJobOutcome.FAILED
                }
            }
        }

    private fun storeAsset(
        job: StoredIllustrationGenerationJob,
        result: IllustrationGenerationRunResult.Succeeded,
    ) {
        val assetId = UUID.randomUUID()
        val asset =
            StoredGeneratedIllustrationAsset(
                assetId = assetId,
                jobId = job.jobId,
                conversionId = job.conversionId,
                basedOnContentRevision = job.basedOnContentRevision,
                metadata =
                    cipher.encrypt(
                        PlainBody(IllustrationGenerationStorageCodec.encode(result.input)),
                        assetId,
                        EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
                    ),
                png =
                    cipher.encryptBytes(
                        PlainBytes(result.generated.pngBytes),
                        assetId,
                        EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG,
                    ),
                pngByteSize = result.generated.pngBytes.size,
                width = ILLUSTRATION_GENERATION_SIZE,
                height = ILLUSTRATION_GENERATION_SIZE,
                provenance = if (result.record.provider == "fake") "fake" else "provider",
            )
        if (!assets.insertAsset(job, asset)) throw StorageException(GENERATION_ASSET_STORAGE_FAILED_MESSAGE)
    }

    private fun supersede(
        lease: IllustrationGenerationJobLease,
        job: StoredIllustrationGenerationJob,
    ): StartResult {
        if (!jobs.markSuperseded(lease, clock.instant())) return StartResult.Dropped
        credits.release(job)
        return StartResult.Superseded
    }

    private fun failPreparation(
        lease: IllustrationGenerationJobLease,
        job: StoredIllustrationGenerationJob,
        failure: RuntimeException,
    ): StartResult {
        if (!jobs.markFailed(lease, IllustrationGenerationJobFailureCode.GENERATION_FAILED, clock.instant())) {
            return StartResult.Dropped
        }
        credits.release(job)
        log.info(
            "그림 생성 입력을 만들지 못해 호출 없이 실패로 끝낸다: jobId={}, failure={}",
            job.jobId,
            failure::class.java.simpleName,
        )
        return StartResult.PreparationFailed
    }

    private fun deadLetter(lease: IllustrationGenerationJobLease): IllustrationGenerationJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            if (job.providerStartedAt != null || job.executionId != null) {
                val executionId = job.executionId ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
                return@inTransaction settleUnknownHeld(
                    lease,
                    job,
                    executionId,
                    IllustrationGenerationJobOutcome.RECOVERED_UNKNOWN,
                )
            }
            if (!jobs.markFailed(lease, IllustrationGenerationJobFailureCode.GENERATION_FAILED, clock.instant())) {
                return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            }
            credits.release(job)
            IllustrationGenerationJobOutcome.DEAD_LETTERED
        }

    private fun recoverUnknown(lease: IllustrationGenerationJobLease): IllustrationGenerationJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            val executionId = job.executionId ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, IllustrationGenerationJobOutcome.RECOVERED_UNKNOWN)
        }

    private fun supersedeUnstarted(lease: IllustrationGenerationJobLease): IllustrationGenerationJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            if (job.providerStartedAt != null || job.executionId != null) {
                val executionId = job.executionId ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
                return@inTransaction settleUnknownHeld(
                    lease,
                    job,
                    executionId,
                    IllustrationGenerationJobOutcome.RECOVERED_UNKNOWN,
                )
            }
            if (!jobs.markSuperseded(lease, clock.instant())) {
                return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            }
            credits.release(job)
            IllustrationGenerationJobOutcome.COMPLETED
        }

    private fun settleUnknown(
        lease: IllustrationGenerationJobLease,
        executionId: UUID,
        outcome: IllustrationGenerationJobOutcome,
    ): IllustrationGenerationJobOutcome =
        transaction.inTransaction {
            val job = jobs.lockIfHeld(lease) ?: return@inTransaction IllustrationGenerationJobOutcome.DROPPED
            settleUnknownHeld(lease, job, executionId, outcome)
        }

    private fun settleUnknownHeld(
        lease: IllustrationGenerationJobLease,
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
        outcome: IllustrationGenerationJobOutcome,
    ): IllustrationGenerationJobOutcome =
        if (jobs.markFailed(lease, IllustrationGenerationJobFailureCode.OUTCOME_UNKNOWN, clock.instant())) {
            ledger.markOutcomeUnknown(job, executionId)
            credits.release(job)
            outcome
        } else {
            IllustrationGenerationJobOutcome.DROPPED
        }

    private sealed interface StartResult {
        data object Dropped : StartResult

        data object Superseded : StartResult

        data object PreparationFailed : StartResult

        class Started(
            val executionId: UUID,
            val call: IllustrationGenerationProviderCall,
        ) : StartResult
    }
}

const val GENERATION_ASSET_STORAGE_FAILED_MESSAGE: String = "생성 그림을 저장할 수 없습니다"

package kr.easydoc.application.illustration.generation

import kr.easydoc.application.illustration.suggestion.PassThroughCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class ProcessIllustrationGenerationJobTest {
    private val jobs = FakeGenerationJobs()
    private val credits = RecordingGenerationCredits()
    private val ledger = RecordingGenerationLedger()
    private val assets = RecordingGenerationAssets()
    private val cipher = PassThroughCipher()

    @Test
    fun `유효한 완료는 자산 한 건 저장 성공 원장과 소비를 커밋하고 provider는 한 번 호출한다`() {
        var calls = 0
        val process =
            process(
                IllustrationGenerationJobRunner { job ->
                    IllustrationGenerationProviderCall {
                        calls++
                        IllustrationGenerationRunResult.Succeeded(generated(), input(job))
                    }
                },
            )
        val job = storedGenerationJob()
        jobs.rows[job.jobId] = job
        jobs.acquired = IllustrationGenerationJobAcquire.Held(lease())

        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.COMPLETED)

        assertThat(calls).isEqualTo(1)
        assertThat(assets.inserted).hasSize(1)
        assertThat(ledger.starts).isEqualTo(1)
        assertThat(ledger.completes).isEqualTo(1)
        assertThat(credits.consumes).isEqualTo(1)
        assertThat(credits.releases).isEqualTo(0)
        assertThat(jobs.rows[job.jobId]!!.status).isEqualTo(IllustrationGenerationJobStatus.SUCCEEDED)
    }

    @Test
    fun `provider 완료 뒤 stale이면 자산 없이 superseded와 예약 반환을 기록한다`() {
        var checks = 0
        jobs.currentInputCheck = { ++checks == 1 }
        val process =
            process(
                IllustrationGenerationJobRunner { job ->
                    IllustrationGenerationProviderCall {
                        IllustrationGenerationRunResult.Succeeded(generated(), input(job))
                    }
                },
            )
        val job = storedGenerationJob()
        jobs.rows[job.jobId] = job
        jobs.acquired = IllustrationGenerationJobAcquire.Held(lease())

        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.COMPLETED)

        assertThat(assets.inserted).isEmpty()
        assertThat(credits.releases).isEqualTo(1)
        assertThat(credits.consumes).isEqualTo(0)
        assertThat(ledger.completes).isEqualTo(1)
        assertThat(jobs.rows[job.jobId]!!.status).isEqualTo(IllustrationGenerationJobStatus.SUPERSEDED)
    }

    @Test
    fun `provider 실패는 자산 없이 예약을 반환하고 다시 호출하지 않는다`() {
        var calls = 0
        val process =
            process(
                IllustrationGenerationJobRunner {
                    IllustrationGenerationProviderCall {
                        calls++
                        IllustrationGenerationRunResult.Failed(
                            record(LlmCallOutcome.PROVIDER_ERROR),
                            IllustrationGenerationJobFailureCode.GENERATION_FAILED,
                        )
                    }
                },
            )
        val job = storedGenerationJob()
        jobs.rows[job.jobId] = job
        jobs.acquired = IllustrationGenerationJobAcquire.Held(lease())

        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.FAILED)
        assertThat(calls).isEqualTo(1)
        assertThat(assets.inserted).isEmpty()
        assertThat(credits.releases).isEqualTo(1)
        assertThat(jobs.rows[job.jobId]!!.failureCode).isEqualTo(IllustrationGenerationJobFailureCode.GENERATION_FAILED)
    }

    @Test
    fun `provider 시작 뒤 만료 작업은 outcome_unknown으로 회수하고 재호출하지 않는다`() {
        var calls = 0
        val process =
            process(
                IllustrationGenerationJobRunner {
                    IllustrationGenerationProviderCall {
                        calls++
                        error("호출하면 안 됩니다")
                    }
                },
            )
        val job =
            storedGenerationJob(
                status = IllustrationGenerationJobStatus.RUNNING,
                executionId = GENERATION_EXECUTION,
                providerStartedAt = GENERATION_NOW,
            )
        jobs.rows[job.jobId] = job
        jobs.acquired = IllustrationGenerationJobAcquire.RecoverUnknown(lease())

        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.RECOVERED_UNKNOWN)
        assertThat(calls).isEqualTo(0)
        assertThat(ledger.unknowns).isEqualTo(1)
        assertThat(credits.releases).isEqualTo(1)
        assertThat(jobs.rows[job.jobId]!!.failureCode).isEqualTo(IllustrationGenerationJobFailureCode.OUTCOME_UNKNOWN)
    }

    private fun process(runner: IllustrationGenerationJobRunner) =
        ProcessIllustrationGenerationJob(
            jobs,
            credits,
            ledger,
            assets,
            cipher,
            runner,
            DirectGenerationTransaction(),
            IllustrationGenerationJobWorkerPolicy("test-worker", Duration.ofSeconds(120), 5),
            Clock.fixed(GENERATION_NOW, ZoneOffset.UTC),
        )

    private fun input(job: StoredIllustrationGenerationJob) =
        IllustrationGenerationStorageCodec.decode(
            cipher.decrypt(job.inputPayload, job.jobId, EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT).value,
        )

    private fun lease() = IllustrationGenerationJobLease(GENERATION_JOB, "test-worker", 1)

    private fun generated() = GeneratedIllustration(byteArrayOf(1, 2, 3), record(LlmCallOutcome.COMPLETED))

    private fun record(outcome: LlmCallOutcome) =
        LlmCallRecord(
            LlmCallPurpose.ILLUSTRATION_GENERATION,
            "fake",
            if (outcome == LlmCallOutcome.COMPLETED) "fake-image" else null,
            0,
            0,
            1,
            BigDecimal.ZERO,
            null,
            null,
            100,
            GENERATION_NOW,
            outcome,
            if (outcome == LlmCallOutcome.PROVIDER_ERROR) "ProviderFailed" else null,
        )
}

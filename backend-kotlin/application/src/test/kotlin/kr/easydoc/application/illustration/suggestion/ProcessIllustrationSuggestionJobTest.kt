package kr.easydoc.application.illustration.suggestion

import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** worker 수직 흐름의 정산 갈래(명세 §3). 소비와 반환이 갈리는 자리를 전부 고정한다. */
class ProcessIllustrationSuggestionJobTest {
    private val jobs = FakeSuggestionJobs()
    private val credits = RecordingSuggestionCredits()
    private val ledger = RecordingSuggestionLedger()
    private val results = RecordingSuggestionResults()
    private val cipher = PassThroughCipher()

    private val lease = IllustrationSuggestionJobLease(SUGGESTION_JOB, "worker-1", fence = 1)

    private fun process(runner: IllustrationSuggestionJobRunner) =
        ProcessIllustrationSuggestionJob(
            jobs,
            credits,
            ledger,
            results,
            cipher,
            runner,
            DirectSuggestionTransaction(),
            IllustrationSuggestionJobWorkerPolicy("worker-1", Duration.ofSeconds(120), maxLeaseAttempts = 5),
            Clock.fixed(SUGGESTION_NOW, ZoneOffset.UTC),
        )

    private fun queuedJob() {
        jobs.rows[SUGGESTION_JOB] = storedSuggestionJob()
        jobs.acquired = IllustrationSuggestionJobAcquire.Held(lease)
    }

    private fun runnerReturning(result: IllustrationSuggestionRunResult) =
        IllustrationSuggestionJobRunner { IllustrationSuggestionProviderCall { result } }

    private fun completedRecord() =
        LlmCallRecord(
            purpose = LlmCallPurpose.ILLUSTRATION_SUGGESTION,
            provider = "fake",
            model = "fake-model",
            inputTokens = 1,
            outputTokens = 1,
            latencyMs = 1,
            estimatedCostUsd = null,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = 10,
            calledAt = SUGGESTION_NOW,
            outcome = LlmCallOutcome.COMPLETED,
        )

    private fun providerErrorRecord() =
        completedRecord().copy(
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            model = null,
            inputTokens = 0,
            outputTokens = 0,
            failureClass = "LlmProviderException",
        )

    @Test
    @DisplayName("제안이 있는 정상 결과는 저장하고 이용량을 소비한다")
    fun `유효한 결과는 소비한다`() {
        queuedJob()
        val set = suggestionSet(count = 2)

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), set))).processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.COMPLETED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).status).isEqualTo(IllustrationSuggestionJobStatus.SUCCEEDED)
        assertThat(credits.consumes).isEqualTo(1)
        assertThat(credits.releases).isZero()
        assertThat(ledger.starts).isEqualTo(1)
        assertThat(ledger.completes).isEqualTo(1)
        val stored =
            IllustrationSuggestionStorageCodec.decode(
                String(
                    results.inserted
                        .single()
                        .payload.bytes,
                ),
            )
        assertThat(stored.suggestions).hasSize(2)
    }

    @Test
    @DisplayName("'제안 없음'(0건)도 정상 결과라 저장하고 소비한다 — 분석 실패와 구분된다")
    fun `제안 0건도 소비한다`() {
        queuedJob()
        val empty = suggestionSet(count = 0)

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), empty))).processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.COMPLETED)
        assertThat(credits.consumes).isEqualTo(1)
        assertThat(credits.releases).isZero()
        val stored =
            IllustrationSuggestionStorageCodec.decode(
                String(
                    results.inserted
                        .single()
                        .payload.bytes,
                ),
            )
        assertThat(stored.suggestions).isEmpty()
    }

    @Test
    @DisplayName("구조 위반·전량 탈락은 result_invalid 로 끝나고 이용량을 반환한다")
    fun `쓸 수 없는 결과는 반환한다`() {
        queuedJob()

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Invalid(completedRecord()))).processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.FAILED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).failureCode)
            .isEqualTo(IllustrationSuggestionJobFailureCode.RESULT_INVALID)
        assertThat(credits.releases).isEqualTo(1)
        assertThat(credits.consumes).isZero()
        assertThat(results.inserted).isEmpty()
    }

    @Test
    @DisplayName("provider 오류는 generation_failed 로 끝나고 이용량을 반환한다")
    fun `provider 오류는 반환한다`() {
        queuedJob()

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.ProviderFailed(providerErrorRecord())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.FAILED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).failureCode)
            .isEqualTo(IllustrationSuggestionJobFailureCode.GENERATION_FAILED)
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("호출 뒤 본문이 바뀌었으면 superseded 로 정산하고 결과를 저장하지 않는다")
    fun `늦게 도착한 결과는 superseded 다`() {
        queuedJob()
        var checks = 0
        // 시작 시점에는 유효하고(첫 호출), 정산 시점에는 바뀌어 있다(둘째 호출).
        jobs.currentInputCheck = { checks++ == 0 }

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.COMPLETED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).status).isEqualTo(IllustrationSuggestionJobStatus.SUPERSEDED)
        assertThat(credits.releases).isEqualTo(1)
        assertThat(credits.consumes).isZero()
        assertThat(results.inserted).isEmpty()
    }

    @Test
    @DisplayName("시작 전에 입력이 유효하지 않으면 호출 없이 superseded 다 — 원장에 아무것도 남지 않는다")
    fun `시작 전 무효 입력은 호출하지 않는다`() {
        queuedJob()
        jobs.currentInput = false

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.COMPLETED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).status).isEqualTo(IllustrationSuggestionJobStatus.SUPERSEDED)
        assertThat(ledger.starts).isZero()
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("입력 준비가 깨지면 provider 시작 없이 generation_failed 로 끝낸다")
    fun `입력 준비 실패는 호출 없이 실패다`() {
        queuedJob()
        val failing = IllustrationSuggestionJobRunner { error("복호화 실패") }

        val outcome = process(failing).processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.FAILED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).failureCode)
            .isEqualTo(IllustrationSuggestionJobFailureCode.GENERATION_FAILED)
        assertThat(ledger.starts).isZero()
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("provider 시작 뒤 리스가 만료되면 다시 호출하지 않고 outcome_unknown 으로 회수한다")
    fun `시작된 호출은 재호출하지 않는다`() {
        jobs.rows[SUGGESTION_JOB] =
            storedSuggestionJob(
                status = IllustrationSuggestionJobStatus.RUNNING,
                executionId = SUGGESTION_EXECUTION,
                providerStartedAt = SUGGESTION_NOW,
            )
        jobs.acquired = IllustrationSuggestionJobAcquire.RecoverUnknown(lease)

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.RECOVERED_UNKNOWN)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).failureCode)
            .isEqualTo(IllustrationSuggestionJobFailureCode.OUTCOME_UNKNOWN)
        assertThat(ledger.unknowns).isEqualTo(1)
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("미시작 작업이 리스 재획득 상한을 넘기면 dead-letter 로 끝내고 예약을 돌려준다")
    fun `상한을 넘긴 미시작 작업은 dead-letter 다`() {
        queuedJob()
        jobs.acquired = IllustrationSuggestionJobAcquire.DeadLettered(lease)

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.DEAD_LETTERED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).failureCode)
            .isEqualTo(IllustrationSuggestionJobFailureCode.GENERATION_FAILED)
        assertThat(ledger.starts).isZero()
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("dead-letter 로 잡았어도 시작 표시가 보이면 불명확 회수로 넘긴다 — 원장 없는 호출을 만들지 않는다")
    fun `시작된 dead-letter 는 불명확 회수다`() {
        jobs.rows[SUGGESTION_JOB] =
            storedSuggestionJob(
                status = IllustrationSuggestionJobStatus.RUNNING,
                executionId = SUGGESTION_EXECUTION,
                providerStartedAt = SUGGESTION_NOW,
            )
        jobs.acquired = IllustrationSuggestionJobAcquire.DeadLettered(lease)

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.RECOVERED_UNKNOWN)
        assertThat(ledger.unknowns).isEqualTo(1)
    }

    @Test
    @DisplayName("intake OFF drain 은 미시작 작업을 호출 없이 superseded 로 비운다")
    fun `drain 은 미시작 작업을 비운다`() {
        queuedJob()
        val forbidden = IllustrationSuggestionJobRunner { error("drain 경로에서 runner를 부르면 안 된다") }

        val outcome = process(forbidden).drainNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.COMPLETED)
        assertThat(jobs.rows.getValue(SUGGESTION_JOB).status).isEqualTo(IllustrationSuggestionJobStatus.SUPERSEDED)
        assertThat(credits.releases).isEqualTo(1)
    }

    @Test
    @DisplayName("결과 저장이 실패하면 예외로 끊어 트랜잭션을 되돌린다 — 소비만 남기지 않는다")
    fun `결과를 저장하지 못하면 끊는다`() {
        queuedJob()
        results.insertSucceeds = false

        assertThatThrownBy {
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()
        }.isInstanceOf(StorageException::class.java)
            .hasMessage(RESULT_STORAGE_FAILED_MESSAGE)
    }

    @Test
    @DisplayName("리스를 이미 잃었으면 아무 것도 정산하지 않는다")
    fun `리스를 잃으면 아무 것도 하지 않는다`() {
        queuedJob()
        jobs.held = false

        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.DROPPED)
        assertThat(credits.releases).isZero()
        assertThat(credits.consumes).isZero()
    }

    @Test
    @DisplayName("획득할 작업이 없으면 IDLE 이다")
    fun `획득할 작업이 없으면 IDLE 이다`() {
        val outcome =
            process(runnerReturning(IllustrationSuggestionRunResult.Valid(completedRecord(), suggestionSet())))
                .processNext()

        assertThat(outcome).isEqualTo(IllustrationSuggestionJobOutcome.IDLE)
    }
}

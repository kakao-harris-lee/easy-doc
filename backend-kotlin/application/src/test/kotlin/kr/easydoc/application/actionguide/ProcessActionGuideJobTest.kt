package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class ProcessActionGuideJobTest {
    @Test
    fun `provider 시작과 in progress 원장을 먼저 커밋한 뒤 트랜잭션 밖에서 runner를 부른다`() {
        val world = World()
        var statusAtRun: ActionGuideJobStatus? = null
        var depthAtRun: Int? = null
        world.runner =
            ActionGuideJobRunner {
                statusAtRun = world.jobs.rows[JOB]?.status
                depthAtRun = world.transaction.depth
                completedRecord()
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(statusAtRun).isEqualTo(ActionGuideJobStatus.RUNNING)
        assertThat(depthAtRun).isZero()
        assertThat(world.ledger.starts).isEqualTo(1)
        assertThat(world.ledger.completes).isEqualTo(1)
        assertThat(world.credits.consumes).isEqualTo(1)
    }

    @Test
    fun `provider 시작 뒤 만료된 리스는 runner를 재호출하지 않고 unknown으로 반환한다`() {
        val world = World(started = true)
        world.jobs.acquired = ActionGuideJobAcquire.RecoverUnknown(world.lease)
        var calls = 0
        world.runner =
            ActionGuideJobRunner {
                calls += 1
                completedRecord()
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.RECOVERED_UNKNOWN)
        assertThat(calls).isZero()
        assertThat(world.ledger.unknowns).isEqualTo(1)
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.jobs.rows[JOB]?.failureCode).isEqualTo(ActionGuideJobFailureCode.OUTCOME_UNKNOWN)
    }

    @Test
    fun `terminal fencing 갱신 실패는 credit과 ledger side effect를 만들지 않는다`() {
        val world = World()
        world.jobs.terminalWriteSucceeds = false

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.DROPPED)
        assertThat(world.credits.consumes).isZero()
        assertThat(world.credits.releases).isZero()
        assertThat(world.ledger.completes).isZero()
        assertThat(world.ledger.unknowns).isZero()
    }

    @Test
    fun `현재 본문이 바뀐 완료는 superseded로 끝나고 예약을 반환한다`() {
        val world = World()
        var checks = 0
        world.jobs.currentInputCheck = {
            checks += 1
            checks == 1
        }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.credits.consumes).isZero()
    }

    @Test
    fun `획득 시점에 입력이 stale이면 provider 시작 없이 superseded로 예약을 반환한다`() {
        val world = World()
        world.jobs.currentInput = false
        var runnerCalls = 0
        world.runner =
            ActionGuideJobRunner {
                runnerCalls += 1
                completedRecord()
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(runnerCalls).isZero()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.completes).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
    }

    @Test
    fun `OFF drain은 queued 작업을 호출하지 않고 superseded로 예약 반환한다`() {
        val world = World()
        var runnerCalls = 0
        world.runner =
            ActionGuideJobRunner {
                runnerCalls += 1
                completedRecord()
            }

        assertThat(world.processor().drainNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(runnerCalls).isZero()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.completes).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
    }

    private class World(started: Boolean = false) {
        val lease = ActionGuideJobLease(JOB, "worker-1", 1)
        val jobs = FakeActionGuideJobs()
        val credits = RecordingActionGuideCredits()
        val ledger = RecordingActionGuideLedger()
        val transaction = DirectTransaction()
        var runner: ActionGuideJobRunner = ActionGuideJobRunner { completedRecord() }

        init {
            jobs.rows[JOB] =
                if (started) {
                    storedJob(ActionGuideJobStatus.RUNNING, executionId = EXECUTION, providerStartedAt = NOW)
                } else {
                    storedJob()
                }
            jobs.acquired = ActionGuideJobAcquire.Held(lease)
        }

        fun processor() =
            ProcessActionGuideJob(
                jobs,
                credits,
                ledger,
                runner,
                transaction,
                ActionGuideJobWorkerPolicy("worker-1", Duration.ofSeconds(30)),
                Clock.fixed(NOW, ZoneOffset.UTC),
            )
    }
}

private fun completedRecord(outcome: LlmCallOutcome = LlmCallOutcome.COMPLETED) =
    LlmCallRecord(
        purpose = LlmCallPurpose.ACTION_GUIDE,
        provider = "fake",
        model = if (outcome == LlmCallOutcome.COMPLETED) "fake-model" else null,
        inputTokens = 1,
        outputTokens = 1,
        latencyMs = 1,
        estimatedCostUsd = null,
        pricingInputUsdPerMtok = null,
        pricingOutputUsdPerMtok = null,
        charCount = 10,
        calledAt = NOW,
        outcome = outcome,
        failureClass = if (outcome == LlmCallOutcome.PROVIDER_ERROR) "LlmProviderException" else null,
    )

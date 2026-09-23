package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.FakeContentCipher
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

class ProcessActionGuideJobTest {
    @Test
    fun `생성 입력은 시작 트랜잭션 안에서 확정하고 provider 호출만 트랜잭션 밖에서 한다`() {
        val world = World()
        var statusAtRun: ActionGuideJobStatus? = null
        var depthAtPrepare: Int? = null
        var depthAtRun: Int? = null
        world.runner =
            ActionGuideJobRunner {
                depthAtPrepare = world.transaction.depth
                ActionGuideProviderCall {
                    statusAtRun = world.jobs.rows[JOB]?.status
                    depthAtRun = world.transaction.depth
                    validResult()
                }
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(statusAtRun).isEqualTo(ActionGuideJobStatus.RUNNING)
        // 입력 확정은 변환 행 잠금 안이어야 하고(> 0), provider 호출은 그 밖이어야 한다(0).
        assertThat(depthAtPrepare).isGreaterThan(0)
        assertThat(depthAtRun).isZero()
        assertThat(world.ledger.starts).isEqualTo(1)
        assertThat(world.ledger.completes).isEqualTo(1)
        assertThat(world.credits.consumes).isEqualTo(1)
        assertThat(world.contents.candidates).hasSize(1)
        val candidate = world.contents.candidates.single()
        val sealed = world.cipher.sealed.single()
        assertThat(candidate.jobId).isEqualTo(JOB)
        assertThat(candidate.basedOnContentRevision).isEqualTo(3)
        assertThat(sealed.third).isEqualTo(EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD)
    }

    @Test
    fun `provider 시작 뒤 만료된 리스는 runner를 재호출하지 않고 unknown으로 반환한다`() {
        val world = World(started = true)
        world.jobs.acquired = ActionGuideJobAcquire.RecoverUnknown(world.lease)
        var calls = 0
        world.runner =
            ActionGuideJobRunner {
                calls += 1
                ActionGuideProviderCall { validResult() }
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.RECOVERED_UNKNOWN)
        assertThat(calls).isZero()
        assertThat(world.ledger.unknowns).isEqualTo(1)
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.jobs.rows[JOB]?.failureCode).isEqualTo(ActionGuideJobFailureCode.OUTCOME_UNKNOWN)
    }

    @Test
    fun `리스 재획득 상한에 닿은 미시작 작업은 runner 없이 실패로 끝내고 예약을 되돌린다`() {
        val world = World()
        world.jobs.acquired = ActionGuideJobAcquire.DeadLettered(world.lease)
        var calls = 0
        world.runner =
            ActionGuideJobRunner {
                calls += 1
                ActionGuideProviderCall { validResult() }
            }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.DEAD_LETTERED)
        assertThat(calls).isZero()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.FAILED)
        assertThat(world.jobs.rows[JOB]?.failureCode).isEqualTo(ActionGuideJobFailureCode.GENERATION_FAILED)
        assertThat(world.jobs.rows[JOB]?.providerStartedAt).isNull()
    }

    @Test
    fun `기능을 끈 drain에서는 상한에 닿은 작업도 미시작 예약 반환으로 끝낸다`() {
        val world = World()
        world.jobs.acquired = ActionGuideJobAcquire.DeadLettered(world.lease)

        assertThat(world.processor().drainNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(world.credits.releases).isEqualTo(1)
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
        assertThat(world.contents.candidates).isEmpty()
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
        assertThat(world.contents.candidates).isEmpty()
    }

    @Test
    fun `획득 시점에 입력이 stale이면 provider 시작 없이 superseded로 예약을 반환한다`() {
        val world = World()
        world.jobs.currentInput = false
        var runnerCalls = 0
        world.runner =
            ActionGuideJobRunner {
                runnerCalls += 1
                ActionGuideProviderCall { validResult() }
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
    fun `생성 입력을 더 이상 찾지 못하면 provider 시작 없이 superseded로 예약을 반환한다`() {
        val world = World()
        // 시작 트랜잭션 안에서 입력을 확정하지 못한 경우다 — 사용자가 본문을 방금 고쳤다.
        world.runner = ActionGuideJobRunner { null }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(world.jobs.rows[JOB]?.providerStartedAt).isNull()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
    }

    @Test
    fun `생성 입력 준비가 실패하면 호출 없이 실패로 끝내고 예약을 반환한다`() {
        val world = World()
        // 복호화 실패·본문 없음처럼 다시 시도해도 같은 실패다. 예외가 그대로 올라가면 작업이
        // running 으로 남아 리스가 만료될 때마다 같은 자리에서 다시 깨진다.
        world.runner = ActionGuideJobRunner { throw DecryptionFailedException() }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.FAILED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.FAILED)
        assertThat(world.jobs.rows[JOB]?.failureCode).isEqualTo(ActionGuideJobFailureCode.GENERATION_FAILED)
        assertThat(world.jobs.rows[JOB]?.providerStartedAt).isNull()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.completes).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.credits.consumes).isZero()
    }

    @Test
    fun `정산 쓰기까지 실패하면 아무것도 정산하지 않고 다음 리스에 맡긴다`() {
        val world = World()
        world.runner = ActionGuideJobRunner { throw DecryptionFailedException() }
        // PostgreSQL 은 문장 하나가 실패하면 트랜잭션 전체를 중단한다 — 저장소가 끊긴 실패는
        // 아래 정산 쓰기도 함께 깨뜨려, 되돌릴 수 있는 실패가 종료 상태로 굳지 않는다.
        world.jobs.terminalWriteFailure = StorageException("저장소에 닿지 못했다")

        assertThatThrownBy { world.processor().processNext() }.isInstanceOf(StorageException::class.java)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.QUEUED)
        assertThat(world.jobs.rows[JOB]?.providerStartedAt).isNull()
        assertThat(world.credits.releases).isZero()
    }

    @Test
    fun `OFF drain은 queued 작업을 호출하지 않고 superseded로 예약 반환한다`() {
        val world = World()
        var runnerCalls = 0
        world.runner =
            ActionGuideJobRunner {
                runnerCalls += 1
                ActionGuideProviderCall { validResult() }
            }

        assertThat(world.processor().drainNext()).isEqualTo(ActionGuideJobOutcome.COMPLETED)
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(runnerCalls).isZero()
        assertThat(world.ledger.starts).isZero()
        assertThat(world.ledger.completes).isZero()
        assertThat(world.ledger.unknowns).isZero()
        assertThat(world.credits.releases).isEqualTo(1)
    }

    @Test
    fun `형식이나 근거가 무효인 완성 응답은 후보 없이 실패 정산하고 예약을 반환한다`() {
        val world = World()
        world.runner =
            ActionGuideJobRunner { ActionGuideProviderCall { ActionGuideRunResult.Invalid(completedRecord()) } }

        assertThat(world.processor().processNext()).isEqualTo(ActionGuideJobOutcome.FAILED)
        assertThat(world.jobs.rows[JOB]?.failureCode).isEqualTo(ActionGuideJobFailureCode.RESULT_INVALID)
        assertThat(world.contents.candidates).isEmpty()
        assertThat(world.ledger.completes).isEqualTo(1)
        assertThat(world.credits.releases).isEqualTo(1)
        assertThat(world.credits.consumes).isZero()
    }

    @Test
    fun `후보 삽입 실패는 성공 상태와 원장 및 크레딧 정산을 함께 롤백한다`() {
        val world = World()
        world.contents.insertSucceeds = false

        assertThatThrownBy { world.processor().processNext() }.isInstanceOf(StorageException::class.java)

        assertThat(world.contents.insertCalls).isEqualTo(1)
        assertThat(world.contents.candidates).isEmpty()
        assertThat(world.jobs.rows[JOB]?.status).isEqualTo(ActionGuideJobStatus.RUNNING)
        assertThat(world.jobs.terminalWrites).isEmpty()
        assertThat(world.ledger.starts).isEqualTo(1)
        assertThat(world.ledger.completes).isZero()
        assertThat(world.credits.consumes).isZero()
    }

    private class World(started: Boolean = false) {
        val lease = ActionGuideJobLease(JOB, "worker-1", 1)
        val jobs = FakeActionGuideJobs()
        val credits = RecordingActionGuideCredits()
        val ledger = RecordingActionGuideLedger()
        val contents = RecordingActionGuideContents()
        val cipher = FakeContentCipher(writeKeyVersion = 1)
        val transaction = ReversibleTransaction(jobs, credits, ledger, contents)
        var runner: ActionGuideJobRunner = ActionGuideJobRunner { ActionGuideProviderCall { validResult() } }

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
                contents,
                cipher,
                runner,
                transaction,
                ActionGuideJobWorkerPolicy("worker-1", Duration.ofSeconds(30), MAX_LEASE_ATTEMPTS),
                Clock.fixed(NOW, ZoneOffset.UTC),
            )
    }
}

/** 이 단위 테스트는 상한 판정을 저장소에 맡기므로 값 자체는 중요하지 않다. */
private const val MAX_LEASE_ATTEMPTS: Int = 5

private fun validResult() =
    ActionGuideRunResult.Valid(
        completedRecord(),
        ActionGuideCandidate(
            schemaVersion = 1,
            sections =
                ActionGuideSectionKind.entries.map { kind ->
                    ActionGuideSection(kind, ActionGuideSectionStatus.NOT_IN_SOURCE, emptyList())
                },
        ),
    )

private class RecordingActionGuideContents : ActionGuideContentRepository {
    val candidates = mutableListOf<StoredActionGuideCandidate>()
    var insertSucceeds = true
    var insertCalls = 0

    override fun insertCandidate(
        job: StoredActionGuideJob,
        candidate: StoredActionGuideCandidate,
    ): Boolean {
        insertCalls += 1
        if (!insertSucceeds) return false
        candidates += candidate
        return true
    }

    override fun findCandidateForJobOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideCandidate? = candidates.singleOrNull { it.jobId == jobId }

    override fun findCandidateOwned(
        ownerId: UUID,
        conversionId: UUID,
        candidateId: UUID,
    ): StoredActionGuideCandidate? = candidates.singleOrNull { it.candidateId == candidateId }

    override fun findGuideOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuide? = null

    override fun saveGuide(
        ownerId: UUID,
        expectedContentRevision: Long,
        expectedGuideRevision: Long?,
        guide: StoredActionGuide,
    ): Boolean = false
}

/** 각 정산 트랜잭션의 관측 가능한 저장 상태를 되돌리는 대역이다. 시작 트랜잭션은 이미 커밋된 채 남는다. */
private class ReversibleTransaction(
    private val jobs: FakeActionGuideJobs,
    private val credits: RecordingActionGuideCredits,
    private val ledger: RecordingActionGuideLedger,
    private val contents: RecordingActionGuideContents,
) : TransactionRunner {
    var depth = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        val rows = LinkedHashMap(jobs.rows)
        val terminalWrites = jobs.terminalWrites.toList()
        val candidates = contents.candidates.toList()
        val completes = ledger.completes
        val consumes = credits.consumes
        val releases = credits.releases
        depth += 1
        return try {
            block()
        } catch (failure: RuntimeException) {
            jobs.rows.clear()
            jobs.rows.putAll(rows)
            jobs.terminalWrites.clear()
            jobs.terminalWrites.addAll(terminalWrites)
            contents.candidates.clear()
            contents.candidates.addAll(candidates)
            ledger.completes = completes
            credits.consumes = consumes
            credits.releases = releases
            throw failure
        } finally {
            depth -= 1
        }
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

package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.llm.LlmCallRecord
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 접수 시 잠가 읽는 변환·문서 스냅샷. 소유권과 보존 기간은 저장소가 함께 확인한다. */
data class ActionGuideJobContext(
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val guideRevision: Long?,
    val charCount: Int,
    val completed: Boolean,
)

data class StoredActionGuideJob(
    val jobId: UUID,
    val ownerId: UUID,
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val requestId: UUID,
    val expectedGuideRevision: Long?,
    val basedOnContentRevision: Long,
    val reservedCredits: Int,
    val status: ActionGuideJobStatus,
    val failureCode: ActionGuideJobFailureCode?,
    val executionId: UUID?,
    val providerStartedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

sealed interface ActionGuideJobInsert {
    data class Inserted(val job: StoredActionGuideJob) : ActionGuideJobInsert

    /** 같은 요청 키가 잠금 밖의 경쟁으로 먼저 저장됐다. 호출자는 전체 트랜잭션을 롤백한다. */
    data object RequestConflict : ActionGuideJobInsert

    data object ActiveConflict : ActionGuideJobInsert

    data object AttemptLimit : ActionGuideJobInsert
}

data class ActionGuideJobLease(
    val jobId: UUID,
    val owner: String,
    val fence: Int,
)

sealed interface ActionGuideJobAcquire {
    data object Empty : ActionGuideJobAcquire

    /** 신규 작업 또는 provider 시작 전 만료 작업을 안전하게 획득했다. */
    data class Held(val lease: ActionGuideJobLease) : ActionGuideJobAcquire

    /** provider 시작 뒤 만료됐다. 새 호출 없이 불명확 실패로 정산해야 한다. */
    data class RecoverUnknown(val lease: ActionGuideJobLease) : ActionGuideJobAcquire
}

/** 접수·조회와 worker fencing을 함께 제공하는 별도 action_guide_jobs 저장소 포트. */
@Suppress("TooManyFunctions")
interface ActionGuideJobRepository {
    fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideJobContext?

    fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredActionGuideJob?

    fun insert(job: StoredActionGuideJob): ActionGuideJobInsert

    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideJob?

    fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob?

    /** 가장 최근 작업. 활성 작업과 같을 수 있다. */
    fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob?

    fun acquire(
        owner: String,
        leaseDuration: Duration,
    ): ActionGuideJobAcquire

    /** fencing이 유효하면 작업 행을 잠가 반환한다. */
    fun lockIfHeld(lease: ActionGuideJobLease): StoredActionGuideJob?

    /** 현재 변환이 존재·보존 중이고 작업의 입력 본문 버전과 같은지 확인한다. */
    fun hasCurrentInput(
        lease: ActionGuideJobLease,
        basedOnContentRevision: Long,
    ): Boolean

    /** 호출 시작 표시는 provider 호출보다 먼저 커밋된다. */
    fun markProviderStarted(
        lease: ActionGuideJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean

    fun markSucceeded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean

    fun markFailed(
        lease: ActionGuideJobLease,
        failureCode: ActionGuideJobFailureCode,
        updatedAt: Instant,
    ): Boolean

    fun markSuperseded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean
}

sealed interface ActionGuideCreditReservation {
    data class Reserved(val available: Int) : ActionGuideCreditReservation

    data class Insufficient(val available: Int) : ActionGuideCreditReservation
}

/** 행동 안내문 예약은 job_id를 고유 참조로 사용해 변환 예약과 섞이지 않는다. */
interface ActionGuideCreditPort {
    fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): Int

    @Suppress("LongParameterList")
    fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): ActionGuideCreditReservation

    fun consume(job: StoredActionGuideJob)

    fun release(job: StoredActionGuideJob)
}

/** `llm_calls`의 action_guide 실행 한 건을 job 실행 식별자로 upsert한다. */
interface ActionGuideLlmCallLedger {
    fun start(
        job: StoredActionGuideJob,
        executionId: UUID,
        startedAt: Instant,
    )

    fun complete(
        job: StoredActionGuideJob,
        executionId: UUID,
        record: LlmCallRecord,
    )

    fun markOutcomeUnknown(
        job: StoredActionGuideJob,
        executionId: UUID,
        recoveredAt: Instant,
    )
}

/** ER-06의 실제 생성기가 구현할 경계. ER-05에서는 fake runner만 사용한다. */
fun interface ActionGuideJobRunner {
    fun run(job: StoredActionGuideJob): LlmCallRecord
}

data class ActionGuideJobWorkerPolicy(
    val owner: String,
    val leaseDuration: Duration,
) {
    init {
        require(owner.isNotBlank()) { "worker owner가 비어 있습니다" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "리스 수명이 양수가 아닙니다" }
    }
}

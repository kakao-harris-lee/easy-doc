package kr.easydoc.application.illustration.suggestion

import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSet
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 접수 시 잠가 읽는 변환·문서 스냅샷. 소유권과 보존 기간은 저장소가 함께 확인한다. */
data class IllustrationSuggestionJobContext(
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val charCount: Int,
    val completed: Boolean,
)

data class StoredIllustrationSuggestionJob(
    val jobId: UUID,
    val ownerId: UUID,
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val requestId: UUID,
    val basedOnContentRevision: Long,
    val reservedCredits: BigDecimal,
    val status: IllustrationSuggestionJobStatus,
    val failureCode: IllustrationSuggestionJobFailureCode?,
    val executionId: UUID?,
    val providerStartedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

sealed interface IllustrationSuggestionJobInsert {
    data class Inserted(val job: StoredIllustrationSuggestionJob) : IllustrationSuggestionJobInsert

    /** 같은 요청 키가 잠금 밖의 경쟁으로 먼저 저장됐다. 호출자는 전체 트랜잭션을 롤백한다. */
    data object RequestConflict : IllustrationSuggestionJobInsert

    data object ActiveConflict : IllustrationSuggestionJobInsert

    data object AttemptLimit : IllustrationSuggestionJobInsert
}

data class IllustrationSuggestionJobLease(
    val jobId: UUID,
    val owner: String,
    val fence: Int,
)

sealed interface IllustrationSuggestionJobAcquire {
    data object Empty : IllustrationSuggestionJobAcquire

    /** 신규 작업 또는 provider 시작 전 만료 작업을 안전하게 획득했다. */
    data class Held(val lease: IllustrationSuggestionJobLease) : IllustrationSuggestionJobAcquire

    /** provider 시작 뒤 만료됐다. 새 호출 없이 불명확 실패로 정산해야 한다. */
    data class RecoverUnknown(val lease: IllustrationSuggestionJobLease) : IllustrationSuggestionJobAcquire

    /**
     * provider 를 시작하지 못한 채 리스 재획득 상한에 닿았다. 다시 worker 에 넘기지 않고 그
     * 자리에서 실패로 정산한다 — 결정적으로 깨지는 작업이 예약·worker slot·계정의 활성 작업
     * 자리를 영원히 붙잡지 않게 한다(PR #154 와 같은 dead-letter).
     */
    data class DeadLettered(val lease: IllustrationSuggestionJobLease) : IllustrationSuggestionJobAcquire
}

/** 접수·조회와 worker fencing 을 함께 제공하는 `illustration_suggestion_jobs` 저장소 포트. */
@Suppress("TooManyFunctions")
interface IllustrationSuggestionJobRepository {
    fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext?

    fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationSuggestionJob?

    fun insert(job: StoredIllustrationSuggestionJob): IllustrationSuggestionJobInsert

    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationSuggestionJob?

    fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob?

    /** 가장 최근 작업. 활성 작업과 같을 수 있다. */
    fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob?

    /** [maxLeaseAttempts] 를 넘겨 다시 얻은 미시작 작업은 [IllustrationSuggestionJobAcquire.DeadLettered] 다. */
    fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationSuggestionJobAcquire

    /** fencing 이 유효하면 작업 행을 잠가 반환한다. */
    fun lockIfHeld(lease: IllustrationSuggestionJobLease): StoredIllustrationSuggestionJob?

    /** 현재 변환이 존재·보존 중이고 작업의 입력 본문 버전과 같은지 확인한다. */
    fun hasCurrentInput(
        lease: IllustrationSuggestionJobLease,
        basedOnContentRevision: Long,
    ): Boolean

    /** 호출 시작 표시는 provider 호출보다 먼저 커밋된다. */
    fun markProviderStarted(
        lease: IllustrationSuggestionJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean

    fun markSucceeded(
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean

    fun markFailed(
        lease: IllustrationSuggestionJobLease,
        failureCode: IllustrationSuggestionJobFailureCode,
        updatedAt: Instant,
    ): Boolean

    fun markSuperseded(
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean
}

/** 저장된 제안 결과 한 건. 평문은 이 경계를 지나 DB 에 남지 않는다. */
data class StoredIllustrationSuggestionResult(
    val resultId: UUID,
    val jobId: UUID,
    val conversionId: UUID,
    val basedOnContentRevision: Long,
    val payload: EncryptedContent,
    val createdAt: Instant,
)

/** 조회는 소유권과 문서 보존기간을 같은 SQL 에서 확인한다. */
interface IllustrationSuggestionResultRepository {
    /** 잠긴 실행 작업에서만 결과를 삽입한다. 본문 버전이 바뀌었으면 false. */
    fun insertResult(
        job: StoredIllustrationSuggestionJob,
        result: StoredIllustrationSuggestionResult,
    ): Boolean

    /** 변환 하나의 가장 최근 결과. 없으면 null. */
    fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionResult?
}

sealed interface IllustrationSuggestionCreditReservation {
    data class Reserved(val available: BigDecimal) : IllustrationSuggestionCreditReservation

    data class Insufficient(val available: BigDecimal) : IllustrationSuggestionCreditReservation
}

/**
 * 제안 분석 예약은 job_id 를 고유 참조로 사용해 변환·행동 안내 예약과 섞이지 않는다.
 *
 * 예약량이 0 인 작업(fake 모드, 명세 §3)에는 거래 행을 만들지 않는다 — 구현은 그 갈래를
 * 스스로 걸러야 한다.
 */
interface IllustrationSuggestionCreditPort {
    fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal

    @Suppress("LongParameterList")
    fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationSuggestionCreditReservation

    fun consume(job: StoredIllustrationSuggestionJob)

    fun release(job: StoredIllustrationSuggestionJob)
}

/** `llm_calls` 의 illustration_suggestion 실행 한 건을 job 실행 식별자로 upsert 한다. */
interface IllustrationSuggestionLlmCallLedger {
    fun start(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        startedAt: Instant,
    )

    fun complete(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        record: LlmCallRecord,
    )

    fun markOutcomeUnknown(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        recoveredAt: Instant,
    )
}

/**
 * 검증된 제안 집합과 호출 원장 결과를 함께 돌려줘 성공 정산과 결과 저장을 한 트랜잭션에 묶는다.
 *
 * [Invalid] 는 「응답은 받았지만 쓸 수 없다」다 — 구조 위반과 **모든 제안 탈락**이 같은 갈래로
 * 모인다(명세 §4: 둘 다 `result_invalid` 이고 이용량을 반환한다). 제안이 0건인 정상 결과
 * (`제안 없음`)는 [Valid] 다 — 그쪽은 소비한다.
 */
sealed interface IllustrationSuggestionRunResult {
    val record: LlmCallRecord

    data class Valid(
        override val record: LlmCallRecord,
        val suggestions: IllustrationSuggestionSet,
    ) : IllustrationSuggestionRunResult

    data class Invalid(override val record: LlmCallRecord) : IllustrationSuggestionRunResult

    data class ProviderFailed(override val record: LlmCallRecord) : IllustrationSuggestionRunResult
}

/** 확정된 입력으로 provider 를 한 번 호출한다. 호출 자체는 트랜잭션 밖에서 실행한다. */
fun interface IllustrationSuggestionProviderCall {
    fun call(): IllustrationSuggestionRunResult
}

fun interface IllustrationSuggestionJobRunner {
    /**
     * 시작 트랜잭션 안에서 분석 입력을 확정한다. 입력이 더 이상 유효하지 않으면 `null` 이며,
     * 호출자는 provider 시작 표시 없이 superseded 로 정산한다 — 시작하지 않은 호출은 시도가 아니다.
     */
    fun prepare(job: StoredIllustrationSuggestionJob): IllustrationSuggestionProviderCall?
}

data class IllustrationSuggestionJobWorkerPolicy(
    val owner: String,
    val leaseDuration: Duration,
    val maxLeaseAttempts: Int,
) {
    init {
        require(owner.isNotBlank()) { "worker owner가 비어 있습니다" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "리스 수명이 양수가 아닙니다" }
        // 위쪽 상한이 없으면 자릿수 오타 하나가 상한을 사실상 없애 버린다 — 기동에서 걸러낸다.
        require(maxLeaseAttempts in 1..MAX_ALLOWED_LEASE_ATTEMPTS) {
            "리스 재획득 상한은 1 이상 $MAX_ALLOWED_LEASE_ATTEMPTS 이하여야 합니다"
        }
    }

    companion object {
        /** 리스 수명이 120초면 100회는 이미 3시간이 넘는다. 그보다 큰 값은 상한이 아니다. */
        const val MAX_ALLOWED_LEASE_ATTEMPTS: Int = 100
    }
}

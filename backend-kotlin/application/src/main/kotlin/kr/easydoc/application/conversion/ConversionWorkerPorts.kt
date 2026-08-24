package kr.easydoc.application.conversion

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.ConversionStatus
import java.time.Duration
import java.util.UUID

/**
 * 변환 작업 한 건의 **리스**. [owner] 와 [attempts] 가 fencing 토큰이다 —
 * 만료 후 다른 worker 가 집을 때 attempts 가 올라가므로, 늦게 끝난 옛 실행은
 * 완료·실패 갱신에서 0행이 된다.
 */
class ConversionJobLease(
    val conversionId: UUID,
    val owner: String,
    val attempts: Int,
) {
    override fun toString(): String = "ConversionJobLease($conversionId, owner=$owner, attempts=$attempts)"
}

/**
 * [ConversionJobLeasePort.acquire] 의 결과. 만료 회수가 시도 상한을 넘기면 큐 행을
 * 실패한 채로 돌려보낸다 — 호출자가 같은 트랜잭션에서 변환 행도 실패로 맞춰야 한다.
 */
sealed interface ConversionAcquire {
    /** 집을 작업이 없다. */
    object Empty : ConversionAcquire

    /** 이 worker 가 리스를 쥐었다. */
    class Held(val lease: ConversionJobLease) : ConversionAcquire

    /** 시도 상한을 넘겨 큐에서 이미 실패로 확정했다. */
    class Exhausted(val conversionId: UUID) : ConversionAcquire
}

/** worker 가 `conversion_jobs` 를 소비할 때 쓰는 포트. 등록([ConversionQueue])과 축이 다르다. */
interface ConversionJobLeasePort {
    /**
     * 집을 수 있는 작업 한 건을 집어 [owner] 에게 맡긴다.
     * 만료된 작업의 시도가 이미 [maxAttempts] 이상이면 큐 행을 실패로 확정하고 [ConversionAcquire.Exhausted] 를 돌려준다.
     */
    fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxAttempts: Int,
    ): ConversionAcquire

    /** 아직 이 리스가 유효하면 만료를 연장한다. 연장됐으면 `true`. */
    fun renew(
        lease: ConversionJobLease,
        leaseDuration: Duration,
    ): Boolean

    /**
     * 이 리스가 **지금** 이 행을 쥐고 있으면 잠근다. 완료 쓰기의 같은 트랜잭션에서 부른다 —
     * 잠금이 서야 다른 worker 의 회수가 뒤에서 기다린다.
     */
    fun lockIfHeld(lease: ConversionJobLease): Boolean

    /** 처리를 끝낸다. fencing 이 맞으면 `true`. */
    fun complete(lease: ConversionJobLease): Boolean

    /** 재시도 대기열로 되돌린다. fencing 이 맞으면 `true`. */
    fun retry(
        lease: ConversionJobLease,
        delay: Duration,
    ): Boolean

    /** 재시도 없이 실패로 확정한다. fencing 이 맞으면 `true`. */
    fun fail(lease: ConversionJobLease): Boolean
}

/** worker 가 변환 행·원문을 읽고 결과를 쓸 때 쓰는 포트. 사용자 조회 포트와 축이 다르다. */
class ConversionWorkItem(
    val conversionId: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    val sourceText: EncryptedContent,
) {
    override fun toString(): String =
        "ConversionWorkItem($conversionId, doc=$documentId, ${status.wireName}, " +
            "source=${sourceText.bytes.size}바이트)"
}

/** 성공 결과를 한 UPDATE 에 실을 값. 암호문은 유스케이스가 봉인한 것이다. */
class ConversionSuccessWrite(
    val easyText: EncryptedContent,
    val maskedItems: EncryptedContent,
    val missingPlaceholders: List<String>,
    val attribution: LlmAttribution,
    val usage: ConversionUsage,
)

interface ConversionWorkStore {
    /** 변환과 원문 암호문을 읽고 **두 행을 잠근다**. 없거나 문서가 지워졌으면 `null`. */
    fun loadForProcessing(conversionId: UUID): ConversionWorkItem?

    /** `pending` 또는 이미 `processing` 인 행을 `processing` 으로 둔다. 갱신됐으면 `true`. */
    fun markProcessing(conversionId: UUID): Boolean

    /**
     * 완료 결과를 쓴다. **아직 끝나지 않은 행만** 받는다 — 이미 `done`/`failed` 이면 0행.
     */
    fun saveSuccess(
        conversionId: UUID,
        write: ConversionSuccessWrite,
    ): Boolean

    /** 실패를 쓴다. 이미 끝난 행은 덮지 않는다. */
    fun saveFailure(
        conversionId: UUID,
        failureCode: String,
        usage: ConversionUsage,
        attribution: LlmAttribution,
    ): Boolean

    /** 재시도 전에 사용자에게 다시 대기로 보이게 한다. 이미 끝난 행은 되돌리지 않는다. */
    fun revertToPending(conversionId: UUID): Boolean
}

/** LLM 호출 동안 리스를 연장한다. */
interface ConversionJobHeartbeat {
    fun <T> whileHeld(
        lease: ConversionJobLease,
        block: () -> T,
    ): T
}

/** worker 실행 정책. 값은 설정에서 온다. */
class ConversionWorkerPolicy(
    val owner: String,
    val leaseDuration: Duration,
    val maxAttempts: Int,
    val retryBackoff: Duration,
) {
    init {
        require(owner.isNotBlank()) { "worker 식별자가 비어 있다" }
        require(owner.length <= OWNER_MAX_LENGTH) { "worker 식별자가 ${OWNER_MAX_LENGTH}자를 넘는다" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "리스 수명이 양수가 아니다" }
        require(maxAttempts >= 1) { "재시도 상한이 1보다 작다" }
        require(!retryBackoff.isNegative) { "재시도 대기가 음수다" }
    }

    companion object {
        /** `conversion_jobs.lease_owner` 컬럼 길이. */
        const val OWNER_MAX_LENGTH: Int = 64
    }
}

/** 한 번의 폴링이 남긴 결과. */
enum class ConversionJobOutcome {
    /** 집을 작업이 없었다. */
    IDLE,

    /** 변환이 완료됐다. */
    COMPLETED,

    /** 실패로 확정됐다. */
    FAILED,

    /** 나중에 다시 집도록 대기열로 돌려보냈다. */
    RETRY_SCHEDULED,

    /** 행이 사라졌거나 다른 worker 가 리스를 가져갔다. */
    DROPPED,
}

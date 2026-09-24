package kr.easydoc.application.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSet
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 블록을 그대로 실행하되 **예외가 나가면 작업 표를 되돌린다** — PostgreSQL 의 전부-아니면-전무를
 * 모사한다. 이 되돌림이 없으면 「잔액 부족 402 는 작업 행을 남기지 않는다」 같은 단언이 대역에서만
 * 거짓이 되어, 접수의 잠금 순서를 바꾸는 편집이 실제 동작과 무관하게 빨개진다.
 */
internal class DirectSuggestionTransaction(private val jobs: FakeSuggestionJobs? = null) : TransactionRunner {
    // 도메인 예외는 전부 RuntimeException 이고, 어느 갈래로 끊기든 되돌림은 같다 — 갈래를 좁히면
    // 새 예외 타입이 조용히 되돌림을 건너뛴다.
    @Suppress("TooGenericExceptionCaught")
    override fun <T> inTransaction(block: () -> T): T {
        val snapshot = jobs?.rows?.toMap()
        return try {
            block()
        } catch (failure: RuntimeException) {
            if (snapshot != null) {
                jobs.rows.clear()
                jobs.rows.putAll(snapshot)
            }
            throw failure
        }
    }
}

internal open class FakeSuggestionJobs(var context: IllustrationSuggestionJobContext? = defaultSuggestionContext()) :
    IllustrationSuggestionJobRepository {
    val rows = linkedMapOf<UUID, StoredIllustrationSuggestionJob>()
    var acquired: IllustrationSuggestionJobAcquire = IllustrationSuggestionJobAcquire.Empty
    var held = true
    var currentInput = true
    var currentInputCheck: (() -> Boolean)? = null
    var terminalWriteSucceeds = true
    val terminalWrites = mutableListOf<Pair<IllustrationSuggestionJobStatus, IllustrationSuggestionJobFailureCode?>>()

    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext? = context

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationSuggestionJob? =
        rows.values.singleOrNull {
            it.ownerId == ownerId && it.conversionId == conversionId && it.requestId == requestId
        }

    // 상한·활성 충돌·정상 삽입은 서로 다른 결과라 갈래마다 끊는다(저장소와 같은 형태).
    @Suppress("ReturnCount")
    override fun insert(job: StoredIllustrationSuggestionJob): IllustrationSuggestionJobInsert {
        // 저장소(`JdbcIllustrationSuggestionJobRepository`)와 같은 규칙 — provider 호출이 실제로
        // 시작된 작업만 센다. 대역이 다른 규칙을 쓰면 서비스 계약을 여기서 확인할 수 없다.
        val started =
            rows.values.count {
                it.ownerId == job.ownerId && it.conversionId == job.conversionId && it.providerStartedAt != null
            }
        if (started >= MAX_SUGGESTION_PROVIDER_ATTEMPTS) return IllustrationSuggestionJobInsert.AttemptLimit
        if (rows.values.any { it.conversionId == job.conversionId && it.status.active }) {
            return IllustrationSuggestionJobInsert.ActiveConflict
        }
        rows[job.jobId] = job
        return IllustrationSuggestionJobInsert.Inserted(job)
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationSuggestionJob? =
        rows[jobId]?.takeIf { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob? =
        rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId && it.status.active }

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob? =
        rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationSuggestionJobAcquire = acquired

    override fun lockIfHeld(lease: IllustrationSuggestionJobLease): StoredIllustrationSuggestionJob? =
        if (held) rows[lease.jobId] else null

    override fun hasCurrentInput(
        lease: IllustrationSuggestionJobLease,
        basedOnContentRevision: Long,
    ): Boolean = currentInputCheck?.invoke() ?: currentInput

    override fun markProviderStarted(
        lease: IllustrationSuggestionJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean {
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] =
            current.copy(
                status = IllustrationSuggestionJobStatus.RUNNING,
                executionId = executionId,
                providerStartedAt = startedAt,
                updatedAt = startedAt,
            )
        return true
    }

    override fun markSucceeded(
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationSuggestionJobStatus.SUCCEEDED, null, updatedAt)

    override fun markFailed(
        lease: IllustrationSuggestionJobLease,
        failureCode: IllustrationSuggestionJobFailureCode,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationSuggestionJobStatus.FAILED, failureCode, updatedAt)

    override fun markSuperseded(
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationSuggestionJobStatus.SUPERSEDED, null, updatedAt)

    @Suppress("ReturnCount")
    private fun terminal(
        lease: IllustrationSuggestionJobLease,
        status: IllustrationSuggestionJobStatus,
        failureCode: IllustrationSuggestionJobFailureCode?,
        updatedAt: Instant,
    ): Boolean {
        if (!terminalWriteSucceeds) return false
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] = current.copy(status = status, failureCode = failureCode, updatedAt = updatedAt)
        terminalWrites += status to failureCode
        return true
    }
}

internal class RecordingSuggestionCredits : IllustrationSuggestionCreditPort {
    var available = BigDecimal("7.0")
    var reservation: IllustrationSuggestionCreditReservation =
        IllustrationSuggestionCreditReservation.Reserved(available)
    var reserveCalls = 0
    val reservedAmounts = mutableListOf<Credits>()
    var consumes = 0
    var releases = 0

    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal = available

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationSuggestionCreditReservation {
        reserveCalls += 1
        reservedAmounts += amount
        return reservation
    }

    override fun consume(job: StoredIllustrationSuggestionJob) {
        consumes += 1
    }

    override fun release(job: StoredIllustrationSuggestionJob) {
        releases += 1
    }
}

internal class RecordingSuggestionLedger : IllustrationSuggestionLlmCallLedger {
    var starts = 0
    var completes = 0
    var unknowns = 0

    override fun start(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        startedAt: Instant,
    ) {
        starts += 1
    }

    override fun complete(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) {
        completes += 1
    }

    override fun markOutcomeUnknown(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
    ) {
        unknowns += 1
    }
}

internal class RecordingSuggestionResults : IllustrationSuggestionResultRepository {
    val inserted = mutableListOf<StoredIllustrationSuggestionResult>()
    var insertSucceeds = true
    var latest: StoredIllustrationSuggestionResult? = null

    override fun insertResult(
        job: StoredIllustrationSuggestionJob,
        result: StoredIllustrationSuggestionResult,
    ): Boolean {
        if (!insertSucceeds) return false
        inserted += result
        latest = result
        return true
    }

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionResult? = latest
}

/** 평문을 그대로 들고 다니는 대역. 이 계층은 암호 자체를 재지 않는다. */
internal class PassThroughCipher : ContentCipher {
    override val writeScheme: String = "aes256gcm-v1"
    override val writeKeyVersion: Int = 1

    override fun encryptBytes(
        plain: PlainBytes,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent = EncryptedContent(plain.value, writeScheme, writeKeyVersion)

    override fun decryptBytes(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBytes = PlainBytes(content.bytes)
}

internal fun defaultSuggestionContext() =
    IllustrationSuggestionJobContext(
        SUGGESTION_WORKSPACE,
        SUGGESTION_DOCUMENT,
        SUGGESTION_CONVERSION,
        contentRevision = 3,
        charCount = 1_500,
        completed = true,
    )

internal fun storedSuggestionJob(
    status: IllustrationSuggestionJobStatus = IllustrationSuggestionJobStatus.QUEUED,
    requestId: UUID = SUGGESTION_REQUEST,
    executionId: UUID? = null,
    providerStartedAt: Instant? = null,
    jobId: UUID = SUGGESTION_JOB,
) = StoredIllustrationSuggestionJob(
    jobId,
    SUGGESTION_OWNER,
    SUGGESTION_WORKSPACE,
    SUGGESTION_DOCUMENT,
    SUGGESTION_CONVERSION,
    requestId,
    basedOnContentRevision = 3,
    reservedCredits = BigDecimal("2.0"),
    status = status,
    failureCode = null,
    executionId = executionId,
    providerStartedAt = providerStartedAt,
    createdAt = SUGGESTION_NOW,
    updatedAt = SUGGESTION_NOW,
)

internal fun suggestionSet(count: Int = 1): IllustrationSuggestionSet =
    IllustrationSuggestionSet(
        schemaVersion = 1,
        analysisVersion = ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
        suggestions = (0 until count).map { index -> suggestion(index) },
        droppedCount = 0,
    )

internal fun suggestion(index: Int = 0): IllustrationSuggestion =
    IllustrationSuggestion(
        suggestionId = UUID.nameUUIDFromBytes("suggestion-$index".toByteArray()),
        purpose = IllustrationSuggestionPurpose.PROCEDURE,
        reason = "행동 순서를 그림으로 보면 이해하기 쉬워집니다",
        bodyRange = IllustrationSuggestionBodyRange(0, 1),
        sourceAnchors = listOf(IllustrationSuggestionSourceAnchor(listOf(index), "신청서를 제출합니다")),
        scenes = listOf("신청서를 내는 장면"),
        preservedFacts = listOf("신청서를 내야 한다"),
        altTextDraft = "신청 순서를 보여 주는 그림",
    )

internal val SUGGESTION_OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
internal val SUGGESTION_WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
internal val SUGGESTION_DOCUMENT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
internal val SUGGESTION_CONVERSION: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
internal val SUGGESTION_REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a5")
internal val SUGGESTION_JOB: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a6")
internal val SUGGESTION_EXECUTION: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a7")
internal val SUGGESTION_NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")

/** 저장소 기본 구성값(`max-provider-attempts-per-conversion`)과 같은 값이어야 한다. */
internal const val MAX_SUGGESTION_PROVIDER_ATTEMPTS: Int = 3

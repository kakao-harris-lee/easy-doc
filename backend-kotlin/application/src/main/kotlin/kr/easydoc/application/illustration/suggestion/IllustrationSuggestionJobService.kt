package kr.easydoc.application.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.CONTENT_REVISION_INVALID_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_DONE_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.MAX_SAFE_REVISION
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class IllustrationSuggestionJobView(
    val jobId: UUID,
    val requestId: UUID,
    val status: IllustrationSuggestionJobStatus,
    val basedOnContentRevision: Long,
    val reservedCredits: BigDecimal,
    val failureCode: IllustrationSuggestionJobFailureCode?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class IllustrationSuggestionJobCollectionView(
    val activeJob: IllustrationSuggestionJobView?,
    val latestJob: IllustrationSuggestionJobView?,
    /** 이용량 단가가 설정되지 않았으면 `null` — 0과 다르다(명세 §3). */
    val requiredCredits: BigDecimal?,
    val availableCredits: BigDecimal,
)

data class IllustrationSuggestionJobCreationView(
    val job: IllustrationSuggestionJobView,
    val availableCredits: BigDecimal,
)

class IllustrationSuggestionAttemptLimitExceededException(message: String) : EasyDocException(message)

/**
 * 그림 제안 분석 작업의 접수·조회(명세 §2·§3·§6).
 *
 * 이용량 단가([creditsPer100Chars])가 **없으면** 기능이 켜져 있어도 접수를 503 으로 거부한다
 * (`ConfigurationException`) — 「무료」가 아니라 「운영 설정이 빠졌다」이기 때문이다. 0 은
 * 설정된 값이며 fake 모드에서만 쓰인다(실제 provider worker 는 기동에서 0을 거부한다).
 */
class IllustrationSuggestionJobService(
    private val enabled: Boolean,
    private val creditsPer100Chars: BigDecimal?,
    private val jobs: IllustrationSuggestionJobRepository,
    private val credits: IllustrationSuggestionCreditPort,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Suppress("LongMethod", "ThrowsCount") // 멱등 확인부터 예약·삽입까지 한 트랜잭션의 순서를 보인다.
    fun create(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        expectedContentRevision: Long,
    ): IllustrationSuggestionJobCreationView {
        requireEnabled()
        val rate = requireConfiguredRate()
        if (expectedContentRevision !in 1..MAX_SAFE_REVISION) {
            throw InvalidInputException(CONTENT_REVISION_INVALID_MESSAGE)
        }
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)

            jobs.findByRequestId(ownerId, conversionId, requestId)?.let { existing ->
                if (existing.basedOnContentRevision != expectedContentRevision) {
                    throw ConflictException(SUGGESTION_REQUEST_ID_CONFLICT_MESSAGE)
                }
                return@inTransaction IllustrationSuggestionJobCreationView(
                    existing.toView(),
                    credits.available(ownerId, context.workspaceId),
                )
            }

            if (!context.completed) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            if (context.contentRevision != expectedContentRevision) {
                throw ConflictException(SUGGESTION_CONTENT_REVISION_CONFLICT_MESSAGE)
            }

            val required = requiredCreditsFor(context.charCount, rate)
            val now = clock.instant()
            val candidate =
                StoredIllustrationSuggestionJob(
                    jobId = UUID.randomUUID(),
                    ownerId = ownerId,
                    workspaceId = context.workspaceId,
                    documentId = context.documentId,
                    conversionId = conversionId,
                    requestId = requestId,
                    basedOnContentRevision = expectedContentRevision,
                    reservedCredits = required.amount,
                    status = IllustrationSuggestionJobStatus.QUEUED,
                    failureCode = null,
                    executionId = null,
                    providerStartedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            val available =
                when (
                    val reservation =
                        credits.reserve(ownerId, context.workspaceId, context.documentId, candidate.jobId, required)
                ) {
                    is IllustrationSuggestionCreditReservation.Reserved -> {
                        reservation.available
                    }

                    is IllustrationSuggestionCreditReservation.Insufficient -> {
                        throw InsufficientCreditsException(
                            INSUFFICIENT_SUGGESTION_CREDITS_MESSAGE,
                            reservation.available,
                            required.amount,
                        )
                    }
                }
            val inserted =
                when (val result = jobs.insert(candidate)) {
                    is IllustrationSuggestionJobInsert.Inserted -> {
                        result.job
                    }

                    IllustrationSuggestionJobInsert.ActiveConflict -> {
                        throw ConflictException(SUGGESTION_ACTIVE_JOB_CONFLICT_MESSAGE)
                    }

                    IllustrationSuggestionJobInsert.AttemptLimit -> {
                        throw IllustrationSuggestionAttemptLimitExceededException(SUGGESTION_ATTEMPT_LIMIT_MESSAGE)
                    }

                    IllustrationSuggestionJobInsert.RequestConflict -> {
                        throw ConflictException(SUGGESTION_REQUEST_ID_CONFLICT_MESSAGE)
                    }
                }
            IllustrationSuggestionJobCreationView(inserted.toView(), available)
        }
    }

    fun list(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobCollectionView {
        requireEnabled()
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val active = jobs.findActiveOwned(ownerId, conversionId)
            IllustrationSuggestionJobCollectionView(
                activeJob = active?.toView(),
                latestJob = (active ?: jobs.findLatestOwned(ownerId, conversionId))?.toView(),
                requiredCredits = creditsPer100Chars?.let { requiredCreditsFor(context.charCount, it).amount },
                availableCredits = credits.available(ownerId, context.workspaceId),
            )
        }
    }

    fun get(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): IllustrationSuggestionJobView {
        requireEnabled()
        return transaction.inTransaction {
            jobs.lockOwnedContext(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            jobs.findOwned(ownerId, conversionId, jobId)?.toView()
                ?: throw NotFoundException(SUGGESTION_JOB_NOT_FOUND_MESSAGE)
        }
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun requireConfiguredRate(): BigDecimal =
        creditsPer100Chars ?: throw ConfigurationException(SUGGESTION_CREDITS_UNCONFIGURED_MESSAGE)
}

/**
 * `ceil(원문 글자 수 / 100) * 단가` — 올림 방식은 [Credits.requiredFor] 와 같고 단가만 구성값이다
 * (명세 §3). 문자 수는 `Long` 으로 올려 `Int` 덧셈 overflow 를 피한다.
 */
internal fun requiredCreditsFor(
    charCount: Int,
    creditsPer100Chars: BigDecimal,
): Credits {
    require(charCount >= 0) { "문자 수는 음수일 수 없습니다: $charCount" }
    val units = (charCount.toLong() + CHARS_PER_CREDIT_UNIT - 1) / CHARS_PER_CREDIT_UNIT
    return Credits(creditsPer100Chars.multiply(BigDecimal.valueOf(units)))
}

private const val CHARS_PER_CREDIT_UNIT = 100

internal fun StoredIllustrationSuggestionJob.toView(): IllustrationSuggestionJobView =
    IllustrationSuggestionJobView(
        jobId,
        requestId,
        status,
        basedOnContentRevision,
        reservedCredits,
        failureCode,
        createdAt,
        updatedAt,
    )

const val SUGGESTION_JOB_NOT_FOUND_MESSAGE: String = "그림 제안 분석 작업을 찾을 수 없습니다"
const val SUGGESTION_REQUEST_ID_CONFLICT_MESSAGE: String = "같은 요청 식별자가 다른 입력에 사용됐습니다"
const val SUGGESTION_CONTENT_REVISION_CONFLICT_MESSAGE: String = "본문 버전이 변경됐습니다"
const val SUGGESTION_ACTIVE_JOB_CONFLICT_MESSAGE: String = "이미 진행 중인 그림 제안 분석 작업이 있습니다"
const val SUGGESTION_ATTEMPT_LIMIT_MESSAGE: String = "그림 제안 분석 횟수를 모두 사용했습니다"
const val INSUFFICIENT_SUGGESTION_CREDITS_MESSAGE: String = "그림 제안 분석에 필요한 이용량이 부족합니다"

/** 503 문구. 사용자 잘못이 아니라 운영 설정이 빠진 상태다(명세 §3). */
const val SUGGESTION_CREDITS_UNCONFIGURED_MESSAGE: String = "그림 제안 분석 이용량이 설정되지 않았습니다"

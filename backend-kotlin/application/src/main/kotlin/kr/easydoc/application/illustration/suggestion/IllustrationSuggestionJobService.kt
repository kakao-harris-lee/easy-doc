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
            // **작업 INSERT 가 이용량 예약보다 먼저다.** 정산(`ProcessIllustrationSuggestionJob.settle`)은
            // 작업 행을 먼저 잠그고 그 다음 이용량 계정을 잡는다. 접수가 계정을 먼저 잡으면 두 순서가
            // 엇갈려 교착한다 — 접수는 계정을 든 채 새 작업의 활성 부분 UNIQUE 항목이 풀리기를
            // 기다리고, 정산은 그 계정을 기다린다. 교착에서 정산이 죽으면 **이미 돈을 쓴 호출의
            // 결과가 사라지고** 작업은 리스 만료 뒤 `outcome_unknown` 으로 정리되며 시도 상한만 깎인다.
            // 잔액이 모자라면 아래 예약이 402 로 끊고 이 INSERT 까지 함께 롤백된다.
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
 *
 * **유상 단가에서는 최소 한 단위를 부과한다.** 빈 원문(0자)이 0을 내면 유상 모드에서도 예약이
 * 없는 `not_charged` 작업이 생겨, fake 모드의 무과금과 원장에서 구분되지 않는다 — 호출은
 * 실제로 나가는데 과금 근거만 사라지는 자리다. 단가가 0이면 그대로 0이다(fake 모드).
 */
internal fun requiredCreditsFor(
    charCount: Int,
    creditsPer100Chars: BigDecimal,
): Credits {
    require(charCount >= 0) { "문자 수는 음수일 수 없습니다: $charCount" }
    val measured = (charCount.toLong() + CHARS_PER_CREDIT_UNIT - 1) / CHARS_PER_CREDIT_UNIT
    val units = if (creditsPer100Chars.signum() > 0) maxOf(1L, measured) else measured
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

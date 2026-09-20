package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.CONTENT_REVISION_INVALID_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_DONE_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.MAX_SAFE_REVISION
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ActionGuideJobView(
    val jobId: UUID,
    val requestId: UUID,
    val status: ActionGuideJobStatus,
    val basedOnContentRevision: Long,
    val reservedCredits: BigDecimal,
    val failureCode: ActionGuideJobFailureCode?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ActionGuideJobCollectionView(
    val activeJob: ActionGuideJobView?,
    val latestJob: ActionGuideJobView?,
    val requiredCredits: BigDecimal,
    val availableCredits: BigDecimal,
)

data class ActionGuideJobCreationView(
    val job: ActionGuideJobView,
    val availableCredits: BigDecimal,
)

class ActionGuideAttemptLimitExceededException(message: String) : EasyDocException(message)

class ActionGuideJobService(
    private val enabled: Boolean,
    private val jobs: ActionGuideJobRepository,
    private val credits: ActionGuideCreditPort,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Suppress("LongMethod", "ThrowsCount") // 멱등 확인부터 예약·삽입까지 한 트랜잭션의 순서를 보인다.
    fun create(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        expectedContentRevision: Long,
        expectedGuideRevision: Long?,
    ): ActionGuideJobCreationView {
        requireEnabled()
        requireRevision(expectedContentRevision, expectedGuideRevision)
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)

            jobs.findByRequestId(ownerId, conversionId, requestId)?.let { existing ->
                if (!existing.matches(expectedContentRevision, expectedGuideRevision)) {
                    throw ConflictException(REQUEST_ID_CONFLICT_MESSAGE)
                }
                return@inTransaction ActionGuideJobCreationView(
                    existing.toView(),
                    credits.available(ownerId, context.workspaceId),
                )
            }

            if (!context.completed) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            if (context.contentRevision != expectedContentRevision) {
                throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            if (context.guideRevision != expectedGuideRevision) {
                throw ConflictException(GUIDE_REVISION_CONFLICT_MESSAGE)
            }

            val required = Credits.requiredFor(context.charCount)
            val now = clock.instant()
            val candidate =
                StoredActionGuideJob(
                    jobId = UUID.randomUUID(),
                    ownerId = ownerId,
                    workspaceId = context.workspaceId,
                    documentId = context.documentId,
                    conversionId = conversionId,
                    requestId = requestId,
                    expectedGuideRevision = expectedGuideRevision,
                    basedOnContentRevision = expectedContentRevision,
                    reservedCredits = required.amount,
                    status = ActionGuideJobStatus.QUEUED,
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
                    is ActionGuideCreditReservation.Reserved -> {
                        reservation.available
                    }

                    is ActionGuideCreditReservation.Insufficient -> {
                        throw InsufficientCreditsException(
                            INSUFFICIENT_ACTION_GUIDE_CREDITS_MESSAGE,
                            reservation.available,
                            required.amount,
                        )
                    }
                }
            val inserted =
                when (val result = jobs.insert(candidate)) {
                    is ActionGuideJobInsert.Inserted -> {
                        result.job
                    }

                    ActionGuideJobInsert.ActiveConflict -> {
                        throw ConflictException(ACTIVE_JOB_CONFLICT_MESSAGE)
                    }

                    ActionGuideJobInsert.AttemptLimit -> {
                        throw ActionGuideAttemptLimitExceededException(ATTEMPT_LIMIT_MESSAGE)
                    }

                    ActionGuideJobInsert.RequestConflict -> {
                        throw ConflictException(REQUEST_ID_CONFLICT_MESSAGE)
                    }
                }
            ActionGuideJobCreationView(inserted.toView(), available)
        }
    }

    fun list(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideJobCollectionView {
        requireEnabled()
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val active = jobs.findActiveOwned(ownerId, conversionId)
            ActionGuideJobCollectionView(
                activeJob = active?.toView(),
                latestJob = (active ?: jobs.findLatestOwned(ownerId, conversionId))?.toView(),
                requiredCredits = Credits.requiredFor(context.charCount).amount,
                availableCredits = credits.available(ownerId, context.workspaceId),
            )
        }
    }

    fun get(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): ActionGuideJobView {
        requireEnabled()
        return transaction.inTransaction {
            jobs.lockOwnedContext(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            jobs.findOwned(ownerId, conversionId, jobId)?.toView()
                ?: throw NotFoundException(ACTION_GUIDE_JOB_NOT_FOUND_MESSAGE)
        }
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun requireRevision(
        contentRevision: Long,
        guideRevision: Long?,
    ) {
        if (contentRevision !in 1..MAX_SAFE_REVISION) throw InvalidInputException(CONTENT_REVISION_INVALID_MESSAGE)
        if (guideRevision != null && guideRevision !in 0..MAX_SAFE_REVISION) {
            throw InvalidInputException(GUIDE_REVISION_INVALID_MESSAGE)
        }
    }
}

private fun StoredActionGuideJob.matches(
    contentRevision: Long,
    guideRevision: Long?,
): Boolean = basedOnContentRevision == contentRevision && expectedGuideRevision == guideRevision

internal fun StoredActionGuideJob.toView(): ActionGuideJobView =
    ActionGuideJobView(
        jobId,
        requestId,
        status,
        basedOnContentRevision,
        reservedCredits,
        failureCode,
        createdAt,
        updatedAt,
    )

const val ACTION_GUIDE_JOB_NOT_FOUND_MESSAGE: String = "행동 안내문 생성 작업을 찾을 수 없습니다"
const val REQUEST_ID_CONFLICT_MESSAGE: String = "같은 요청 식별자가 다른 입력에 사용됐습니다"
const val CONTENT_REVISION_CONFLICT_MESSAGE: String = "본문 버전이 변경됐습니다"
const val GUIDE_REVISION_CONFLICT_MESSAGE: String = "행동 안내문 버전이 변경됐습니다"
const val GUIDE_REVISION_INVALID_MESSAGE: String = "행동 안내문 버전이 올바르지 않습니다"
const val ACTIVE_JOB_CONFLICT_MESSAGE: String = "이미 진행 중인 행동 안내문 생성 작업이 있습니다"
const val ATTEMPT_LIMIT_MESSAGE: String = "행동 안내문 생성 횟수를 모두 사용했습니다"
const val INSUFFICIENT_ACTION_GUIDE_CREDITS_MESSAGE: String = "행동 안내문 생성에 필요한 이용량이 부족합니다"

package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/** Reusing a valid snapshot never reserves credits or silently repeats a provider call. */
class GuideAnalysisIntakeService(
    private val enabled: Boolean,
    private val analyses: GuideAnalysisRepository,
    private val jobs: ActionGuideJobService,
    private val jobRepository: ActionGuideJobRepository,
    private val transaction: TransactionRunner,
) {
    // Replay, alias reuse and reservation must retain this lock order.
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    fun create(
        owner: UUID,
        conversion: UUID,
        request: UUID,
        expectedContentRevision: Long,
    ): ActionGuideJobCreationView =
        transaction.inTransaction {
            if (!enabled) throw NotFoundException("행동 분석 요청을 사용할 수 없습니다")
            val input = analyses.lockInput(owner, conversion) ?: throw NotFoundException("변환을 찾을 수 없습니다")
            // Job keys are shared with v1. Never hide a guide request behind a free-analysis alias.
            jobRepository.findByRequestId(owner, conversion, request)?.let {
                if (it.operation != ActionGuideOperation.ANALYSIS ||
                    it.basedOnContentRevision != expectedContentRevision
                ) {
                    throw ConflictException(REQUEST_ID_CONFLICT_MESSAGE)
                }
                return@inTransaction jobs.createAnalysis(owner, conversion, request, expectedContentRevision)
            }
            analyses.findRequest(owner, conversion, request)?.let { prior ->
                if (prior.basedOnContentRevision !=
                    expectedContentRevision
                ) {
                    throw ConflictException(REQUEST_ID_CONFLICT_MESSAGE)
                }
                prior.originJobId?.let { return@inTransaction reused(owner, conversion, it) }
                throw ConflictException("이전 검증용 요청 키입니다. 새 요청으로 다시 분석하세요")
            }
            if (!input.completed ||
                input.contentRevision != expectedContentRevision
            ) {
                throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            val latest = analyses.latest(owner, conversion)
            if (latest != null && latest.basedOnContentRevision == expectedContentRevision &&
                latest.readingLevel == input.readingLevel &&
                latest.provenance == "provider" && latest.analyzerVersion == "grounded-v1" && latest.originJobId != null
            ) {
                analyses.bindRequest(owner, conversion, request, latest.analysisId)
                return@inTransaction reused(owner, conversion, latest.originJobId)
            }
            jobs.createAnalysis(owner, conversion, request, expectedContentRevision)
        }

    private fun reused(
        owner: UUID,
        conversion: UUID,
        jobId: UUID,
    ): ActionGuideJobCreationView =
        ActionGuideJobCreationView(
            jobs.get(owner, conversion, jobId),
            jobs.list(owner, conversion, ActionGuideOperation.ANALYSIS).availableCredits,
        )
}

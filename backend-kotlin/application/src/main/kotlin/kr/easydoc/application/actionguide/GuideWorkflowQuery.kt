package kr.easydoc.application.actionguide

import java.util.UUID

/** Read composition only; commands retain their existing transaction boundaries. */
interface GuideWorkflowQuery {
    fun load(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideWorkflowView

    fun analysis(
        ownerId: UUID,
        conversionId: UUID,
        analysisId: UUID,
    ): GuideAnalysisView
}

data class GuideWorkflowView(
    val analysis: GuideAnalysisView?,
    val jobs: ActionGuideJobCollectionView,
    val drafts: List<GuideDraft>,
) {
    override fun toString(): String = "GuideWorkflowView()"
}

class DefaultGuideWorkflowQuery(
    private val analyses: ActionGuideAnalysisService,
    private val jobs: ActionGuideJobService,
    private val drafts: GuideDraftService,
) : GuideWorkflowQuery {
    override fun load(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideWorkflowView {
        val analysis = analyses.latest(ownerId, conversionId)
        val collection = jobs.list(ownerId, conversionId, ActionGuideOperation.ANALYSIS)
        return GuideWorkflowView(analysis, collection, drafts.list(ownerId, conversionId))
    }

    override fun analysis(
        ownerId: UUID,
        conversionId: UUID,
        analysisId: UUID,
    ): GuideAnalysisView = analyses.get(ownerId, conversionId, analysisId)
}

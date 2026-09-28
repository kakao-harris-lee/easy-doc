package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.actionguide.GuideAnalysisView
import kr.easydoc.application.actionguide.GuideDraft
import kr.easydoc.application.actionguide.GuideDraftApplyView
import kr.easydoc.application.actionguide.GuidePreviousBodyView
import kr.easydoc.application.actionguide.GuideWorkflowView
import java.math.BigDecimal
import java.util.UUID

data class GuideWorkflowResponse(
    @get:JsonProperty("intake_enabled") val intakeEnabled: Boolean,
    @get:JsonProperty("generation_enabled") val generationEnabled: Boolean,
    @get:JsonProperty("required_credits") val requiredCredits: BigDecimal,
    @get:JsonProperty("available_credits") val availableCredits: BigDecimal,
    @get:JsonProperty("generation_credits") val generationCredits: Int,
    val analysis: ActionGuideAnalysisResponse?,
    @get:JsonProperty("active_job") val activeJob: ActionGuideJobResponse?,
    @get:JsonProperty("latest_job") val latestJob: ActionGuideJobResponse?,
    val drafts: List<GuideDraftResponse>,
) {
    override fun toString(): String = "GuideWorkflowResponse()"

    companion object {
        fun of(
            view: GuideWorkflowView,
            enabled: Boolean,
        ): GuideWorkflowResponse =
            GuideWorkflowResponse(
                enabled,
                enabled,
                view.jobs.requiredCredits,
                view.jobs.availableCredits,
                0,
                view.analysis?.let(ActionGuideAnalysisResponse::of),
                view.jobs.activeJob?.let(ActionGuideJobResponse::of),
                view.jobs.latestJob?.let(ActionGuideJobResponse::of),
                view.drafts.map { GuideDraftResponse.of(it, view.analysis) },
            )
    }
}

data class GuideDraftBlockResponse(
    val id: String,
    @get:JsonProperty("action_id") val actionId: String,
    val text: String,
    val cautions: List<String>,
    val evidence: List<ActionGuideAnchorPayload>,
) {
    override fun toString(): String = "GuideDraftBlockResponse(id=$id)"
}

data class GuideDraftResponse(
    @get:JsonProperty("draft_id") val draftId: UUID,
    @get:JsonProperty("analysis_id") val analysisId: UUID,
    @get:JsonProperty("analysis_revision") val analysisRevision: Long,
    @get:JsonProperty("analysis_review_revision") val analysisReviewRevision: Long,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("draft_revision") val draftRevision: Long,
    val mode: String,
    val body: String,
    val blocks: List<GuideDraftBlockResponse>,
    val reviewed: Boolean,
    @get:JsonProperty("created_at") val createdAt: String,
    val state: String,
) {
    override fun toString(): String = "GuideDraftResponse(draftId=$draftId)"

    companion object {
        fun of(
            draft: GuideDraft,
            analysis: GuideAnalysisView?,
        ): GuideDraftResponse =
            GuideDraftResponse(
                draft.draftId,
                draft.analysisId,
                draft.analysisRevision,
                draft.analysisReviewRevision,
                draft.basedOnContentRevision,
                draft.draftRevision,
                draft.mode.name.lowercase(),
                draft.body,
                draft.blocks.map { block ->
                    GuideDraftBlockResponse(
                        block.id,
                        block.actionId,
                        block.text,
                        block.cautions,
                        block.evidence.map { ActionGuideAnchorPayload(it.sourceUnitIndexes, it.quote) },
                    )
                },
                draft.reviewed,
                draft.createdAt.toString(),
                state(draft, analysis),
            )

        @Suppress("ComplexCondition") // All independent analysis revision axes must still match.
        private fun state(draft: GuideDraft, analysis: GuideAnalysisView?): String =
            if (analysis?.state == "current" && analysis.snapshot.analysisId == draft.analysisId &&
                analysis.snapshot.analysisRevision == draft.analysisRevision &&
                analysis.snapshot.reviewRevision == draft.analysisReviewRevision
            ) {
                "current"
            } else {
                "stale"
            }
    }
}

data class GuideDraftApplyResponse(
    @get:JsonProperty("content_revision") val contentRevision: Long,
    @get:JsonProperty("previous_snapshot_id") val previousSnapshotId: UUID,
    val replayed: Boolean,
) {
    companion object {
        fun of(view: GuideDraftApplyView): GuideDraftApplyResponse =
            GuideDraftApplyResponse(view.contentRevision, view.previousSnapshotId, view.replayed)
    }
}

data class GuidePreviousBodyResponse(
    @get:JsonProperty("snapshot_id") val snapshotId: UUID,
    @get:JsonProperty("draft_id") val draftId: UUID,
    @get:JsonProperty("previous_content_revision") val previousContentRevision: Long,
    @get:JsonProperty("applied_content_revision") val appliedContentRevision: Long,
) {
    companion object {
        fun of(view: GuidePreviousBodyView): GuidePreviousBodyResponse =
            GuidePreviousBodyResponse(
                view.snapshotId,
                view.draftId,
                view.previousContentRevision,
                view.appliedContentRevision,
            )
    }
}

data class GuidePreviousBodyContentResponse(val body: String) {
    override fun toString(): String = "GuidePreviousBodyContentResponse()"
}

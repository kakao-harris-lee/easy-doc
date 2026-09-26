package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.actionguide.ActionGuideAnalysisService
import kr.easydoc.application.actionguide.ActionGuideJobService
import kr.easydoc.application.actionguide.ActionGuideOperation
import kr.easydoc.application.actionguide.GuideAnalysisIntakeService
import kr.easydoc.application.actionguide.GuideAnalysisReviewService
import kr.easydoc.application.actionguide.GuideAnalysisView
import kr.easydoc.application.actionguide.GuideDraft
import kr.easydoc.application.actionguide.GuideDraftApplyCommand
import kr.easydoc.application.actionguide.GuideDraftApplyService
import kr.easydoc.application.actionguide.GuideDraftService
import kr.easydoc.core.actionguide.GuideOutputMode
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
@Suppress("LongParameterList", "TooManyFunctions")
class GuideWorkflowController(
    private val analyses: ActionGuideAnalysisService,
    private val intake: GuideAnalysisIntakeService,
    private val reviews: GuideAnalysisReviewService,
    private val jobs: ActionGuideJobService,
    private val drafts: GuideDraftService,
    private val apply: GuideDraftApplyService,
    @param:Value("\${easydoc.action-guide.enabled:false}") private val guideEnabled: Boolean,
    @param:Value("\${easydoc.action-guide.analysis-enabled:false}") private val analysisEnabled: Boolean,
) {
    @GetMapping("$BASE/action-guide-workflow")
    fun workflow(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
    ): ResponseEntity<Map<String, Any?>> {
        val analysis = analyses.latest(user.id, conversion)
        val collection = jobs.list(user.id, conversion, ActionGuideOperation.ANALYSIS)
        return ok(
            mapOf(
                "intake_enabled" to (guideEnabled && analysisEnabled),
                "generation_enabled" to (guideEnabled && analysisEnabled),
                "required_credits" to collection.requiredCredits,
                "available_credits" to collection.availableCredits,
                "generation_credits" to 0,
                "analysis" to analysis?.let(ActionGuideAnalysisResponse::of),
                "active_job" to collection.activeJob?.let { ActionGuideJobResponse.of(it) },
                "latest_job" to collection.latestJob?.let { ActionGuideJobResponse.of(it) },
                "drafts" to drafts.list(user.id, conversion).map { draftPayload(it, analysis) },
            ),
        )
    }

    @PostMapping("$BASE/action-guide-analysis-jobs")
    fun analyze(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @Valid @RequestBody request: ActionGuideAnalysisRequest,
    ): ResponseEntity<ActionGuideJobResponse> {
        val created = intake.create(user.id, conversion, request.requestId, request.expectedContentRevision)
        return ResponseEntity
            .accepted()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(
                "X-Credit-Balance",
                created.availableCredits.toPlainString(),
            ).body(ActionGuideJobResponse.of(created.job))
    }

    @PutMapping("$BASE/action-guide-analyses/{analysis_id}/signals/{signal_id}")
    fun resolve(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("analysis_id") analysis: UUID,
        @PathVariable("signal_id") signal: String,
        @Valid @RequestBody request: GuideSignalRequest,
    ): ResponseEntity<ActionGuideAnalysisResponse> =
        ok(
            ActionGuideAnalysisResponse.of(
                reviews.resolve(
                    user.id,
                    conversion,
                    analysis,
                    request.revision(),
                    signal,
                    request.note,
                    request.bodyUnitIndexes,
                    request.bodyQuote,
                ),
            ),
        )

    @PutMapping("$BASE/action-guide-analyses/{analysis_id}")
    fun correct(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("analysis_id") analysis: UUID,
        @Valid @RequestBody request: GuideCorrectionRequest,
    ): ResponseEntity<ActionGuideAnalysisResponse> =
        ok(
            ActionGuideAnalysisResponse.of(
                reviews.correct(user.id, conversion, analysis, request.revision(), request.result()),
            ),
        )

    @PostMapping("$BASE/action-guide-analyses/{analysis_id}/review")
    fun review(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("analysis_id") analysis: UUID,
        @Valid @RequestBody request: GuideRevisionRequest,
    ): ResponseEntity<ActionGuideAnalysisResponse> =
        ok(ActionGuideAnalysisResponse.of(reviews.review(user.id, conversion, analysis, request.revision())))

    @PostMapping("$BASE/action-guide-drafts")
    fun createDraft(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @Valid @RequestBody request: GuideDraftRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val draft =
            drafts.create(
                user.id,
                conversion,
                request.requestId,
                request.analysisId,
                request.revision(),
                mode(request.mode),
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header(
                HttpHeaders.CACHE_CONTROL,
                "no-store",
            ).body(draftPayload(draft, analyses.get(user.id, conversion, draft.analysisId)))
    }

    @PostMapping("$BASE/action-guide-drafts/{draft_id}/review")
    fun reviewDraft(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("draft_id") draftId: UUID,
        @Valid @RequestBody request: GuideDraftReviewRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val draft =
            drafts.review(
                user.id,
                conversion,
                draftId,
                request.revision(),
                request.expectedDraftRevision,
                request.confirmedBlockIds,
            )
        return ok(draftPayload(draft, analyses.get(user.id, conversion, draft.analysisId)))
    }

    @PostMapping("$BASE/action-guide-drafts/{draft_id}/apply")
    fun applyDraft(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("draft_id") draftId: UUID,
        @Valid @RequestBody request: GuideDraftApplyRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val result =
            apply.apply(
                user.id,
                conversion,
                GuideDraftApplyCommand(
                    draftId,
                    request.requestId,
                    request.expectedContentRevision,
                    request.expectedAnalysisRevision,
                    request.expectedDraftRevision,
                    request.expectedReviewRevision,
                ),
            )
        return ok(
            mapOf(
                "content_revision" to result.contentRevision,
                "previous_snapshot_id" to result.previousSnapshotId,
                "replayed" to result.replayed,
            ),
        )
    }

    @GetMapping("$BASE/action-guide-drafts/{draft_id}/export")
    fun export(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("draft_id") draftId: UUID,
    ): ResponseEntity<ByteArray> {
        val draft = drafts.export(user.id, conversion, draftId)
        val filename =
            if (draft.mode ==
                GuideOutputMode.ADDITIONAL_GUIDE
            ) {
                "additional-action-guide.txt"
            } else {
                "full-document-supplement.txt"
            }
        return ResponseEntity
            .ok()
            .contentType(MediaType("text", "plain", Charsets.UTF_8))
            .header(
                HttpHeaders.CACHE_CONTROL,
                "no-store",
            ).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(draft.body.toByteArray(Charsets.UTF_8))
    }

    @GetMapping("$BASE/action-guide-previous-bodies")
    fun previous(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
    ): ResponseEntity<List<Map<String, Any>>> =
        ok(
            apply.listPreviousBodies(user.id, conversion).map {
                mapOf(
                    "snapshot_id" to it.snapshotId,
                    "draft_id" to it.draftId,
                    "previous_content_revision" to it.previousContentRevision,
                    "applied_content_revision" to it.appliedContentRevision,
                )
            },
        )

    @GetMapping("$BASE/action-guide-previous-bodies/{snapshot_id}")
    fun previousBody(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("snapshot_id") snapshot: UUID,
    ): ResponseEntity<Map<String, String>> =
        ok(mapOf("body" to apply.previousBody(user.id, conversion, snapshot).value))

    @Suppress("ComplexCondition") // A draft is current only across all three independent revision axes.
    private fun draftPayload(
        draft: GuideDraft,
        analysis: GuideAnalysisView?,
    ): Map<String, Any?> =
        mapOf(
            "draft_id" to draft.draftId,
            "analysis_id" to draft.analysisId,
            "analysis_revision" to draft.analysisRevision,
            "analysis_review_revision" to draft.analysisReviewRevision,
            "based_on_content_revision" to draft.basedOnContentRevision,
            "draft_revision" to draft.draftRevision,
            "mode" to draft.mode.name.lowercase(),
            "body" to draft.body,
            "blocks" to
                draft.blocks.map {
                    mapOf(
                        "id" to it.id,
                        "action_id" to it.actionId,
                        "text" to it.text,
                        "cautions" to it.cautions,
                        "evidence" to
                            it.evidence.map { anchor ->
                                ActionGuideAnchorPayload(anchor.sourceUnitIndexes, anchor.quote)
                            },
                    )
                },
            "reviewed" to draft.reviewed,
            "created_at" to draft.createdAt.toString(),
            "state" to
                if (analysis?.state == "current" && analysis.snapshot.analysisId == draft.analysisId &&
                    analysis.snapshot.analysisRevision == draft.analysisRevision &&
                    analysis.snapshot.reviewRevision == draft.analysisReviewRevision
                ) {
                    "current"
                } else {
                    "stale"
                },
        )

    private fun <T : Any> ok(value: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(value)

    private fun mode(value: String): GuideOutputMode =
        GuideOutputMode.entries.find { it.name.lowercase() == value }
            ?: throw InvalidInputException("지원하지 않는 보완 방식입니다")

    private companion object {
        const val BASE = "/conversions/{conversion_id}"
    }
}

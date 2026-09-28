package kr.easydoc.api.document

import jakarta.validation.Valid
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.api.config.privateResponse
import kr.easydoc.application.actionguide.GuideAnalysisIntakeService
import kr.easydoc.application.actionguide.GuideAnalysisReviewService
import kr.easydoc.application.actionguide.GuideDraftApplyCommand
import kr.easydoc.application.actionguide.GuideDraftApplyService
import kr.easydoc.application.actionguide.GuideDraftService
import kr.easydoc.application.actionguide.GuideWorkflowQuery
import kr.easydoc.core.actionguide.GuideOutputMode
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
    private val query: GuideWorkflowQuery,
    private val intake: GuideAnalysisIntakeService,
    private val reviews: GuideAnalysisReviewService,
    private val drafts: GuideDraftService,
    private val apply: GuideDraftApplyService,
    @param:Value("\${easydoc.action-guide.enabled:false}") private val guideEnabled: Boolean,
    @param:Value("\${easydoc.action-guide.analysis-enabled:false}") private val analysisEnabled: Boolean,
) {
    @GetMapping("$BASE/action-guide-workflow")
    fun workflow(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
    ): ResponseEntity<GuideWorkflowResponse> =
        ok(GuideWorkflowResponse.of(query.load(user.id, conversion), guideEnabled && analysisEnabled))

    @PostMapping("$BASE/action-guide-analysis-jobs")
    fun analyze(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @Valid @RequestBody request: ActionGuideAnalysisRequest,
    ): ResponseEntity<ActionGuideJobResponse> {
        val created = intake.create(user.id, conversion, request.requestId, request.expectedContentRevision)
        return ResponseEntity
            .accepted()
            .privateResponse()
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
    ): ResponseEntity<GuideDraftResponse> {
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
            .privateResponse()
            .body(GuideDraftResponse.of(draft, query.analysis(user.id, conversion, draft.analysisId)))
    }

    @PostMapping("$BASE/action-guide-drafts/{draft_id}/review")
    fun reviewDraft(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("draft_id") draftId: UUID,
        @Valid @RequestBody request: GuideDraftReviewRequest,
    ): ResponseEntity<GuideDraftResponse> {
        val draft =
            drafts.review(
                user.id,
                conversion,
                draftId,
                request.revision(),
                request.expectedDraftRevision,
                request.confirmedBlockIds,
            )
        return ok(GuideDraftResponse.of(draft, query.analysis(user.id, conversion, draft.analysisId)))
    }

    @PostMapping("$BASE/action-guide-drafts/{draft_id}/apply")
    fun applyDraft(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("draft_id") draftId: UUID,
        @Valid @RequestBody request: GuideDraftApplyRequest,
    ): ResponseEntity<GuideDraftApplyResponse> {
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
        return ok(GuideDraftApplyResponse.of(result))
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
            .privateResponse()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(draft.body.toByteArray(Charsets.UTF_8))
    }

    @GetMapping("$BASE/action-guide-previous-bodies")
    fun previous(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
    ): ResponseEntity<List<GuidePreviousBodyResponse>> =
        ok(apply.listPreviousBodies(user.id, conversion).map(GuidePreviousBodyResponse::of))

    @GetMapping("$BASE/action-guide-previous-bodies/{snapshot_id}")
    fun previousBody(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversion: UUID,
        @PathVariable("snapshot_id") snapshot: UUID,
    ): ResponseEntity<GuidePreviousBodyContentResponse> =
        ok(GuidePreviousBodyContentResponse(apply.previousBody(user.id, conversion, snapshot).value))

    private fun <T : Any> ok(value: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .privateResponse()
            .body(value)

    private fun mode(value: String): GuideOutputMode =
        GuideOutputMode.entries.find { it.name.lowercase() == value }
            ?: throw InvalidInputException("지원하지 않는 보완 방식입니다")

    private companion object {
        const val BASE = "/conversions/{conversion_id}"
    }
}

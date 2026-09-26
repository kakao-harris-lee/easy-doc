package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.actionguide.ActionGuideAnalysisService
import kr.easydoc.application.actionguide.GuideAnalysisView
import kr.easydoc.core.actionguide.ExtractedGuideAction
import kr.easydoc.core.actionguide.GuideInformation
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
class ActionGuideAnalysisController(private val service: ActionGuideAnalysisService) {
    @PostMapping(PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversionId: UUID,
        @Valid @RequestBody request: ActionGuideAnalysisRequest,
    ): ResponseEntity<ActionGuideAnalysisResponse> =
        response(
            ActionGuideAnalysisResponse.of(
                service.create(
                    user.id,
                    conversionId,
                    request.requestId,
                    request.expectedContentRevision,
                ),
            ),
        )

    @GetMapping(PATH)
    fun latest(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversionId: UUID,
    ): ResponseEntity<ActionGuideAnalysisLatestResponse> =
        response(
            ActionGuideAnalysisLatestResponse(
                service.latest(user.id, conversionId)?.let(ActionGuideAnalysisResponse::of),
            ),
        )

    @GetMapping("$PATH/{analysis_id}")
    fun get(
        user: AuthenticatedUser,
        @PathVariable("conversion_id") conversionId: UUID,
        @PathVariable("analysis_id") analysisId: UUID,
    ): ResponseEntity<ActionGuideAnalysisResponse> =
        response(ActionGuideAnalysisResponse.of(service.get(user.id, conversionId, analysisId)))

    private fun <T : Any> response(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(body)

    private companion object {
        const val PATH = "/conversions/{conversion_id}/action-guide-analyses"
    }
}

data class ActionGuideAnalysisRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty("expected_content_revision", required = true)
        @field:Min(1)
        @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
    )

data class ActionGuideAnalysisLatestResponse(val analysis: ActionGuideAnalysisResponse?)

data class GuideInformationPayload(
    val status: String,
    val text: String?,
    val evidence: List<ActionGuideAnchorPayload>,
) {
    override fun toString(): String = "GuideInformationPayload(status=$status)"

    companion object {
        fun of(info: GuideInformation): GuideInformationPayload =
            GuideInformationPayload(
                info.status.name.lowercase(),
                info.text,
                info.evidence.map { ActionGuideAnchorPayload(it.sourceUnitIndexes, it.quote) },
            )
    }
}

data class ExtractedGuideActionPayload(
    val id: String,
    val instruction: GuideInformationPayload,
    val actor: GuideInformationPayload,
    val beneficiaries: GuideInformationPayload,
    val conditions: List<GuideInformationPayload>,
    val deadline: GuideInformationPayload,
    val preparation: GuideInformationPayload,
    val contact: GuideInformationPayload,
    @get:JsonProperty("after_action_ids") val afterActionIds: List<String>,
    @get:JsonProperty("order_evidence") val orderEvidence: List<ActionGuideAnchorPayload>,
) {
    override fun toString(): String = "ExtractedGuideActionPayload()"

    companion object {
        fun of(action: ExtractedGuideAction): ExtractedGuideActionPayload =
            ExtractedGuideActionPayload(
                action.id,
                GuideInformationPayload.of(action.instruction),
                GuideInformationPayload.of(action.actor),
                GuideInformationPayload.of(action.beneficiaries),
                action.conditions.map(GuideInformationPayload::of),
                GuideInformationPayload.of(action.deadline),
                GuideInformationPayload.of(action.preparation),
                GuideInformationPayload.of(action.contact),
                action.afterActionIds,
                action.orderEvidence.map { ActionGuideAnchorPayload(it.sourceUnitIndexes, it.quote) },
            )
    }
}

data class GuideUnitAssessmentPayload(
    @get:JsonProperty("source_unit_id") val sourceUnitId: Int,
    val status: String,
    @get:JsonProperty("action_ids") val actionIds: List<String>,
)

data class ActionGuideAnalysisResponse(
    @get:JsonProperty("schema_version") val schemaVersion: Int,
    @get:JsonProperty("analysis_id") val analysisId: UUID,
    @get:JsonProperty("analysis_revision") val analysisRevision: Long,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    val state: String,
    val provenance: String,
    @get:JsonProperty("reading_level") val readingLevel: String,
    val suitability: String,
    @get:JsonProperty("action_presence") val actionPresence: String,
    val reason: String,
    val evidence: List<ActionGuideAnchorPayload>,
    @get:JsonProperty("source_units") val sourceUnits: List<kr.easydoc.core.actionguide.GuideSourceUnit>,
    val actions: List<ExtractedGuideActionPayload>,
    val coverage: List<GuideUnitAssessmentPayload>,
    @get:JsonProperty("unresolved_signals") val unresolvedSignals: List<String>,
    @get:JsonProperty("extraction_review_complete") val extractionReviewComplete: Boolean,
    @get:JsonProperty("allowed_modes") val allowedModes: List<String>,
    @get:JsonProperty("generation_enabled") val generationEnabled: Boolean,
    @get:JsonProperty("created_at") val createdAt: String,
) {
    override fun toString(): String = "ActionGuideAnalysisResponse(id=$analysisId)"

    companion object {
        private const val SCHEMA_VERSION = 2

        fun of(view: GuideAnalysisView): ActionGuideAnalysisResponse {
            val snapshot = view.snapshot
            val result = snapshot.result
            return ActionGuideAnalysisResponse(
                SCHEMA_VERSION,
                snapshot.analysisId,
                snapshot.analysisRevision,
                snapshot.basedOnContentRevision,
                view.state,
                "fake",
                snapshot.readingLevel,
                result.suitability.name.lowercase(),
                result.actionPresence.name.lowercase(),
                result.reason,
                result.evidence.map { ActionGuideAnchorPayload(it.sourceUnitIndexes, it.quote) },
                snapshot.sourceUnits,
                result.actions.map(ExtractedGuideActionPayload::of),
                result.coverage.map {
                    GuideUnitAssessmentPayload(
                        it.sourceUnitId,
                        it.status.name.lowercase(),
                        it.actionIds,
                    )
                },
                result.unresolvedSignals,
                result.extractionReviewComplete,
                view.allowedModes.map { it.name.lowercase() },
                false,
                snapshot.createdAt.toString(),
            )
        }
    }
}

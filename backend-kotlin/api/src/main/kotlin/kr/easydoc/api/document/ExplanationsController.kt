package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ExplanationsService
import kr.easydoc.application.document.ExplanationsView
import kr.easydoc.core.dictionary.Explanation
import kr.easydoc.core.privacy.UserContent
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
class ExplanationsController(private val service: ExplanationsService) {
    @GetMapping(EXPLANATIONS_PATH)
    fun read(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<ExplanationsResponse> = json(ExplanationsResponse.of(service.read(user.id, conversionId)))

    private fun <T : Any> json(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(body)

    private companion object {
        const val EXPLANATIONS_PATH = "/conversions/{conversion_id}/explanations"
        const val CONVERSION_ID = "conversion_id"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

data class ExplanationsResponse(
    @get:JsonProperty("conversion_id") val conversionId: UUID,
    @get:JsonProperty("current_content_revision") val currentContentRevision: Long,
    @get:JsonProperty("explanations") val explanations: List<ExplanationResponse>,
) {
    override fun toString(): String =
        "ExplanationsResponse(conversionId=$conversionId, currentContentRevision=$currentContentRevision, " +
            "explanations=${explanations.size})"

    companion object {
        fun of(view: ExplanationsView): ExplanationsResponse =
            ExplanationsResponse(
                view.conversionId,
                view.currentContentRevision,
                view.explanations.map(ExplanationResponse::of),
            )
    }
}

@UserContent
data class ExplanationResponse(
    @get:JsonProperty("term") val term: String,
    @get:JsonProperty("definition_source") val definitionSource: String,
    @get:JsonProperty("explanation") val explanation: String,
    @get:JsonProperty("source_anchors") val sourceAnchors: List<SourceAnchorResponse>,
) {
    override fun toString(): String =
        "ExplanationResponse(term=${term.length}자, definitionSource=${definitionSource.length}자, " +
            "explanation=${explanation.length}자, sourceAnchors=${sourceAnchors.size})"

    companion object {
        fun of(explanation: Explanation): ExplanationResponse =
            ExplanationResponse(
                explanation.term,
                explanation.definitionSource.wire,
                explanation.explanation,
                explanation.sourceAnchors.map(SourceAnchorResponse::of),
            )
    }
}

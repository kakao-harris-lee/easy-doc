package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.actionguide.ActionGuideContentService
import kr.easydoc.application.actionguide.ActionGuideResourceView
import kr.easydoc.application.actionguide.ActionGuideView
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.actionguide.ActionGuideItem
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.privacy.CONTENT_MASK
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
class ActionGuideContentController(private val service: ActionGuideContentService) {
    @GetMapping(ACTION_GUIDE_PATH)
    fun get(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<ActionGuideResourceResponse> =
        json(ActionGuideResourceResponse.of(service.get(user.id, conversionId)))

    @PutMapping(ACTION_GUIDE_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun save(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: ActionGuideSaveRequest,
    ): ResponseEntity<ActionGuideResourceResponse> {
        val guide =
            service.save(
                ownerId = user.id,
                conversionId = conversionId,
                candidateId = request.candidateId,
                expectedContentRevision = request.expectedContentRevision,
                expectedGuideRevision = request.expectedGuideRevision,
                content = ActionGuideCandidateParser.decode(request.content.toString()),
                markReviewed = request.markReviewed,
            )
        return json(ActionGuideResourceResponse(guide.status.wireName, ActionGuideResponse.of(guide), null, null))
    }

    @GetMapping(ACTION_GUIDE_EXPORT_PATH)
    fun export(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @RequestParam("guide_revision") guideRevision: Long,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType("text", "plain", Charsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=action-guide.txt")
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(service.export(user.id, conversionId, guideRevision))

    private fun <T : Any> json(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(body)

    private companion object {
        const val ACTION_GUIDE_PATH = "/conversions/{conversion_id}/action-guide"
        const val ACTION_GUIDE_EXPORT_PATH = "$ACTION_GUIDE_PATH/export"
        const val CONVERSION_ID = "conversion_id"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

data class ActionGuideSaveRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("candidate_id", required = true) val candidateId: UUID?,
        @param:JsonProperty("expected_content_revision", required = true)
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
        @param:JsonProperty("expected_guide_revision", required = true)
        @field:Min(0)
        @field:Max(9_007_199_254_740_991)
        val expectedGuideRevision: Long?,
        @param:JsonProperty("content", required = true) val content: JsonNode,
        @param:JsonProperty("mark_reviewed", required = true) val markReviewed: Boolean,
    ) {
        override fun toString(): String =
            "ActionGuideSaveRequest(candidateId=$candidateId, contentRevision=$expectedContentRevision, " +
                "guideRevision=$expectedGuideRevision, content=$CONTENT_MASK, markReviewed=$markReviewed)"
    }

data class ActionGuideContentPayload
    @JsonCreator
    constructor(
        @param:JsonProperty("schema_version", required = true) val schemaVersion: Int,
        @param:JsonProperty("sections", required = true) @field:Valid val sections: List<ActionGuideSectionPayload>,
    ) {
        fun toDomain(): ActionGuideCandidate = ActionGuideCandidate(schemaVersion, sections.map { it.toDomain() })

        override fun toString(): String =
            "ActionGuideContentPayload(schemaVersion=$schemaVersion, sectionCount=${sections.size})"

        companion object {
            fun of(candidate: ActionGuideCandidate): ActionGuideContentPayload =
                ActionGuideContentPayload(
                    candidate.schemaVersion,
                    candidate.sections.map(ActionGuideSectionPayload::of),
                )
        }
    }

data class ActionGuideSectionPayload
    @JsonCreator
    constructor(
        @param:JsonProperty("kind", required = true) val kind: String,
        @param:JsonProperty("status", required = true) val status: String,
        @param:JsonProperty("items", required = true) @field:Valid val items: List<ActionGuideItemPayload>,
    ) {
        fun toDomain(): ActionGuideSection =
            ActionGuideSection(
                ActionGuideSectionKind.ofWireName(kind),
                ActionGuideSectionStatus.ofWireName(status),
                items.map(ActionGuideItemPayload::toDomain),
            )

        override fun toString(): String =
            "ActionGuideSectionPayload(kind=$kind, status=$status, itemCount=${items.size})"

        companion object {
            fun of(section: ActionGuideSection): ActionGuideSectionPayload =
                ActionGuideSectionPayload(
                    section.kind.wireName,
                    section.status.wireName,
                    section.items.map(ActionGuideItemPayload::of),
                )
        }
    }

data class ActionGuideItemPayload
    @JsonCreator
    constructor(
        @param:JsonProperty("text", required = true) val text: String,
        @param:JsonProperty("cautions", required = true) val cautions: List<String>,
        @param:JsonProperty("source_anchors", required = true) @field:Valid val sourceAnchors:
            List<ActionGuideAnchorPayload>,
    ) {
        fun toDomain(): ActionGuideItem =
            ActionGuideItem(text, cautions, sourceAnchors.map(ActionGuideAnchorPayload::toDomain))

        override fun toString(): String =
            "ActionGuideItemPayload(text=$CONTENT_MASK ${text.length}자, " +
                "cautions=${cautions.size}, anchors=${sourceAnchors.size})"

        companion object {
            fun of(item: ActionGuideItem): ActionGuideItemPayload =
                ActionGuideItemPayload(item.text, item.cautions, item.sourceAnchors.map(ActionGuideAnchorPayload::of))
        }
    }

data class ActionGuideAnchorPayload
    @JsonCreator
    constructor(
        @param:JsonProperty("source_unit_indexes", required = true) val sourceUnitIndexes: List<Int>,
        @param:JsonProperty("quote", required = true) val quote: String,
    ) {
        fun toDomain(): ActionGuideSourceAnchor = ActionGuideSourceAnchor(sourceUnitIndexes, quote)

        override fun toString(): String =
            "ActionGuideAnchorPayload(indexCount=${sourceUnitIndexes.size}, quote=$CONTENT_MASK ${quote.length}자)"

        companion object {
            fun of(anchor: ActionGuideSourceAnchor): ActionGuideAnchorPayload =
                ActionGuideAnchorPayload(anchor.sourceUnitIndexes, anchor.quote)
        }
    }

data class ActionGuideResourceResponse(
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("guide") val guide: ActionGuideResponse?,
    @get:JsonProperty("active_job_id") val activeJobId: UUID?,
    @get:JsonProperty("latest_job_id") val latestJobId: UUID?,
) {
    companion object {
        fun of(view: ActionGuideResourceView): ActionGuideResourceResponse =
            ActionGuideResourceResponse(
                view.status.wireName,
                view.guide?.let(ActionGuideResponse::of),
                view.activeJobId,
                view.latestJobId,
            )
    }
}

data class ActionGuideResponse(
    @get:JsonProperty("guide_id") val guideId: UUID,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("guide_revision") val guideRevision: Long,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("content") val content: ActionGuideContentPayload,
    @get:JsonProperty("reviewed_at") val reviewedAt: String?,
    @get:JsonProperty("reviewed_by") val reviewedBy: UUID?,
) {
    override fun toString(): String =
        "ActionGuideResponse(guideId=$guideId, guideRevision=$guideRevision, status=$status, content=$CONTENT_MASK)"

    companion object {
        fun of(view: ActionGuideView): ActionGuideResponse =
            ActionGuideResponse(
                view.guideId,
                view.basedOnContentRevision,
                view.guideRevision,
                view.status.wireName,
                ActionGuideContentPayload.of(view.content),
                view.reviewedAt?.toString(),
                view.reviewedBy,
            )
    }
}

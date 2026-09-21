package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ReviewHistoryEventType
import kr.easydoc.application.document.ReviewHistoryEventView
import kr.easydoc.application.document.ReviewHistoryPageView
import kr.easydoc.application.document.ReviewHistoryService
import kr.easydoc.application.document.ReviewHistorySnapshotKind
import kr.easydoc.application.document.ReviewHistorySnapshotView
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
class ReviewHistoryController(private val service: ReviewHistoryService) {
    @GetMapping(REVIEW_HISTORY_PATH)
    fun read(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", defaultValue = "20") limit: Int,
    ): ResponseEntity<ReviewHistoryResponse> =
        json(service.page(user.id, conversionId, cursor, limit).let(ReviewHistoryResponse::of))

    @GetMapping(REVIEW_HISTORY_EXPORT_PATH)
    fun export(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType("text", "plain", Charsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=review-history.txt")
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(service.export(user.id, conversionId))

    private fun <T : Any> json(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(body)

    private companion object {
        const val REVIEW_HISTORY_PATH = "/conversions/{conversion_id}/review-history"
        const val REVIEW_HISTORY_EXPORT_PATH = "$REVIEW_HISTORY_PATH/export"
        const val CONVERSION_ID = "conversion_id"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

data class ReviewHistoryResponse(
    @get:JsonProperty("conversion_id") val conversionId: UUID,
    @get:JsonProperty("current_content_revision") val currentContentRevision: Long,
    @get:JsonProperty("events") val events: List<ReviewHistoryEventResponse>,
    @get:JsonProperty("next_cursor") @get:JsonInclude(JsonInclude.Include.ALWAYS) val nextCursor: String?,
) {
    override fun toString(): String =
        "ReviewHistoryResponse(conversionId=$conversionId, revision=$currentContentRevision, " +
            "events=${events.size}, nextCursor=${nextCursor != null})"

    companion object {
        fun of(view: ReviewHistoryPageView): ReviewHistoryResponse =
            ReviewHistoryResponse(
                conversionId = view.conversionId,
                currentContentRevision = view.currentContentRevision,
                events = view.events.map(ReviewHistoryEventResponse::of),
                nextCursor = view.nextCursor,
            )
    }
}

data class ReviewHistoryEventResponse(
    @get:JsonProperty("event_id") val eventId: UUID,
    @get:JsonProperty("event_type") val eventType: String,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("actor_user_id") val actorUserId: UUID,
    @get:JsonProperty("content_revision") val contentRevision: Long,
    @get:JsonProperty("artifact_revision") @get:JsonInclude(JsonInclude.Include.ALWAYS) val artifactRevision: Long?,
    @get:JsonProperty("item_id") @get:JsonInclude(JsonInclude.Include.ALWAYS) val itemId: UUID?,
    @get:JsonProperty("assessment_id") @get:JsonInclude(JsonInclude.Include.ALWAYS) val assessmentId: UUID?,
    @get:JsonProperty("guide_id") @get:JsonInclude(JsonInclude.Include.ALWAYS) val guideId: UUID?,
    @get:JsonProperty("snapshot") val snapshot: ReviewHistorySnapshotResponse,
) {
    override fun toString(): String =
        "ReviewHistoryEventResponse(eventId=$eventId, type=$eventType, revision=$contentRevision, " +
            "snapshot=$snapshot)"

    companion object {
        fun of(view: ReviewHistoryEventView): ReviewHistoryEventResponse =
            ReviewHistoryEventResponse(
                eventId = view.eventId,
                eventType = view.eventType.wireName,
                createdAt = view.createdAt.toString(),
                actorUserId = view.actorUserId,
                contentRevision = view.contentRevision,
                artifactRevision = view.artifactRevision,
                itemId = view.itemId,
                assessmentId = view.assessmentId,
                guideId = view.guideId,
                snapshot = ReviewHistorySnapshotResponse.of(view.snapshot),
            )
    }
}

data class ReviewHistorySnapshotResponse(
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("kind") @get:JsonInclude(JsonInclude.Include.ALWAYS) val kind: String?,
    @get:JsonProperty("content_text") @get:JsonInclude(JsonInclude.Include.ALWAYS) val contentText: String?,
    @get:JsonProperty("artifact_json") @get:JsonInclude(JsonInclude.Include.ALWAYS) val artifactJson: String?,
) {
    override fun toString(): String =
        "ReviewHistorySnapshotResponse(status=$status, kind=$kind, contentText=${contentText?.length ?: 0}자, " +
            "artifactJson=${artifactJson?.length ?: 0}자)"

    companion object {
        fun of(view: ReviewHistorySnapshotView): ReviewHistorySnapshotResponse =
            ReviewHistorySnapshotResponse(
                status = view.status,
                kind = view.kind?.wireName,
                contentText = view.contentText,
                artifactJson = view.artifactJson,
            )
    }
}

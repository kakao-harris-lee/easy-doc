package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.ReviewAssessmentView
import kr.easydoc.application.document.ReviewSupportService
import kr.easydoc.application.document.ReviewSupportView
import kr.easydoc.core.easyread.ReviewItem
import kr.easydoc.core.easyread.ReviewItemState
import kr.easydoc.core.easyread.SourceAnchor
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.privacy.CONTENT_MASK
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
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
class ReviewSupportController(private val service: ReviewSupportService) {
    @GetMapping(REVIEW_SUPPORT_PATH)
    fun get(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<ReviewSupportResponse> = response(service.get(user.id, conversionId))

    @PostMapping(REVIEW_SUPPORT_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun analyze(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: ReviewSupportAnalyzeRequest,
    ): ResponseEntity<ReviewSupportResponse> =
        response(service.analyze(user.id, conversionId, request.expectedContentRevision))

    @PutMapping(REVIEW_SUPPORT_ITEM_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateItem(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(ITEM_ID) itemId: UUID,
        @Valid @RequestBody request: ReviewSupportItemUpdateRequest,
    ): ResponseEntity<ReviewSupportResponse> {
        val state =
            ReviewItemState.entries.singleOrNull { it.wireName == request.state }
                ?: throw InvalidInputException("검수 상태가 올바르지 않습니다")
        return response(
            service.updateItem(
                ownerId = user.id,
                conversionId = conversionId,
                itemId = itemId,
                assessmentId = request.assessmentId,
                expectedContentRevision = request.expectedContentRevision,
                expectedReviewRevision = request.expectedReviewRevision,
                state = state,
                reason = request.reason,
            ),
        )
    }

    private fun response(view: ReviewSupportView): ResponseEntity<ReviewSupportResponse> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(ReviewSupportResponse.of(view))

    private companion object {
        const val REVIEW_SUPPORT_PATH = "/conversions/{conversion_id}/review-support"
        const val REVIEW_SUPPORT_ITEM_PATH = "$REVIEW_SUPPORT_PATH/items/{item_id}"
        const val CONVERSION_ID = "conversion_id"
        const val ITEM_ID = "item_id"
    }
}

data class ReviewSupportAnalyzeRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("expected_content_revision")
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
    )

data class ReviewSupportItemUpdateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("assessment_id") val assessmentId: UUID,
        @param:JsonProperty("expected_content_revision")
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
        @param:JsonProperty("expected_review_revision")
        @field:Min(0)
        @field:Max(9_007_199_254_740_991)
        val expectedReviewRevision: Long,
        @param:JsonProperty("state") val state: String,
        @param:JsonProperty("reason")
        @param:JsonSetter(nulls = Nulls.SET)
        val reason: String? = null,
    ) {
        override fun toString(): String =
            "ReviewSupportItemUpdateRequest(assessmentId=$assessmentId, contentRevision=$expectedContentRevision, " +
                "reviewRevision=$expectedReviewRevision, state=$state, reason=$CONTENT_MASK ${reason?.length ?: 0}자)"
    }

data class ReviewSupportResponse(
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("assessment") val assessment: ReviewAssessmentResponse?,
) {
    companion object {
        fun of(view: ReviewSupportView): ReviewSupportResponse =
            ReviewSupportResponse(view.status.wireName, view.assessment?.let(ReviewAssessmentResponse::of))
    }
}

data class ReviewAssessmentResponse(
    @get:JsonProperty("assessment_id") val assessmentId: UUID,
    @get:JsonProperty("content_revision") val contentRevision: Long,
    @get:JsonProperty("analyzer_version") val analyzerVersion: String,
    @get:JsonProperty("review_revision") val reviewRevision: Long,
    @get:JsonProperty("coverage") val coverage: String,
    @get:JsonProperty("limitations") val limitations: List<String>,
    @get:JsonProperty("items") val items: List<ReviewItemResponse>,
) {
    companion object {
        fun of(view: ReviewAssessmentView): ReviewAssessmentResponse =
            ReviewAssessmentResponse(
                view.assessmentId,
                view.contentRevision,
                view.analyzerVersion,
                view.reviewRevision,
                view.coverage.wireName,
                view.limitedReasons.map { it.wireName },
                view.items.map(ReviewItemResponse::of),
            )
    }
}

data class ReviewItemResponse(
    @get:JsonProperty("item_id") val itemId: UUID,
    @get:JsonProperty("kind") val kind: String,
    @get:JsonProperty("rule_code") val ruleCode: String,
    @get:JsonProperty("source_anchors") val sourceAnchors: List<SourceAnchorResponse>,
    @get:JsonProperty("easy_unit_indexes") val easyUnitIndexes: List<Int>,
    @get:JsonProperty("state") val state: String,
    @get:JsonProperty("reason") val reason: String?,
    @get:JsonProperty("confirmed_by") val confirmedBy: UUID?,
    @get:JsonProperty("confirmed_at") val confirmedAt: String?,
) {
    override fun toString(): String =
        "ReviewItemResponse(itemId=$itemId, kind=$kind, ruleCode=$ruleCode, state=$state, " +
            "reason=$CONTENT_MASK ${reason?.length ?: 0}자)"

    companion object {
        fun of(item: ReviewItem): ReviewItemResponse =
            ReviewItemResponse(
                item.itemId,
                item.kind.wireName,
                item.ruleCode,
                item.sourceAnchors.map(SourceAnchorResponse::of),
                item.easyUnitIndexes,
                item.state.wireName,
                item.reason,
                item.confirmedBy,
                item.confirmedAt?.toString(),
            )
    }
}

data class SourceAnchorResponse(
    @get:JsonProperty("source_unit_indexes") val sourceUnitIndexes: List<Int>,
    @get:JsonProperty("quote") val quote: String,
) {
    override fun toString(): String =
        "SourceAnchorResponse(indexes=$sourceUnitIndexes, quote=$CONTENT_MASK ${quote.length}자)"

    companion object {
        fun of(anchor: SourceAnchor): SourceAnchorResponse =
            SourceAnchorResponse(anchor.sourceUnitIndexes, anchor.quote)
    }
}

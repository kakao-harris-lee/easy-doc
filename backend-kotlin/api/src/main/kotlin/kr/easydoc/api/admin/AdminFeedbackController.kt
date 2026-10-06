package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import kr.easydoc.application.admin.AdminFeedbackItem
import kr.easydoc.application.admin.AdminFeedbackQuery
import kr.easydoc.core.privacy.CONTENT_MASK
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** AdminAccessInterceptor가 매 요청 관리자 권한과 이메일 검증을 확인한다. */
@RestController
@Profile("!migrate")
@RequestMapping("/admin/feedback")
class AdminFeedbackController(private val query: AdminFeedbackQuery) {
    @Suppress("LongParameterList", "ComplexCondition") // Independent optional HTTP filters.
    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "1") @Min(1) @Max(100000) page: Int,
        @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(name = "publish_intent", required = false)
        @Pattern(regexp = "as_is|with_edits|not_usable") publishIntent: String? = null,
        @RequestParam(name = "max_quality_score", required = false) @Min(1) @Max(5) maxQualityScore: Int? = null,
        @RequestParam(required = false) from: Instant? = null,
        @RequestParam(required = false) to: Instant? = null,
    ): ResponseEntity<AdminFeedbackListResponse> {
        if (from != null && to != null &&
            from >= to
        ) {
            throw kr.easydoc.core.exceptions
                .InvalidInputException("조회 기간을 확인하세요")
        }
        val result =
            if (publishIntent == null && maxQualityScore == null && from == null &&
                to == null
            ) {
                query.list(page, size)
            } else {
                query.filteredList(
                    page,
                    size,
                    kr.easydoc.application.admin
                        .AdminFeedbackFilters(publishIntent, maxQualityScore, from, to),
                )
            }
        return adminResponse(HttpStatus.OK).body(
            AdminFeedbackListResponse(result.items.map(AdminFeedbackResponse::of), page, size, result.total),
        )
    }
}

data class AdminFeedbackListResponse(
    @get:JsonProperty("items") val items: List<AdminFeedbackResponse>,
    @get:JsonProperty("page") val page: Int,
    @get:JsonProperty("size") val size: Int,
    @get:JsonProperty("total") val total: Long,
)

data class AdminFeedbackResponse(
    @get:JsonProperty("conversion_id") val conversionId: UUID,
    @get:JsonProperty("user_id") val userId: UUID?,
    @get:JsonProperty("owner_email") val ownerEmail: String?,
    @get:JsonProperty("publish_intent") val publishIntent: String,
    @get:JsonProperty("quality_score") val qualityScore: Int,
    @get:JsonProperty("minutes_spent") val minutesSpent: Int,
    @get:JsonProperty("comment") val comment: String?,
    @get:JsonProperty("comment_unreadable") val commentUnreadable: Boolean,
    @get:JsonProperty("submitted_at") val submittedAt: Instant,
) {
    override fun toString(): String =
        "AdminFeedbackResponse(conversionId=$conversionId, ownerEmail=$CONTENT_MASK, comment=$CONTENT_MASK)"

    companion object {
        fun of(item: AdminFeedbackItem): AdminFeedbackResponse =
            AdminFeedbackResponse(
                item.conversionId,
                item.userId,
                item.ownerEmail,
                item.publishIntent,
                item.qualityScore,
                item.minutesSpent,
                item.comment?.value,
                item.commentUnreadable,
                item.submittedAt,
            )
    }
}

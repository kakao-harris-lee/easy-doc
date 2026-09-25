package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobCollectionView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobCreationView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobService
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultService
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultView
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * R7 ER-17 그림 제안 분석의 접수·조회(명세 §6).
 *
 * 컨트롤러는 HTTP 변환만 한다 — 기능 토글·소유권·이용량 판정은 전부 서비스에 있고, 여기서는
 * 그 결과를 상태 코드와 헤더로 옮긴다.
 */
@Profile("!$MIGRATE_PROFILE")
@RestController
class IllustrationSuggestionController(
    private val jobs: IllustrationSuggestionJobService,
    private val results: IllustrationSuggestionResultService,
) {
    @GetMapping(SUGGESTION_JOBS_PATH)
    fun listJobs(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<IllustrationSuggestionJobCollectionResponse> =
        ok(IllustrationSuggestionJobCollectionResponse.of(jobs.list(user.id, conversionId)))

    @PostMapping(SUGGESTION_JOBS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createJob(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: IllustrationSuggestionJobCreateRequest,
    ): ResponseEntity<IllustrationSuggestionJobResponse> =
        accepted(
            conversionId,
            jobs.create(
                ownerId = user.id,
                conversionId = conversionId,
                requestId = request.requestId,
                expectedContentRevision = request.expectedContentRevision,
            ),
        )

    @GetMapping(SUGGESTION_JOB_PATH)
    fun getJob(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(JOB_ID) jobId: UUID,
    ): ResponseEntity<IllustrationSuggestionJobResponse> =
        ok(IllustrationSuggestionJobResponse.of(jobs.get(user.id, conversionId, jobId)))

    @GetMapping(SUGGESTIONS_PATH)
    fun getSuggestions(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<IllustrationSuggestionsResponse> =
        ok(IllustrationSuggestionsResponse.of(results.get(user.id, conversionId)))

    private fun <T : Any> ok(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(body)

    private fun accepted(
        conversionId: UUID,
        creation: IllustrationSuggestionJobCreationView,
    ): ResponseEntity<IllustrationSuggestionJobResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.LOCATION,
                "/conversions/$conversionId/illustration-suggestion-jobs/${creation.job.jobId}",
            ).header(CREDIT_BALANCE_HEADER, creation.availableCredits.toPlainString())
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(IllustrationSuggestionJobResponse.of(creation.job))

    private companion object {
        const val SUGGESTION_JOBS_PATH = "/conversions/{conversion_id}/illustration-suggestion-jobs"
        const val SUGGESTION_JOB_PATH = "$SUGGESTION_JOBS_PATH/{job_id}"
        const val SUGGESTIONS_PATH = "/conversions/{conversion_id}/illustration-suggestions"
        const val CONVERSION_ID = "conversion_id"
        const val JOB_ID = "job_id"
        const val CREDIT_BALANCE_HEADER = "X-Credit-Balance"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

/** 계약 `IllustrationSuggestionJobCreateRequest`. 안내문 버전 같은 두 번째 축이 없다. */
data class IllustrationSuggestionJobCreateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty("expected_content_revision", required = true)
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
    )

data class IllustrationSuggestionJobResponse(
    @get:JsonProperty("job_id") val jobId: UUID,
    @get:JsonProperty("request_id") val requestId: UUID,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("reserved_credits") val reservedCredits: BigDecimal,
    @get:JsonProperty("failure_code") val failureCode: String?,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("updated_at") val updatedAt: String,
) {
    companion object {
        fun of(view: IllustrationSuggestionJobView): IllustrationSuggestionJobResponse =
            IllustrationSuggestionJobResponse(
                view.jobId,
                view.requestId,
                view.status.wireName,
                view.basedOnContentRevision,
                view.reservedCredits,
                view.failureCode?.wireName,
                view.createdAt.toString(),
                view.updatedAt.toString(),
            )
    }
}

data class IllustrationSuggestionJobCollectionResponse(
    @get:JsonProperty("active_job") val activeJob: IllustrationSuggestionJobResponse?,
    @get:JsonProperty("latest_job") val latestJob: IllustrationSuggestionJobResponse?,
    @get:JsonProperty("required_credits") val requiredCredits: BigDecimal?,
    @get:JsonProperty("available_credits") val availableCredits: BigDecimal,
) {
    companion object {
        fun of(view: IllustrationSuggestionJobCollectionView): IllustrationSuggestionJobCollectionResponse =
            IllustrationSuggestionJobCollectionResponse(
                view.activeJob?.let(IllustrationSuggestionJobResponse::of),
                view.latestJob?.let(IllustrationSuggestionJobResponse::of),
                view.requiredCredits,
                view.availableCredits,
            )
    }
}

/** 제안이 가리키는 저장 본문 줄 범위. 0 기반이고 양 끝을 포함한다. */
data class IllustrationSuggestionBodyRangePayload(
    @get:JsonProperty("start") val start: Int,
    @get:JsonProperty("end") val end: Int,
)

/** 원문 근거 한 건. R2 행동 안내 항목의 앵커와 같은 모양이다. */
data class IllustrationSuggestionAnchorPayload(
    @get:JsonProperty("source_unit_indexes") val sourceUnitIndexes: List<Int>,
    @get:JsonProperty("quote") val quote: String,
) {
    /** 인용은 원문 조각이다 — 개수만 남긴다(`SensitiveToStringReachTest` 규약). */
    override fun toString(): String = "IllustrationSuggestionAnchorPayload(indexCount=${sourceUnitIndexes.size})"
}

data class IllustrationSuggestionPayload(
    @get:JsonProperty("suggestion_id") val suggestionId: UUID,
    @get:JsonProperty("purpose") val purpose: String,
    @get:JsonProperty("reason") val reason: String,
    @get:JsonProperty("body_range") val bodyRange: IllustrationSuggestionBodyRangePayload,
    @get:JsonProperty("source_anchors") val sourceAnchors: List<IllustrationSuggestionAnchorPayload>,
    @get:JsonProperty("scenes") val scenes: List<String>,
    @get:JsonProperty("preserved_facts") val preservedFacts: List<String>,
    @get:JsonProperty("alt_text_draft") val altTextDraft: String,
) {
    /** 본문에서 나온 값을 로그에 싣지 않는다 — 직렬화는 이 재정의의 영향을 받지 않는다. */
    override fun toString(): String =
        "IllustrationSuggestionPayload(suggestionId=$suggestionId, purpose=$purpose, " +
            "anchorCount=${sourceAnchors.size}, sceneCount=${scenes.size}, factCount=${preservedFacts.size})"

    companion object {
        fun of(suggestion: IllustrationSuggestion): IllustrationSuggestionPayload =
            IllustrationSuggestionPayload(
                suggestion.suggestionId,
                suggestion.purpose.wireName,
                suggestion.reason,
                IllustrationSuggestionBodyRangePayload(suggestion.bodyRange.start, suggestion.bodyRange.end),
                suggestion.sourceAnchors.map {
                    IllustrationSuggestionAnchorPayload(it.sourceUnitIndexes, it.quote)
                },
                suggestion.scenes,
                suggestion.preservedFacts,
                suggestion.altTextDraft,
            )
    }
}

data class IllustrationSuggestionsResponse(
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("content_revision") val contentRevision: Long,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long?,
    @get:JsonProperty("required_credits") val requiredCredits: BigDecimal?,
    @get:JsonProperty("suggestions") val suggestions: List<IllustrationSuggestionPayload>,
    @get:JsonProperty("dropped_count") val droppedCount: Int,
) {
    companion object {
        fun of(view: IllustrationSuggestionResultView): IllustrationSuggestionsResponse =
            IllustrationSuggestionsResponse(
                view.status.wireName,
                view.contentRevision,
                view.basedOnContentRevision,
                view.requiredCredits,
                view.suggestions.map(IllustrationSuggestionPayload::of),
                view.droppedCount,
            )
    }
}

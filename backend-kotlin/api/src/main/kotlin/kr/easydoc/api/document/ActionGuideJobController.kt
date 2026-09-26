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
import kr.easydoc.application.actionguide.ActionGuideCandidateView
import kr.easydoc.application.actionguide.ActionGuideContentService
import kr.easydoc.application.actionguide.ActionGuideJobCollectionView
import kr.easydoc.application.actionguide.ActionGuideJobCreationView
import kr.easydoc.application.actionguide.ActionGuideJobService
import kr.easydoc.application.actionguide.ActionGuideJobView
import kr.easydoc.core.actionguide.ActionGuideJobStatus
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

@Profile("!$MIGRATE_PROFILE")
@RestController
class ActionGuideJobController(
    private val service: ActionGuideJobService,
    private val contentService: ActionGuideContentService,
) {
    @GetMapping(ACTION_GUIDE_JOBS_PATH)
    fun list(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<ActionGuideJobCollectionResponse> =
        ok(ActionGuideJobCollectionResponse.of(service.list(user.id, conversionId)))

    @PostMapping(ACTION_GUIDE_JOBS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: ActionGuideJobCreateRequest,
    ): ResponseEntity<ActionGuideJobResponse> =
        accepted(
            conversionId,
            service.create(
                ownerId = user.id,
                conversionId = conversionId,
                requestId = request.requestId,
                expectedContentRevision = request.expectedContentRevision,
                expectedGuideRevision = request.expectedGuideRevision,
            ),
        )

    @GetMapping(ACTION_GUIDE_JOB_PATH)
    fun get(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(JOB_ID) jobId: UUID,
    ): ResponseEntity<ActionGuideJobResponse> {
        val job = service.get(user.id, conversionId, jobId)
        val candidate =
            if (job.status == ActionGuideJobStatus.SUCCEEDED &&
                job.operation == kr.easydoc.application.actionguide.ActionGuideOperation.GUIDE
            ) {
                contentService.candidateForJob(user.id, conversionId, jobId)
            } else {
                null
            }
        return ok(ActionGuideJobResponse.of(job, candidate))
    }

    private fun <T : Any> ok(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(body)

    private fun accepted(
        conversionId: UUID,
        creation: ActionGuideJobCreationView,
    ): ResponseEntity<ActionGuideJobResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.LOCATION, "/conversions/$conversionId/action-guide-jobs/${creation.job.jobId}")
            .header(CREDIT_BALANCE_HEADER, creation.availableCredits.toPlainString())
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(ActionGuideJobResponse.of(creation.job))

    private companion object {
        const val ACTION_GUIDE_JOBS_PATH = "/conversions/{conversion_id}/action-guide-jobs"
        const val ACTION_GUIDE_JOB_PATH = "$ACTION_GUIDE_JOBS_PATH/{job_id}"
        const val CONVERSION_ID = "conversion_id"
        const val JOB_ID = "job_id"
        const val CREDIT_BALANCE_HEADER = "X-Credit-Balance"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
    }
}

data class ActionGuideJobCreateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty("expected_content_revision", required = true)
        @field:Min(1)
        @field:Max(9_007_199_254_740_991)
        val expectedContentRevision: Long,
        @param:JsonProperty("expected_guide_revision", required = true)
        @param:JsonSetter(nulls = Nulls.SET)
        @field:Min(0)
        @field:Max(9_007_199_254_740_991)
        val expectedGuideRevision: Long?,
    )

data class ActionGuideJobResponse(
    @get:JsonProperty("job_id") val jobId: UUID,
    @get:JsonProperty("request_id") val requestId: UUID,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("reserved_credits") val reservedCredits: BigDecimal,
    @get:JsonProperty("failure_code") val failureCode: String?,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("updated_at") val updatedAt: String,
    @get:JsonProperty("candidate_id") val candidateId: UUID? = null,
    @get:JsonProperty("candidate_state") val candidateState: String? = null,
    @get:JsonProperty("content") val content: ActionGuideContentPayload? = null,
    val operation: String = "guide",
) {
    companion object {
        fun of(
            view: ActionGuideJobView,
            candidate: ActionGuideCandidateView? = null,
        ): ActionGuideJobResponse =
            ActionGuideJobResponse(
                view.jobId,
                view.requestId,
                view.status.wireName,
                view.basedOnContentRevision,
                view.reservedCredits,
                view.failureCode?.wireName,
                view.createdAt.toString(),
                view.updatedAt.toString(),
                candidate?.candidateId,
                candidate?.state,
                candidate?.let { ActionGuideContentPayload.of(it.content) },
                view.operation.wireName,
            )
    }
}

data class ActionGuideJobCollectionResponse(
    @get:JsonProperty("active_job") val activeJob: ActionGuideJobResponse?,
    @get:JsonProperty("latest_job") val latestJob: ActionGuideJobResponse?,
    @get:JsonProperty("required_credits") val requiredCredits: BigDecimal,
    @get:JsonProperty("available_credits") val availableCredits: BigDecimal,
) {
    companion object {
        fun of(view: ActionGuideJobCollectionView): ActionGuideJobCollectionResponse =
            ActionGuideJobCollectionResponse(
                view.activeJob?.let(ActionGuideJobResponse::of),
                view.latestJob?.let(ActionGuideJobResponse::of),
                view.requiredCredits,
                view.availableCredits,
            )
    }
}

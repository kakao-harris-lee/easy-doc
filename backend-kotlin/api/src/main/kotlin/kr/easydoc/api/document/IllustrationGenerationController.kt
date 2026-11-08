package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.api.config.privateResponse
import kr.easydoc.api.illustration.IllustrationAccessGuard
import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetService
import kr.easydoc.application.illustration.generation.GeneratedIllustrationCollectionView
import kr.easydoc.application.illustration.generation.GeneratedIllustrationView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobCollectionView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobCreationView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobService
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobView
import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import org.springframework.context.annotation.Profile
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

@Profile("!$MIGRATE_PROFILE")
@RestController
@Suppress("TooManyFunctions")
class IllustrationGenerationController(
    private val jobs: IllustrationGenerationJobService,
    private val assets: GeneratedIllustrationAssetService,
    private val illustrationAccess: IllustrationAccessGuard,
) {
    @PostMapping(GENERATION_JOBS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createJob(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @Valid @RequestBody request: IllustrationGenerationJobCreateRequest,
    ): ResponseEntity<IllustrationGenerationJobResponse> {
        illustrationAccess.requireAllowed(user.id)
        return accepted(
            conversionId,
            jobs.create(
                user.id,
                conversionId,
                request.requestId,
                request.expectedContentRevision,
                request.suggestionId,
                request.analysisVersion,
                request.configurationRevision,
                request.configuration.toDomain(),
            ),
        )
    }

    @GetMapping(GENERATION_JOBS_PATH)
    fun listJobs(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<IllustrationGenerationJobCollectionResponse> {
        illustrationAccess.requireAllowed(user.id)
        return ok(IllustrationGenerationJobCollectionResponse.of(jobs.list(user.id, conversionId)))
    }

    @GetMapping(GENERATION_JOB_PATH)
    fun getJob(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(JOB_ID) jobId: UUID,
    ): ResponseEntity<IllustrationGenerationJobResponse> {
        illustrationAccess.requireAllowed(user.id)
        return ok(IllustrationGenerationJobResponse.of(jobs.get(user.id, conversionId, jobId)))
    }

    @GetMapping(GENERATED_ILLUSTRATIONS_PATH)
    fun listAssets(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
    ): ResponseEntity<GeneratedIllustrationCollectionResponse> {
        illustrationAccess.requireAllowed(user.id)
        return ok(GeneratedIllustrationCollectionResponse.of(assets.list(user.id, conversionId)))
    }

    @GetMapping(GENERATED_ILLUSTRATION_IMAGE_PATH)
    fun image(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(ASSET_ID) assetId: UUID,
    ): ResponseEntity<ByteArray> {
        illustrationAccess.requireAllowed(user.id)
        return png(assets, user.id, conversionId, assetId, false)
    }

    @GetMapping(GENERATED_ILLUSTRATION_DOWNLOAD_PATH)
    fun download(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(ASSET_ID) assetId: UUID,
    ): ResponseEntity<ByteArray> {
        illustrationAccess.requireAllowed(user.id)
        return png(assets, user.id, conversionId, assetId, true)
    }

    @PutMapping(GENERATED_ILLUSTRATION_APPLICATION_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateApplication(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(ASSET_ID) assetId: UUID,
        @Valid @RequestBody request: GeneratedIllustrationApplicationRequest,
    ): ResponseEntity<GeneratedIllustrationResponse> {
        illustrationAccess.requireAllowed(user.id)
        return ok(
            GeneratedIllustrationResponse.of(
                assets.updateApplication(
                    user.id,
                    conversionId,
                    assetId,
                    request.expectedContentRevision,
                    request.expectedAssetRevision,
                    request.applied,
                ),
            ),
        )
    }

    @PatchMapping(GENERATED_ILLUSTRATION_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateReview(
        user: AuthenticatedUser,
        @PathVariable(CONVERSION_ID) conversionId: UUID,
        @PathVariable(ASSET_ID) assetId: UUID,
        @Valid @RequestBody request: GeneratedIllustrationUpdateRequest,
    ): ResponseEntity<GeneratedIllustrationResponse> {
        illustrationAccess.requireAllowed(user.id)
        return ok(
            GeneratedIllustrationResponse.of(
                assets.updateReview(
                    user.id,
                    conversionId,
                    assetId,
                    request.expectedContentRevision,
                    request.expectedAssetRevision,
                    request.altText,
                    request.discarded,
                ),
            ),
        )
    }

    private fun <T : Any> ok(body: T): ResponseEntity<T> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .privateResponse()
            .body(body)

    private fun accepted(
        conversionId: UUID,
        creation: IllustrationGenerationJobCreationView,
    ): ResponseEntity<IllustrationGenerationJobResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.LOCATION,
                "/conversions/$conversionId/illustration-generation-jobs/${creation.job.jobId}",
            ).header(CREDIT_BALANCE_HEADER, creation.availableCredits.toPlainString())
            .privateResponse()
            .body(IllustrationGenerationJobResponse.of(creation.job))

    private fun png(
        service: GeneratedIllustrationAssetService,
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        download: Boolean,
    ): ResponseEntity<ByteArray> {
        val image = service.image(ownerId, conversionId, assetId)
        val disposition =
            ContentDisposition
                .builder(if (download) "attachment" else "inline")
                .filename("illustration-$assetId.png")
                .build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.IMAGE_PNG)
            .contentLength(image.bytes.size.toLong())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .privateResponse()
            .body(image.bytes)
    }

    private companion object {
        const val GENERATION_JOBS_PATH = "/conversions/{conversion_id}/illustration-generation-jobs"
        const val GENERATION_JOB_PATH = "$GENERATION_JOBS_PATH/{job_id}"
        const val GENERATED_ILLUSTRATIONS_PATH = "/conversions/{conversion_id}/generated-illustrations"
        const val GENERATED_ILLUSTRATION_PATH = "$GENERATED_ILLUSTRATIONS_PATH/{asset_id}"
        const val GENERATED_ILLUSTRATION_IMAGE_PATH = "$GENERATED_ILLUSTRATIONS_PATH/{asset_id}/image"
        const val GENERATED_ILLUSTRATION_DOWNLOAD_PATH = "$GENERATED_ILLUSTRATIONS_PATH/{asset_id}/download"
        const val GENERATED_ILLUSTRATION_APPLICATION_PATH = "$GENERATED_ILLUSTRATIONS_PATH/{asset_id}/application"
        const val CONVERSION_ID = "conversion_id"
        const val JOB_ID = "job_id"
        const val ASSET_ID = "asset_id"
        const val CREDIT_BALANCE_HEADER = "X-Credit-Balance"
    }
}

data class IllustrationGenerationConfigurationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("scenes", required = true) val scenes: List<String>,
        @param:JsonProperty("preserved_facts", required = true) val preservedFacts: List<String>,
        @param:JsonProperty("alt_text", required = true) val altText: String,
    ) {
        fun toDomain(): IllustrationGenerationConfiguration =
            IllustrationGenerationConfiguration(scenes, preservedFacts, altText)

        override fun toString(): String =
            "IllustrationGenerationConfigurationRequest(sceneCount=${scenes.size}, factCount=${preservedFacts.size}, " +
                "altText=${altText.length}자)"
    }

data class IllustrationGenerationJobCreateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty("expected_content_revision", required = true) val expectedContentRevision: Long,
        @param:JsonProperty("suggestion_id", required = true) val suggestionId: UUID,
        @param:JsonProperty("analysis_version", required = true) val analysisVersion: String,
        @param:JsonProperty("configuration_revision", required = true) val configurationRevision: String,
        @param:JsonProperty("configuration", required = true)
        val configuration: IllustrationGenerationConfigurationRequest,
    ) {
        override fun toString(): String =
            "IllustrationGenerationJobCreateRequest(requestId=$requestId, " +
                "expectedContentRevision=$expectedContentRevision, suggestionId=$suggestionId, " +
                "analysisVersion=$analysisVersion, configurationRevision=$configurationRevision, " +
                "configuration=$configuration)"
    }

data class IllustrationGenerationJobResponse(
    @get:JsonProperty("job_id") val jobId: UUID,
    @get:JsonProperty("request_id") val requestId: UUID,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("suggestion_id") val suggestionId: UUID,
    @get:JsonProperty("analysis_version") val analysisVersion: String,
    @get:JsonProperty("configuration_revision") val configurationRevision: String,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("reserved_credits") val reservedCredits: BigDecimal,
    @get:JsonProperty("failure_code") val failureCode: String?,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("updated_at") val updatedAt: String,
) {
    companion object {
        fun of(view: IllustrationGenerationJobView): IllustrationGenerationJobResponse =
            IllustrationGenerationJobResponse(
                view.jobId,
                view.requestId,
                view.status.wireName,
                view.suggestionId,
                view.analysisVersion,
                view.configurationRevision,
                view.basedOnContentRevision,
                view.reservedCredits,
                view.failureCode?.wireName,
                view.createdAt.toString(),
                view.updatedAt.toString(),
            )
    }
}

data class IllustrationGenerationJobCollectionResponse(
    @get:JsonProperty("active_job") val activeJob: IllustrationGenerationJobResponse?,
    @get:JsonProperty("latest_job") val latestJob: IllustrationGenerationJobResponse?,
    @get:JsonProperty("required_credits") val requiredCredits: BigDecimal?,
    @get:JsonProperty("available_credits") val availableCredits: BigDecimal,
    @get:JsonProperty("enabled") val enabled: Boolean,
    @get:JsonProperty("request_count") val requestCount: Int,
    @get:JsonProperty("request_limit") val requestLimit: Int,
    @get:JsonProperty("remaining_request_count") val remainingRequestCount: Int,
) {
    companion object {
        fun of(view: IllustrationGenerationJobCollectionView): IllustrationGenerationJobCollectionResponse =
            IllustrationGenerationJobCollectionResponse(
                view.activeJob?.let(IllustrationGenerationJobResponse::of),
                view.latestJob?.let(IllustrationGenerationJobResponse::of),
                view.requiredCredits,
                view.availableCredits,
                view.enabled,
                view.requestCount,
                view.requestLimit,
                view.remainingRequestCount,
            )
    }
}

data class GeneratedIllustrationApplicationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("expected_content_revision", required = true) val expectedContentRevision: Long,
        @param:JsonProperty("expected_asset_revision", required = true) val expectedAssetRevision: Long,
        @param:JsonProperty("applied", required = true) val applied: Boolean,
    )

data class GeneratedIllustrationUpdateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("expected_content_revision", required = true) val expectedContentRevision: Long,
        @param:JsonProperty("expected_asset_revision", required = true) val expectedAssetRevision: Long,
        @param:JsonProperty("alt_text") val altText: String? = null,
        @param:JsonProperty("discarded") val discarded: Boolean? = null,
    ) {
        override fun toString(): String =
            "GeneratedIllustrationUpdateRequest(expectedContentRevision=$expectedContentRevision, " +
                "expectedAssetRevision=$expectedAssetRevision, altText=${altText?.length ?: 0}자, " +
                "discarded=$discarded)"
    }

data class GeneratedIllustrationResponse(
    @get:JsonProperty("asset_id") val assetId: UUID,
    @get:JsonProperty("job_id") val jobId: UUID,
    @get:JsonProperty("suggestion_id") val suggestionId: UUID,
    @get:JsonProperty("analysis_version") val analysisVersion: String,
    @get:JsonProperty("configuration_revision") val configurationRevision: String,
    @get:JsonProperty("based_on_content_revision") val basedOnContentRevision: Long,
    @get:JsonProperty("stale") val stale: Boolean,
    @get:JsonProperty("applied") val applied: Boolean,
    @get:JsonProperty("discarded") val discarded: Boolean,
    @get:JsonProperty("asset_revision") val assetRevision: Long,
    @get:JsonProperty("purpose") val purpose: String,
    @get:JsonProperty("reason") val reason: String,
    @get:JsonProperty("body_range") val bodyRange: IllustrationSuggestionBodyRangePayload,
    @get:JsonProperty("source_anchors") val sourceAnchors: List<IllustrationSuggestionAnchorPayload>,
    @get:JsonProperty("scenes") val scenes: List<String>,
    @get:JsonProperty("preserved_facts") val preservedFacts: List<String>,
    @get:JsonProperty("alt_text") val altText: String,
    @get:JsonProperty("image_url") val imageUrl: String,
    @get:JsonProperty("download_url") val downloadUrl: String,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("provenance") val provenance: String,
) {
    override fun toString(): String =
        "GeneratedIllustrationResponse(assetId=$assetId, jobId=$jobId, suggestionId=$suggestionId, " +
            "basedOnContentRevision=$basedOnContentRevision, stale=$stale, applied=$applied, " +
            "discarded=$discarded, assetRevision=$assetRevision, anchorCount=${sourceAnchors.size}, " +
            "sceneCount=${scenes.size}, factCount=${preservedFacts.size}, altText=${altText.length}자)"

    companion object {
        fun of(view: GeneratedIllustrationView): GeneratedIllustrationResponse =
            GeneratedIllustrationResponse(
                view.assetId,
                view.jobId,
                view.metadata.suggestionId,
                view.metadata.analysisVersion,
                view.metadata.configurationRevision,
                view.basedOnContentRevision,
                view.stale,
                view.applied,
                view.discarded,
                view.assetRevision,
                view.metadata.purpose.wireName,
                view.metadata.reason,
                view.metadata.bodyRange.toPayload(),
                view.metadata.sourceAnchors.map { it.toPayload() },
                view.metadata.scenes,
                view.metadata.preservedFacts,
                view.metadata.altText,
                view.imageUrl,
                view.downloadUrl,
                view.createdAt.toString(),
                view.provenance,
            )

        private fun IllustrationSuggestionBodyRange.toPayload(): IllustrationSuggestionBodyRangePayload =
            IllustrationSuggestionBodyRangePayload(start, end)

        private fun IllustrationSuggestionSourceAnchor.toPayload(): IllustrationSuggestionAnchorPayload =
            IllustrationSuggestionAnchorPayload(sourceUnitIndexes, quote)
    }
}

data class GeneratedIllustrationCollectionResponse(
    @get:JsonProperty("content_revision") val contentRevision: Long,
    @get:JsonProperty("assets") val assets: List<GeneratedIllustrationResponse>,
) {
    companion object {
        fun of(view: GeneratedIllustrationCollectionView): GeneratedIllustrationCollectionResponse =
            GeneratedIllustrationCollectionResponse(
                view.contentRevision,
                view.assets.map(GeneratedIllustrationResponse::of),
            )
    }
}

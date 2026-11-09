package kr.easydoc.application.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONVERSION_NOT_DONE_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultRepository
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.generation.IllustrationGenerationInput
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.illustration.generation.buildIllustrationGenerationPrompt
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.generation.illustrationGenerationMetadata
import kr.easydoc.core.illustration.generation.validateIllustrationGenerationConfiguration
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import kr.easydoc.core.revision.MAX_SAFE_REVISION
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class IllustrationGenerationJobView(
    val jobId: UUID,
    val requestId: UUID,
    val status: IllustrationGenerationJobStatus,
    val suggestionId: UUID,
    val analysisVersion: String,
    val configurationRevision: String,
    val basedOnContentRevision: Long,
    val reservedCredits: BigDecimal,
    val failureCode: IllustrationGenerationJobFailureCode?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class IllustrationGenerationJobCollectionView(
    val activeJob: IllustrationGenerationJobView?,
    val latestJob: IllustrationGenerationJobView?,
    val requiredCredits: BigDecimal?,
    val availableCredits: BigDecimal,
    val enabled: Boolean,
    val requestCount: Int,
    val requestLimit: Int,
    val remainingRequestCount: Int,
)

data class IllustrationGenerationJobCreationView(
    val job: IllustrationGenerationJobView,
    val availableCredits: BigDecimal,
)

@Suppress("LongParameterList")
class IllustrationGenerationJobService(
    private val enabled: Boolean,
    private val creditsPerImage: BigDecimal?,
    private val zeroCreditsAllowed: Boolean,
    private val providerPricingConfigured: Boolean,
    private val jobs: IllustrationGenerationJobRepository,
    private val suggestions: IllustrationSuggestionResultRepository,
    private val credits: IllustrationGenerationCreditPort,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
    private val readEnabled: Boolean = true,
) {
    @Suppress("LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "ComplexCondition")
    fun create(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        expectedContentRevision: Long,
        suggestionId: UUID,
        analysisVersion: String,
        configurationRevision: String,
        configuration: IllustrationGenerationConfiguration,
    ): IllustrationGenerationJobCreationView {
        requireEnabled()
        val required = requireConfiguredCredits()
        validateRequest(expectedContentRevision, analysisVersion, configurationRevision)
        validateConfiguration(configuration, configurationRevision)
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            jobs.findByRequestId(ownerId, conversionId, requestId)?.let { existing ->
                if (
                    existing.basedOnContentRevision != expectedContentRevision ||
                    existing.suggestionId != suggestionId ||
                    existing.analysisVersion != analysisVersion ||
                    existing.configurationRevision != configurationRevision
                ) {
                    throw ConflictException(GENERATION_REQUEST_ID_CONFLICT_MESSAGE)
                }
                return@inTransaction IllustrationGenerationJobCreationView(
                    existing.toView(),
                    credits.available(ownerId, context.workspaceId),
                )
            }
            if (!context.completed) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            if (context.contentRevision != expectedContentRevision) {
                throw ConflictException(GENERATION_CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            if (jobs.lockAndCountDocumentJobs(ownerId, context.documentId) >= MAX_GENERATION_JOBS_PER_DOCUMENT) {
                throw ConflictException(GENERATION_DOCUMENT_LIMIT_MESSAGE)
            }
            val stored =
                suggestions.findLatestOwned(ownerId, conversionId)
                    ?: throw ConflictException(GENERATION_SUGGESTION_STALE_MESSAGE)
            if (stored.basedOnContentRevision != expectedContentRevision) {
                throw ConflictException(GENERATION_SUGGESTION_STALE_MESSAGE)
            }
            val suggestionSet =
                IllustrationSuggestionStorageCodec.decode(
                    cipher
                        .decrypt(
                            stored.payload,
                            stored.resultId,
                            EncryptedField.ILLUSTRATION_SUGGESTION_RESULT,
                        ).value,
                )
            if (suggestionSet.analysisVersion != analysisVersion ||
                analysisVersion != ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
            ) {
                throw ConflictException(GENERATION_SUGGESTION_STALE_MESSAGE)
            }
            val suggestion =
                suggestionSet.suggestions.singleOrNull { it.suggestionId == suggestionId }
                    ?: throw ConflictException(GENERATION_SUGGESTION_STALE_MESSAGE)
            try {
                validateIllustrationGenerationConfiguration(suggestion, configuration)
            } catch (_: IllegalArgumentException) {
                throw InvalidInputException(GENERATION_CONFIGURATION_INVALID_MESSAGE)
            }
            val metadata =
                illustrationGenerationMetadata(
                    suggestion,
                    analysisVersion,
                    configuration,
                    configurationRevision,
                )
            val input =
                IllustrationGenerationInput(
                    buildIllustrationGenerationPrompt(suggestion, configuration),
                    metadata,
                    if (zeroCreditsAllowed) "fake" else "provider",
                )
            val now = clock.instant()
            val jobId = UUID.randomUUID()
            val job =
                StoredIllustrationGenerationJob(
                    jobId = jobId,
                    ownerId = ownerId,
                    workspaceId = context.workspaceId,
                    documentId = context.documentId,
                    conversionId = conversionId,
                    requestId = requestId,
                    suggestionResultId = stored.resultId,
                    suggestionId = suggestionId,
                    analysisVersion = analysisVersion,
                    configurationRevision = configurationRevision,
                    basedOnContentRevision = expectedContentRevision,
                    inputFingerprint =
                        generationFingerprint(
                            expectedContentRevision,
                            stored.resultId,
                            suggestionId,
                            analysisVersion,
                            configurationRevision,
                        ),
                    inputPayload =
                        cipher.encrypt(
                            PlainBody(IllustrationGenerationStorageCodec.encode(input)),
                            jobId,
                            EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
                        ),
                    reservedCredits = required.amount,
                    status = IllustrationGenerationJobStatus.QUEUED,
                    failureCode = null,
                    executionId = null,
                    providerStartedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            val inserted =
                when (val result = jobs.insert(job)) {
                    is IllustrationGenerationJobInsert.Inserted -> {
                        result.job
                    }

                    IllustrationGenerationJobInsert.RequestConflict -> {
                        throw ConflictException(GENERATION_REQUEST_ID_CONFLICT_MESSAGE)
                    }

                    IllustrationGenerationJobInsert.ActiveConflict -> {
                        throw ConflictException(GENERATION_ACTIVE_JOB_CONFLICT_MESSAGE)
                    }
                }
            val available =
                when (
                    val reservation =
                        credits.reserve(
                            ownerId,
                            context.workspaceId,
                            context.documentId,
                            jobId,
                            required,
                        )
                ) {
                    is IllustrationGenerationCreditReservation.Reserved -> reservation.available

                    is IllustrationGenerationCreditReservation.Insufficient -> throw InsufficientCreditsException(
                        INSUFFICIENT_GENERATION_CREDITS_MESSAGE,
                        reservation.available,
                        required.amount,
                    )
                }
            IllustrationGenerationJobCreationView(inserted.toView(), available)
        }
    }

    fun list(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobCollectionView {
        requireReadEnabled()
        return transaction.inTransaction {
            val context =
                jobs.readOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val active = jobs.findActiveOwned(ownerId, conversionId)
            val requestCount = jobs.countDocumentJobs(ownerId, context.documentId)
            IllustrationGenerationJobCollectionView(
                active?.toView(),
                (active ?: jobs.findLatestOwned(ownerId, conversionId))?.toView(),
                creditsPerImage,
                credits.available(ownerId, context.workspaceId),
                capabilityAvailable(),
                requestCount,
                MAX_GENERATION_JOBS_PER_DOCUMENT,
                (MAX_GENERATION_JOBS_PER_DOCUMENT - requestCount).coerceAtLeast(0),
            )
        }
    }

    fun get(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): IllustrationGenerationJobView {
        requireReadEnabled()
        return transaction.inTransaction {
            jobs.readOwnedContext(ownerId, conversionId) ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            jobs.findOwned(ownerId, conversionId, jobId)?.toView()
                ?: throw NotFoundException(GENERATION_JOB_NOT_FOUND_MESSAGE)
        }
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun requireReadEnabled() {
        if (!readEnabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun capabilityAvailable(): Boolean =
        enabled && creditsPerImage != null &&
            (zeroCreditsAllowed || (creditsPerImage.signum() > 0 && providerPricingConfigured))

    private fun requireConfiguredCredits(): Credits {
        val configured = creditsPerImage ?: throw ConfigurationException(GENERATION_CREDITS_UNCONFIGURED_MESSAGE)
        val invalidZero = configured.signum() == 0 && !zeroCreditsAllowed
        val missingProviderPricing = !zeroCreditsAllowed && !providerPricingConfigured
        if (invalidZero || missingProviderPricing) {
            throw ConfigurationException(GENERATION_CREDITS_UNCONFIGURED_MESSAGE)
        }
        return Credits(configured)
    }

    private fun validateRequest(
        revision: Long,
        analysisVersion: String,
        configurationRevision: String,
    ) {
        if (revision !in 1..MAX_SAFE_REVISION || analysisVersion.isBlank() ||
            !CONFIGURATION_REVISION_PATTERN.matches(configurationRevision)
        ) {
            throw InvalidInputException(GENERATION_REQUEST_INVALID_MESSAGE)
        }
    }

    private fun validateConfiguration(
        configuration: IllustrationGenerationConfiguration,
        configurationRevision: String,
    ) {
        if (illustrationConfigurationRevision(configuration) != configurationRevision) {
            throw ConflictException(GENERATION_CONFIGURATION_CONFLICT_MESSAGE)
        }
    }

    private companion object {
        val CONFIGURATION_REVISION_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private fun generationFingerprint(
    revision: Long,
    resultId: UUID,
    suggestionId: UUID,
    analysisVersion: String,
    configurationRevision: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(
            "$revision|$resultId|$suggestionId|$analysisVersion|$configurationRevision"
                .toByteArray(StandardCharsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

internal fun StoredIllustrationGenerationJob.toView(): IllustrationGenerationJobView =
    IllustrationGenerationJobView(
        jobId,
        requestId,
        status,
        suggestionId,
        analysisVersion,
        configurationRevision,
        basedOnContentRevision,
        reservedCredits,
        failureCode,
        createdAt,
        updatedAt,
    )

const val GENERATION_JOB_NOT_FOUND_MESSAGE: String = "그림 생성 작업을 찾을 수 없습니다"
const val GENERATION_REQUEST_ID_CONFLICT_MESSAGE: String = "같은 요청 식별자가 다른 입력에 사용됐습니다"
const val GENERATION_CONTENT_REVISION_CONFLICT_MESSAGE: String = "본문 버전이 변경됐습니다"
const val GENERATION_ACTIVE_JOB_CONFLICT_MESSAGE: String = "이미 진행 중인 그림 생성 작업이 있습니다"
const val GENERATION_SUGGESTION_STALE_MESSAGE: String = "최신 그림 제안을 다시 확인해 주세요"
const val GENERATION_CONFIGURATION_CONFLICT_MESSAGE: String = "그림 구성 버전이 일치하지 않습니다"
const val GENERATION_CONFIGURATION_INVALID_MESSAGE: String = "그림 구성이 올바르지 않습니다"
const val GENERATION_REQUEST_INVALID_MESSAGE: String = "그림 생성 요청이 올바르지 않습니다"
const val INSUFFICIENT_GENERATION_CREDITS_MESSAGE: String = "그림 생성에 필요한 이용량이 부족합니다"
const val GENERATION_CREDITS_UNCONFIGURED_MESSAGE: String = "그림 생성 이용량이 설정되지 않았습니다"
const val GENERATION_DOCUMENT_LIMIT_MESSAGE: String = "문서당 그림 생성 요청 한도에 도달했습니다"
const val MAX_GENERATION_JOBS_PER_DOCUMENT: Int = 10

package kr.easydoc.application.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.illustration.suggestion.PassThroughCipher
import kr.easydoc.application.illustration.suggestion.suggestion
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.generation.IllustrationGenerationInput
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.illustration.generation.buildIllustrationGenerationPrompt
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.generation.illustrationGenerationMetadata
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class DirectGenerationTransaction : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

internal open class FakeGenerationJobs(var context: IllustrationGenerationJobContext? = defaultGenerationContext()) :
    IllustrationGenerationJobRepository {
    val rows = linkedMapOf<UUID, StoredIllustrationGenerationJob>()
    var acquired: IllustrationGenerationJobAcquire = IllustrationGenerationJobAcquire.Empty
    var held = true
    var currentInput = true
    var currentInputCheck: (() -> Boolean)? = null
    var terminalWriteSucceeds = true
    var countOverride: Int? = null
    var lockContextCalls = 0
    var readContextCalls = 0
    val terminalWrites = mutableListOf<Pair<IllustrationGenerationJobStatus, IllustrationGenerationJobFailureCode?>>()

    override fun lockAndCountDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int = countDocumentJobs(ownerId, documentId)

    override fun countDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int = countOverride ?: rows.values.count { it.ownerId == ownerId && it.documentId == documentId }

    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext? {
        lockContextCalls++
        return context
    }

    override fun readOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext? {
        readContextCalls++
        return context
    }

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationGenerationJob? =
        rows.values.singleOrNull {
            it.ownerId == ownerId && it.conversionId == conversionId && it.requestId == requestId
        }

    override fun insert(job: StoredIllustrationGenerationJob): IllustrationGenerationJobInsert {
        if (rows.values.any { it.ownerId == job.ownerId && it.status.active }) {
            return IllustrationGenerationJobInsert.ActiveConflict
        }
        rows[job.jobId] = job
        return IllustrationGenerationJobInsert.Inserted(job)
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationGenerationJob? =
        rows[jobId]?.takeIf { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob? =
        rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId && it.status.active }

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob? =
        rows.values.lastOrNull { it.ownerId == ownerId && it.conversionId == conversionId }

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationGenerationJobAcquire = acquired

    override fun lockIfHeld(lease: IllustrationGenerationJobLease): StoredIllustrationGenerationJob? =
        if (held) rows[lease.jobId] else null

    override fun hasCurrentInput(
        lease: IllustrationGenerationJobLease,
        basedOnContentRevision: Long,
        suggestionResultId: UUID,
    ): Boolean = currentInputCheck?.invoke() ?: currentInput

    override fun markProviderStarted(
        lease: IllustrationGenerationJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean {
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] =
            current.copy(
                status = IllustrationGenerationJobStatus.RUNNING,
                executionId = executionId,
                providerStartedAt = startedAt,
                updatedAt = startedAt,
            )
        return true
    }

    override fun markSucceeded(
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationGenerationJobStatus.SUCCEEDED, null, updatedAt)

    override fun markFailed(
        lease: IllustrationGenerationJobLease,
        failureCode: IllustrationGenerationJobFailureCode,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationGenerationJobStatus.FAILED, failureCode, updatedAt)

    override fun markSuperseded(
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean = terminal(lease, IllustrationGenerationJobStatus.SUPERSEDED, null, updatedAt)

    @Suppress("ReturnCount")
    private fun terminal(
        lease: IllustrationGenerationJobLease,
        status: IllustrationGenerationJobStatus,
        failureCode: IllustrationGenerationJobFailureCode?,
        updatedAt: Instant,
    ): Boolean {
        if (!terminalWriteSucceeds) return false
        val current = lockIfHeld(lease) ?: return false
        rows[lease.jobId] = current.copy(status = status, failureCode = failureCode, updatedAt = updatedAt)
        terminalWrites += status to failureCode
        return true
    }
}

internal class RecordingGenerationCredits : IllustrationGenerationCreditPort {
    var available = BigDecimal("7.0")
    var reservation: IllustrationGenerationCreditReservation =
        IllustrationGenerationCreditReservation.Reserved(available)
    var reserveCalls = 0
    val reservedAmounts = mutableListOf<Credits>()
    var consumes = 0
    var releases = 0

    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal = available

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationGenerationCreditReservation {
        reserveCalls++
        reservedAmounts += amount
        return reservation
    }

    override fun consume(job: StoredIllustrationGenerationJob) {
        consumes++
    }

    override fun release(job: StoredIllustrationGenerationJob) {
        releases++
    }
}

internal class RecordingGenerationLedger : IllustrationGenerationLlmCallLedger {
    var starts = 0
    var completes = 0
    var unknowns = 0

    override fun start(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
        startedAt: Instant,
    ) {
        starts++
    }

    override fun complete(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) {
        completes++
    }

    override fun markOutcomeUnknown(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
    ) {
        unknowns++
    }
}

internal class RecordingGenerationAssets : GeneratedIllustrationAssetRepository {
    val inserted = mutableListOf<StoredGeneratedIllustrationAsset>()
    var insertSucceeds = true
    var updateSucceeds = true
    var applied: Boolean? = null

    override fun insertAsset(
        job: StoredIllustrationGenerationJob,
        asset: StoredGeneratedIllustrationAsset,
    ): Boolean {
        if (!insertSucceeds) return false
        inserted += asset
        return true
    }

    override fun listOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): List<StoredGeneratedIllustrationAsset> = inserted

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
    ): StoredGeneratedIllustrationAsset? = inserted.singleOrNull { it.assetId == assetId }

    @Suppress("ReturnCount", "ComplexCondition")
    override fun updateApplication(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        applied: Boolean,
    ): Boolean {
        this.applied = applied
        if (!updateSucceeds) return false
        val index = inserted.indexOfFirst { it.assetId == assetId }
        val current = inserted.getOrNull(index) ?: return false
        if (current.assetRevision != expectedAssetRevision ||
            current.basedOnContentRevision > expectedContentRevision ||
            (applied && current.basedOnContentRevision != expectedContentRevision)
        ) {
            return false
        }
        inserted[index] = current.copy(applied = applied, assetRevision = current.assetRevision + 1)
        return true
    }

    @Suppress("ReturnCount", "ComplexCondition", "LongParameterList")
    override fun updateReview(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        metadata: EncryptedContent,
        png: EncryptedContent,
        discard: Boolean,
        allowStale: Boolean,
    ): Boolean {
        val index = inserted.indexOfFirst { it.assetId == assetId }
        val current = inserted.getOrNull(index) ?: return false
        if (current.assetRevision != expectedAssetRevision ||
            (!allowStale && current.basedOnContentRevision != expectedContentRevision) ||
            (discard && current.applied)
        ) {
            return false
        }
        inserted[index] = current.copy(metadata = metadata, png = png, assetRevision = current.assetRevision + 1)
        return true
    }
}

internal fun defaultGenerationContext() =
    IllustrationGenerationJobContext(
        GENERATION_WORKSPACE,
        GENERATION_DOCUMENT,
        GENERATION_CONVERSION,
        contentRevision = 3,
        completed = true,
    )

internal fun generationConfiguration() =
    IllustrationGenerationConfiguration(
        scenes = listOf("신청서를 내는 장면"),
        preservedFacts = listOf("신청서를 내야 한다"),
        altText = "신청 순서를 보여 주는 그림",
    )

internal fun generationInput(): IllustrationGenerationInput {
    val suggestion = suggestion(0)
    val configuration = generationConfiguration()
    return IllustrationGenerationInput(
        buildIllustrationGenerationPrompt(suggestion, configuration),
        illustrationGenerationMetadata(
            suggestion,
            ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
            configuration,
            illustrationConfigurationRevision(configuration),
        ),
    )
}

internal fun storedGenerationJob(
    status: IllustrationGenerationJobStatus = IllustrationGenerationJobStatus.QUEUED,
    executionId: UUID? = null,
    providerStartedAt: Instant? = null,
): StoredIllustrationGenerationJob {
    val cipher = PassThroughCipher()
    return StoredIllustrationGenerationJob(
        GENERATION_JOB,
        GENERATION_OWNER,
        GENERATION_WORKSPACE,
        GENERATION_DOCUMENT,
        GENERATION_CONVERSION,
        GENERATION_REQUEST,
        GENERATION_SUGGESTION_RESULT,
        generationInput().metadata.suggestionId,
        ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
        generationInput().metadata.configurationRevision,
        3,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        cipher.encrypt(
            PlainBody(IllustrationGenerationStorageCodec.encode(generationInput())),
            GENERATION_JOB,
            EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
        ),
        BigDecimal("0.5"),
        status,
        null,
        executionId,
        providerStartedAt,
        GENERATION_NOW,
        GENERATION_NOW,
    )
}

internal val GENERATION_OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
internal val GENERATION_WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
internal val GENERATION_DOCUMENT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
internal val GENERATION_CONVERSION: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
internal val GENERATION_REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b5")
internal val GENERATION_JOB: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b6")
internal val GENERATION_SUGGESTION_RESULT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b7")
internal val GENERATION_EXECUTION: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b8")
internal val GENERATION_NOW: Instant = Instant.parse("2026-10-31T00:00:00Z")

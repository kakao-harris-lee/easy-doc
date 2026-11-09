@file:Suppress("TooManyFunctions", "LongParameterList")

package kr.easydoc.application.illustration.generation

import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.IllustrationGenerationInput
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IllustrationGenerationJobContext(
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val completed: Boolean,
)

@Suppress("LongParameterList")
class StoredIllustrationGenerationJob(
    val jobId: UUID,
    val ownerId: UUID,
    val workspaceId: UUID,
    val documentId: UUID,
    val conversionId: UUID,
    val requestId: UUID,
    val suggestionResultId: UUID,
    val suggestionId: UUID,
    val analysisVersion: String,
    val configurationRevision: String,
    val basedOnContentRevision: Long,
    val inputFingerprint: String,
    val inputPayload: EncryptedContent,
    val reservedCredits: BigDecimal,
    val status: IllustrationGenerationJobStatus,
    val failureCode: IllustrationGenerationJobFailureCode?,
    val executionId: UUID?,
    val providerStartedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun copy(
        status: IllustrationGenerationJobStatus = this.status,
        failureCode: IllustrationGenerationJobFailureCode? = this.failureCode,
        executionId: UUID? = this.executionId,
        providerStartedAt: Instant? = this.providerStartedAt,
        updatedAt: Instant = this.updatedAt,
    ): StoredIllustrationGenerationJob =
        StoredIllustrationGenerationJob(
            jobId,
            ownerId,
            workspaceId,
            documentId,
            conversionId,
            requestId,
            suggestionResultId,
            suggestionId,
            analysisVersion,
            configurationRevision,
            basedOnContentRevision,
            inputFingerprint,
            inputPayload,
            reservedCredits,
            status,
            failureCode,
            executionId,
            providerStartedAt,
            createdAt,
            updatedAt,
        )

    override fun toString(): String =
        "StoredIllustrationGenerationJob(jobId=$jobId, conversionId=$conversionId, status=$status, " +
            "suggestionId=$suggestionId, basedOnContentRevision=$basedOnContentRevision)"
}

sealed interface IllustrationGenerationJobInsert {
    data class Inserted(val job: StoredIllustrationGenerationJob) : IllustrationGenerationJobInsert

    data object RequestConflict : IllustrationGenerationJobInsert

    data object ActiveConflict : IllustrationGenerationJobInsert
}

data class IllustrationGenerationJobLease(
    val jobId: UUID,
    val owner: String,
    val fence: Int,
)

sealed interface IllustrationGenerationJobAcquire {
    data object Empty : IllustrationGenerationJobAcquire

    data class Held(val lease: IllustrationGenerationJobLease) : IllustrationGenerationJobAcquire

    data class RecoverUnknown(val lease: IllustrationGenerationJobLease) : IllustrationGenerationJobAcquire

    data class DeadLettered(val lease: IllustrationGenerationJobLease) : IllustrationGenerationJobAcquire
}

interface IllustrationGenerationJobRepository {
    fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext?

    fun readOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext?

    fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationGenerationJob?

    fun insert(job: StoredIllustrationGenerationJob): IllustrationGenerationJobInsert

    /** Serializes generation intake for this document until the surrounding transaction completes. */
    fun lockAndCountDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int

    fun countDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int

    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationGenerationJob?

    fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob?

    fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob?

    fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationGenerationJobAcquire

    fun lockIfHeld(lease: IllustrationGenerationJobLease): StoredIllustrationGenerationJob?

    fun hasCurrentInput(
        lease: IllustrationGenerationJobLease,
        basedOnContentRevision: Long,
        suggestionResultId: UUID,
    ): Boolean

    fun markProviderStarted(
        lease: IllustrationGenerationJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean

    fun markSucceeded(
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean

    fun markFailed(
        lease: IllustrationGenerationJobLease,
        failureCode: IllustrationGenerationJobFailureCode,
        updatedAt: Instant,
    ): Boolean

    fun markSuperseded(
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean
}

class StoredGeneratedIllustrationAsset(
    val assetId: UUID,
    val jobId: UUID,
    val conversionId: UUID,
    val basedOnContentRevision: Long,
    val metadata: EncryptedContent,
    val png: EncryptedContent,
    val pngByteSize: Int,
    val width: Int,
    val height: Int,
    val provenance: String,
    val assetRevision: Long = 1,
    val applied: Boolean = false,
    val createdAt: Instant? = null,
) {
    fun copy(
        metadata: EncryptedContent = this.metadata,
        png: EncryptedContent = this.png,
        assetRevision: Long = this.assetRevision,
        applied: Boolean = this.applied,
    ): StoredGeneratedIllustrationAsset =
        StoredGeneratedIllustrationAsset(
            assetId,
            jobId,
            conversionId,
            basedOnContentRevision,
            metadata,
            png,
            pngByteSize,
            width,
            height,
            provenance,
            assetRevision,
            applied,
            createdAt,
        )

    override fun toString(): String =
        "StoredGeneratedIllustrationAsset(assetId=$assetId, jobId=$jobId, conversionId=$conversionId, " +
            "basedOnContentRevision=$basedOnContentRevision, pngByteSize=$pngByteSize, " +
            "assetRevision=$assetRevision, applied=$applied)"
}

interface GeneratedIllustrationAssetRepository {
    fun insertAsset(
        job: StoredIllustrationGenerationJob,
        asset: StoredGeneratedIllustrationAsset,
    ): Boolean

    fun listOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): List<StoredGeneratedIllustrationAsset>

    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
    ): StoredGeneratedIllustrationAsset?

    fun updateApplication(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        applied: Boolean,
    ): Boolean

    fun updateReview(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        metadata: EncryptedContent,
        png: EncryptedContent,
        discard: Boolean,
    ): Boolean
}

sealed interface IllustrationGenerationCreditReservation {
    data class Reserved(val available: BigDecimal) : IllustrationGenerationCreditReservation

    data class Insufficient(val available: BigDecimal) : IllustrationGenerationCreditReservation
}

interface IllustrationGenerationCreditPort {
    fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal

    fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationGenerationCreditReservation

    fun consume(job: StoredIllustrationGenerationJob)

    fun release(job: StoredIllustrationGenerationJob)
}

interface IllustrationGenerationLlmCallLedger {
    fun start(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
        startedAt: Instant,
    )

    fun complete(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
        record: LlmCallRecord,
    )

    fun markOutcomeUnknown(
        job: StoredIllustrationGenerationJob,
        executionId: UUID,
    )
}

sealed interface IllustrationGenerationRunResult {
    val record: LlmCallRecord

    class Succeeded(
        val generated: GeneratedIllustration,
        val input: IllustrationGenerationInput,
    ) : IllustrationGenerationRunResult {
        override val record: LlmCallRecord get() = generated.record
    }

    data class Failed(
        override val record: LlmCallRecord,
        val failureCode: IllustrationGenerationJobFailureCode,
    ) : IllustrationGenerationRunResult
}

fun interface IllustrationGenerationProviderCall {
    fun call(): IllustrationGenerationRunResult
}

fun interface IllustrationGenerationJobRunner {
    fun prepare(job: StoredIllustrationGenerationJob): IllustrationGenerationProviderCall?
}

data class IllustrationGenerationJobWorkerPolicy(
    val owner: String,
    val leaseDuration: Duration,
    val maxLeaseAttempts: Int,
) {
    init {
        require(owner.isNotBlank()) { "worker owner가 비어 있습니다" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "리스 수명이 양수가 아닙니다" }
        require(maxLeaseAttempts in 1..MAX_ALLOWED_LEASE_ATTEMPTS) { "리스 재획득 상한이 올바르지 않습니다" }
    }

    companion object {
        const val MAX_ALLOWED_LEASE_ATTEMPTS: Int = 100
    }
}

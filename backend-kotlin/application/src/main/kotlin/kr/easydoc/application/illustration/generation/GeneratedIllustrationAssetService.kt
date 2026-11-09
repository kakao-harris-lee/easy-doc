package kr.easydoc.application.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.generation.IllustrationGenerationInput
import kr.easydoc.core.illustration.generation.IllustrationGenerationMetadata
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.illustration.generation.validateIllustrationGenerationAltText
import kr.easydoc.core.revision.MAX_SAFE_REVISION
import java.time.Instant
import java.util.UUID

data class GeneratedIllustrationView(
    val assetId: UUID,
    val jobId: UUID,
    val basedOnContentRevision: Long,
    val stale: Boolean,
    val applied: Boolean,
    val discarded: Boolean,
    val assetRevision: Long,
    val metadata: IllustrationGenerationMetadata,
    val imageUrl: String,
    val downloadUrl: String,
    val createdAt: Instant,
    val provenance: String,
)

data class GeneratedIllustrationCollectionView(
    val contentRevision: Long,
    val assets: List<GeneratedIllustrationView>,
)

class GeneratedIllustrationImage(val bytes: ByteArray) {
    override fun toString(): String = "GeneratedIllustrationImage(${bytes.size}바이트)"
}

class GeneratedIllustrationAssetService(
    private val readEnabled: Boolean,
    private val jobs: IllustrationGenerationJobRepository,
    private val assets: GeneratedIllustrationAssetRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun list(
        ownerId: UUID,
        conversionId: UUID,
    ): GeneratedIllustrationCollectionView {
        requireReadEnabled()
        return transaction.inTransaction {
            val context =
                jobs.readOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            GeneratedIllustrationCollectionView(
                context.contentRevision,
                assets
                    .listOwned(ownerId, conversionId)
                    .map { it.toView(context.contentRevision) }
                    .filterNot { it.discarded },
            )
        }
    }

    @Suppress("LongParameterList", "ThrowsCount")
    fun updateApplication(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        applied: Boolean,
    ): GeneratedIllustrationView {
        requireReadEnabled()
        if (expectedContentRevision !in 1..MAX_SAFE_REVISION || expectedAssetRevision !in 1..MAX_SAFE_REVISION) {
            throw ConflictException(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)
        }
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val asset =
                assets.findOwned(ownerId, conversionId, assetId)
                    ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
            if (asset.input().metadata.discarded) {
                throw ConflictException(GENERATED_ILLUSTRATION_DISCARDED_MESSAGE)
            }
            if (context.contentRevision != expectedContentRevision) {
                throw ConflictException(GENERATION_CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            if (!assets.updateApplication(
                    ownerId,
                    conversionId,
                    assetId,
                    expectedContentRevision,
                    expectedAssetRevision,
                    applied,
                )
            ) {
                throw ConflictException(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)
            }
            assets.findOwned(ownerId, conversionId, assetId)?.toView(context.contentRevision)
                ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
        }
    }

    @Suppress("LongParameterList", "ThrowsCount", "LongMethod", "CyclomaticComplexMethod")
    fun updateReview(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
        expectedContentRevision: Long,
        expectedAssetRevision: Long,
        altText: String?,
        discard: Boolean?,
    ): GeneratedIllustrationView {
        requireReadEnabled()
        if (expectedContentRevision !in 1..MAX_SAFE_REVISION || expectedAssetRevision !in 1..MAX_SAFE_REVISION) {
            throw ConflictException(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)
        }
        if ((altText == null && discard != true) || discard == false) {
            throw InvalidInputException(GENERATED_ILLUSTRATION_UPDATE_INVALID_MESSAGE)
        }
        val shouldDiscard = discard == true
        try {
            altText?.let(::validateIllustrationGenerationAltText)
        } catch (_: IllegalArgumentException) {
            throw InvalidInputException(GENERATED_ILLUSTRATION_UPDATE_INVALID_MESSAGE)
        }
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val asset =
                assets.findOwned(ownerId, conversionId, assetId)
                    ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
            val input = asset.input()
            if (input.metadata.discarded) throw ConflictException(GENERATED_ILLUSTRATION_DISCARDED_MESSAGE)
            if (context.contentRevision != expectedContentRevision ||
                asset.basedOnContentRevision != context.contentRevision
            ) {
                throw ConflictException(GENERATION_CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            if (shouldDiscard && asset.applied) throw ConflictException(GENERATED_ILLUSTRATION_APPLIED_DISCARD_MESSAGE)
            val updated =
                IllustrationGenerationInput(
                    input.finalPrompt,
                    input.metadata.copy(
                        altText = altText ?: input.metadata.altText,
                        discarded = shouldDiscard,
                    ),
                    input.executionMode,
                )
            val metadata =
                cipher.encrypt(
                    PlainBody(IllustrationGenerationStorageCodec.encode(updated)),
                    assetId,
                    EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
                )
            val png =
                cipher.encryptBytes(
                    cipher.decryptBytes(asset.png, assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG),
                    assetId,
                    EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG,
                )
            if (!assets.updateReview(
                    ownerId,
                    conversionId,
                    assetId,
                    expectedContentRevision,
                    expectedAssetRevision,
                    metadata,
                    png,
                    shouldDiscard,
                )
            ) {
                throw ConflictException(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)
            }
            assets.findOwned(ownerId, conversionId, assetId)?.toView(context.contentRevision)
                ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
        }
    }

    fun image(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
    ): GeneratedIllustrationImage {
        requireReadEnabled()
        return transaction.inTransaction {
            jobs.readOwnedContext(ownerId, conversionId) ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val asset =
                assets.findOwned(ownerId, conversionId, assetId)
                    ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
            GeneratedIllustrationImage(
                cipher.decryptBytes(asset.png, asset.assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG).value,
            )
        }
    }

    private fun StoredGeneratedIllustrationAsset.input(): IllustrationGenerationInput =
        IllustrationGenerationStorageCodec.decode(
            cipher.decrypt(metadata, assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA).value,
        )

    private fun StoredGeneratedIllustrationAsset.toView(contentRevision: Long): GeneratedIllustrationView {
        val metadata = input().metadata
        return GeneratedIllustrationView(
            assetId = assetId,
            jobId = jobId,
            basedOnContentRevision = basedOnContentRevision,
            stale = basedOnContentRevision != contentRevision,
            applied = applied,
            discarded = metadata.discarded,
            assetRevision = assetRevision,
            metadata = metadata,
            imageUrl = "/conversions/$conversionId/generated-illustrations/$assetId/image",
            downloadUrl = "/conversions/$conversionId/generated-illustrations/$assetId/download",
            createdAt = checkNotNull(createdAt),
            provenance = provenance,
        )
    }

    private fun requireReadEnabled() {
        if (!readEnabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }
}

const val GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE: String = "생성 그림을 찾을 수 없습니다"
const val GENERATION_ASSET_REVISION_CONFLICT_MESSAGE: String = "생성 그림 상태 버전이 변경됐습니다"
const val GENERATED_ILLUSTRATION_UPDATE_INVALID_MESSAGE: String = "생성 그림 수정 요청이 올바르지 않습니다"
const val GENERATED_ILLUSTRATION_DISCARDED_MESSAGE: String = "이미 폐기된 생성 그림입니다"
const val GENERATED_ILLUSTRATION_APPLIED_DISCARD_MESSAGE: String = "적용된 그림은 먼저 적용을 해제해야 폐기할 수 있습니다"

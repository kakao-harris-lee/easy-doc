package kr.easydoc.application.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.generation.IllustrationGenerationMetadata
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.revision.MAX_SAFE_REVISION
import java.time.Instant
import java.util.UUID

data class GeneratedIllustrationView(
    val assetId: UUID,
    val jobId: UUID,
    val basedOnContentRevision: Long,
    val stale: Boolean,
    val applied: Boolean,
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
    private val enabled: Boolean,
    private val jobs: IllustrationGenerationJobRepository,
    private val assets: GeneratedIllustrationAssetRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun list(
        ownerId: UUID,
        conversionId: UUID,
    ): GeneratedIllustrationCollectionView {
        requireEnabled()
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            GeneratedIllustrationCollectionView(
                context.contentRevision,
                assets.listOwned(ownerId, conversionId).map { it.toView(context.contentRevision) },
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
        requireEnabled()
        if (expectedContentRevision !in 1..MAX_SAFE_REVISION || expectedAssetRevision !in 1..MAX_SAFE_REVISION) {
            throw ConflictException(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)
        }
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            assets.findOwned(ownerId, conversionId, assetId)
                ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
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

    fun image(
        ownerId: UUID,
        conversionId: UUID,
        assetId: UUID,
    ): GeneratedIllustrationImage {
        requireEnabled()
        return transaction.inTransaction {
            jobs.lockOwnedContext(ownerId, conversionId) ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val asset =
                assets.findOwned(ownerId, conversionId, assetId)
                    ?: throw NotFoundException(GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE)
            GeneratedIllustrationImage(
                cipher.decryptBytes(asset.png, asset.assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG).value,
            )
        }
    }

    private fun StoredGeneratedIllustrationAsset.toView(contentRevision: Long): GeneratedIllustrationView {
        val metadata =
            IllustrationGenerationStorageCodec
                .decode(
                    cipher.decrypt(this.metadata, assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA).value,
                ).metadata
        return GeneratedIllustrationView(
            assetId = assetId,
            jobId = jobId,
            basedOnContentRevision = basedOnContentRevision,
            stale = basedOnContentRevision != contentRevision,
            applied = applied,
            assetRevision = assetRevision,
            metadata = metadata,
            imageUrl = "/conversions/$conversionId/generated-illustrations/$assetId/image",
            downloadUrl = "/conversions/$conversionId/generated-illustrations/$assetId/download",
            createdAt = checkNotNull(createdAt),
            provenance = provenance,
        )
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }
}

const val GENERATED_ILLUSTRATION_NOT_FOUND_MESSAGE: String = "생성 그림을 찾을 수 없습니다"
const val GENERATION_ASSET_REVISION_CONFLICT_MESSAGE: String = "생성 그림 상태 버전이 변경됐습니다"

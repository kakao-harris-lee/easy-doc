package kr.easydoc.application.illustration

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONTENT_REVISION_CONFLICT_MESSAGE
import kr.easydoc.application.document.CONTENT_REVISION_INVALID_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_DONE_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.LockedConversion
import kr.easydoc.application.document.MAX_SAFE_REVISION
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import kr.easydoc.core.segment.splitUnits
import java.util.UUID

/**
 * 그림 배치(ER-16) 조회 결과 하나. [toString] 은 개수만 남긴다 — 좌표는 본문 내용을 유추할 수
 * 있는 데이터다([IllustrationPlacements] 와 같은 규약).
 */
data class IllustrationPlacementsView(
    val conversionId: UUID,
    val currentContentRevision: Long,
    val placementsContentRevision: Long?,
    val stale: Boolean,
    val placements: List<IllustrationPlacement>,
) {
    override fun toString(): String =
        "IllustrationPlacementsView($conversionId, current=$currentContentRevision, " +
            "placements=$placementsContentRevision, stale=$stale, count=${placements.size})"
}

/**
 * ER-16 「그림 배치」 조회·저장. 토글은 ER-15 가 만든 기존 [IllustrationsService] 와 같은
 * `easydoc.illustrations.enabled` 를 그대로 쓴다.
 *
 * 저장(PUT)은 현재 집합을 통째로 교체한다(현재 집합만 있고 이력이 없다). 조회 시 저장된
 * revision 이 변환의 현재 revision 과 다르면 지우지 않고 `stale = true` 로 그대로 돌려준다 —
 * 검수자가 다시 확인해 저장한다.
 */
@Suppress("LongParameterList")
class IllustrationPlacementService(
    private val enabled: Boolean,
    private val conversions: ConversionRepository,
    private val catalog: IllustrationCatalogSource,
    private val placements: IllustrationPlacementRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    @Suppress("ThrowsCount") // 소유·완료를 계약의 서로 다른 실패(404/409)로 유지한다.
    fun read(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationPlacementsView {
        requireEnabled()
        return transaction.inTransaction {
            val conversion =
                conversions.findOwnedResult(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            if (conversion.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            val stored = placements.findOwned(ownerId, conversionId)
            if (stored == null) {
                IllustrationPlacementsView(conversionId, conversion.contentRevision, null, false, emptyList())
            } else {
                IllustrationPlacementsView(
                    conversionId,
                    conversion.contentRevision,
                    stored.contentRevision,
                    stored.contentRevision != conversion.contentRevision,
                    open(stored).entries,
                )
            }
        }
    }

    @Suppress("ThrowsCount") // 소유·완료·CAS·검증·저장 실패를 계약의 서로 다른 HTTP 의미로 유지한다.
    fun replace(
        ownerId: UUID,
        conversionId: UUID,
        expectedContentRevision: Long,
        requested: IllustrationPlacements,
    ): IllustrationPlacementsView {
        requireEnabled()
        requireContentRevision(expectedContentRevision)
        return transaction.inTransaction {
            val locked =
                conversions.lockOwnedForReview(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            if (locked.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            if (locked.contentRevision != expectedContentRevision) {
                throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            val body = currentBody(locked) ?: throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            requested.validateAgainst(splitUnits(body).size, catalog.catalog())

            if (requested.entries.isEmpty()) {
                placements.deleteOwned(ownerId, conversionId)
            } else {
                save(ownerId, conversionId, expectedContentRevision, requested)
            }

            IllustrationPlacementsView(
                conversionId = conversionId,
                currentContentRevision = expectedContentRevision,
                placementsContentRevision = if (requested.entries.isEmpty()) null else expectedContentRevision,
                stale = false,
                placements = requested.entries,
            )
        }
    }

    private fun save(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        requested: IllustrationPlacements,
    ) {
        // 봉인 AAD 가 행 id 에 결속되므로, 이미 있는 행이면 그 id 를 그대로 다시 쓴다 — 새
        // UUID 를 매번 뽑으면 ON CONFLICT UPDATE 가 유지하는 옛 id 와 어긋나 다음 조회의
        // 복호화가 실패한다.
        val id = placements.findOwned(ownerId, conversionId)?.id ?: UUID.randomUUID()
        val encoded =
            IllustrationPlacementCodec.encode(requested)
                ?: throw StorageException(ILLUSTRATION_PLACEMENTS_STORAGE_MESSAGE)
        val sealed = cipher.encryptBytes(PlainBytes(encoded), id, EncryptedField.ILLUSTRATION_PLACEMENTS)
        val saved = placements.replaceOwned(ownerId, conversionId, id, contentRevision, sealed)
        if (!saved) throw StorageException(ILLUSTRATION_PLACEMENTS_STORAGE_MESSAGE)
    }

    private fun currentBody(locked: LockedConversion): String? =
        locked.envelope.ciphertexts.editedText
            ?.let { cipher.decrypt(it, locked.envelope.conversionId, EncryptedField.CONVERSION_EDITED_TEXT).value }
            ?: locked.envelope.ciphertexts.easyText
                ?.let { cipher.decrypt(it, locked.envelope.conversionId, EncryptedField.CONVERSION_EASY_TEXT).value }

    private fun open(stored: StoredIllustrationPlacements): IllustrationPlacements =
        IllustrationPlacementCodec.decode(
            cipher.decryptBytes(stored.payload, stored.id, EncryptedField.ILLUSTRATION_PLACEMENTS).value,
        )

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun requireContentRevision(value: Long) {
        if (value < 1 || value > MAX_SAFE_REVISION) throw InvalidInputException(CONTENT_REVISION_INVALID_MESSAGE)
    }
}

const val ILLUSTRATION_PLACEMENTS_STORAGE_MESSAGE: String = "그림 배치를 저장하지 못했습니다"

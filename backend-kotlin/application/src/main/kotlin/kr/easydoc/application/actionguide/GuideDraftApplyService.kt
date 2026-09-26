package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.MAX_SAFE_REVISION
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.text.normalizeLineEndings
import kr.easydoc.core.text.stripControlChars
import java.util.UUID

/** Applying a full draft always creates an unreviewed revision and an independently encrypted previous body. */
class GuideDraftApplyService(
    private val conversions: ConversionRepository,
    private val repository: GuideDraftApplyRepository,
    private val drafts: GuideDraftApplySource,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    @Suppress("ThrowsCount", "LongMethod") // Authorization, idempotency, snapshot and body CAS are one transaction.
    fun apply(
        ownerId: UUID,
        conversionId: UUID,
        command: GuideDraftApplyCommand,
    ): GuideDraftApplyView {
        validateCommand(command)
        return transaction.inTransaction {
            val locked = conversions.lockOwnedForReview(ownerId, conversionId) ?: notFound()
            repository.findRequest(ownerId, conversionId, command.requestId)?.let { prior ->
                if (prior.command != command) throw ConflictException("같은 반영 요청에 다른 입력이 사용됐습니다")
                return@inTransaction GuideDraftApplyView(
                    prior.appliedContentRevision,
                    prior.snapshotId,
                    replayed = true,
                )
            }
            if (locked.status != ConversionStatus.DONE || locked.contentRevision != command.expectedContentRevision) {
                throw ConflictException("본문이 바뀌었거나 변환이 완료되지 않았습니다")
            }
            if (locked.contentRevision >= MAX_SAFE_REVISION) throw ConflictException("본문 버전 상한에 도달했습니다")
            val proposed = drafts.requireApplicable(ownerId, conversionId, command)
            validateBody(proposed)
            val envelope = locked.envelope
            val previous =
                envelope.ciphertexts.editedText?.let {
                    cipher.decrypt(it, conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
                } ?: envelope.ciphertexts.easyText?.let {
                    cipher.decrypt(it, conversionId, EncryptedField.CONVERSION_EASY_TEXT)
                } ?: throw StorageException("이전 본문을 보존할 수 없습니다")
            val snapshotId = UUID.randomUUID()
            val nextRevision = locked.contentRevision + 1
            val archive =
                StoredGuideDraftApplication(
                    snapshotId,
                    conversionId,
                    command,
                    nextRevision,
                    cipher.encrypt(previous, snapshotId, EncryptedField.ACTION_GUIDE_PREVIOUS_BODY),
                )
            if (!repository.insertSnapshot(ownerId, archive)) throw StorageException("이전 본문을 보존하지 못했습니다")
            if (!repository.saveUnreviewed(
                    ownerId,
                    envelope,
                    seal(envelope, proposed),
                    locked.contentRevision,
                    nextRevision,
                )
            ) {
                throw StorageException("전체 문서를 반영하지 못했습니다")
            }
            GuideDraftApplyView(nextRevision, snapshotId)
        }
    }

    /** Recovery reads stay available when generation is disabled; document retention still applies. */
    fun listPreviousBodies(
        ownerId: UUID,
        conversionId: UUID,
    ): List<GuidePreviousBodyView> =
        transaction.inTransaction {
            conversions.findOwnedResult(ownerId, conversionId) ?: notFound()
            repository.listApplications(ownerId, conversionId)
        }

    /** The retained ID is discoverable through [listPreviousBodies] after a reload or lost apply response. */
    fun previousBody(
        ownerId: UUID,
        conversionId: UUID,
        snapshotId: UUID,
    ): PlainBody {
        val snapshot = repository.findSnapshot(ownerId, conversionId, snapshotId) ?: notFound()
        return cipher.decrypt(snapshot.previousBody, snapshotId, EncryptedField.ACTION_GUIDE_PREVIOUS_BODY)
    }

    private fun validateCommand(command: GuideDraftApplyCommand) {
        if (listOf(command.expectedContentRevision, command.expectedAnalysisRevision, command.expectedDraftRevision)
                .any { it !in 1..MAX_SAFE_REVISION } || command.expectedReviewRevision !in 0..MAX_SAFE_REVISION
        ) {
            throw InvalidInputException("전체 문서 반영 버전이 올바르지 않습니다")
        }
    }

    private fun validateBody(body: PlainBody) {
        if (body.value.isBlank() || charCountOf(body.value) > MAX_CONVERTIBLE_CHARS ||
            body.value != normalizeLineEndings(stripControlChars(body.value))
        ) {
            throw InvalidInputException("전체 문서 본문이 비어 있거나 저장 길이·형식 제한을 넘었습니다")
        }
    }

    /** All encrypted conversion columns retain the same scheme/key label even during key rotation. */
    private fun seal(
        envelope: ConversionEnvelope,
        body: PlainBody,
    ): ConversionEnvelope {
        val easy =
            envelope.ciphertexts.easyText?.let {
                if (envelope.scheme == cipher.writeScheme && envelope.keyVersion == cipher.writeKeyVersion) {
                    it
                } else {
                    cipher.encrypt(
                        cipher.decrypt(it, envelope.conversionId, EncryptedField.CONVERSION_EASY_TEXT),
                        envelope.conversionId,
                        EncryptedField.CONVERSION_EASY_TEXT,
                    )
                }
            }
        return ConversionEnvelope(
            envelope.conversionId,
            cipher.writeScheme,
            cipher.writeKeyVersion,
            ConversionCiphertexts(
                easy,
                cipher.encrypt(body, envelope.conversionId, EncryptedField.CONVERSION_EDITED_TEXT),
            ),
        )
    }

    private fun notFound(): Nothing = throw NotFoundException("전체 문서 반영 기록을 찾을 수 없습니다")
}

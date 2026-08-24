package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.ReviewedBody
import kr.easydoc.core.privacy.restoreForExport
import java.util.UUID

/**
 * 변환 결과 내보내기 — 소유 확인·완료 여부·자리표시자 복원을 한 자리에서 판정한다.
 * 파일 바이트 조립은 [DocumentExporter] 가 한다.
 */
class ConversionExportService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val exporter: DocumentExporter,
    private val transaction: TransactionRunner,
) {
    /** 내 변환의 최종본을 파일로 만든다. 복호화는 트랜잭션 안, 패키지 조립은 밖에서 한다. */
    fun export(
        ownerId: UUID,
        conversionId: UUID,
        format: ExportFormat,
    ): ExportFile {
        val prepared = transaction.inTransaction { prepare(ownerId, conversionId) }
        return exporter.export(prepared.title, prepared.body, format)
    }

    private fun prepare(
        ownerId: UUID,
        conversionId: UUID,
    ): PreparedExport {
        val stored =
            conversions.findOwnedExport(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        requireDone(stored)
        val draft = requireDraft(stored)
        val reviewed =
            open(stored.result.id, stored.result.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT)
        val items =
            open(stored.result.id, stored.result.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                ?.let(maskedItems::decode)
                .orEmpty()
                .map(::toMaskedItem)
        return PreparedExport(title = stored.documentTitle, body = restoredBody(draft, reviewed, items))
    }

    private fun requireDone(stored: StoredExport) {
        if (!stored.result.status.exposesResult) {
            throw ConflictException(EXPORT_NOT_DONE_MESSAGE)
        }
    }

    private fun requireDraft(stored: StoredExport): PlainBody =
        open(stored.result.id, stored.result.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT)
            ?: throw StorageException(UNREADABLE_EXPORT_MESSAGE)

    private fun restoredBody(
        draft: PlainBody,
        reviewed: PlainBody?,
        items: List<MaskedItem>,
    ): String {
        // 검수본이 없으면 자리표시자를 복원하지 않는다. 계약 GET export 복원 규칙과 같다.
        val restoration =
            restoreForExport(
                ModelDraft(draft.value),
                reviewed?.let { ReviewedBody(it.value) },
                items,
            )
        if (reviewed == null && restoration.missing.isNotEmpty()) {
            throw ConflictException(EXPORT_MISSING_PLACEHOLDERS_MESSAGE)
        }
        return restoration.text
    }

    private fun open(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = column?.let { cipher.decrypt(it, record, field) }

    private fun toMaskedItem(view: MaskedItemView): MaskedItem =
        MaskedItem(view.category, view.placeholder, view.original)

    private class PreparedExport(
        val title: String,
        val body: String,
    ) {
        override fun toString(): String = "PreparedExport(제목 ${title.length}자, 본문 ${body.length}자)"
    }

    private companion object {
        const val UNREADABLE_EXPORT_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}

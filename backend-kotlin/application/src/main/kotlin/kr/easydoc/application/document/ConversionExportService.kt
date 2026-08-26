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
 * 변환 결과 내보내기 — 소유 확인·**형식 합의**·완료 여부·자리표시자 복원을 한 자리에서
 * 판정한다. 파일 바이트 조립은 [DocumentExporter] 가 한다.
 *
 * **형식은 서버가 정한다**(계약 `x-export-format-derivation.enforcement`, `DESIGN.md` §6.5).
 * 유도 규칙을 여기 옮겨 적지 않고 [ExportFormat.ofSource] 하나를 쓴다 — 조회 응답의
 * `export_format` 도 같은 함수를 지나므로(`ConversionQueryService`) 두 자리가 갈릴 수 없다.
 */
class ConversionExportService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val exporter: DocumentExporter,
    private val transaction: TransactionRunner,
) {
    /**
     * 내 변환의 최종본을 파일로 만든다. 복호화는 트랜잭션 안, 패키지 조립은 밖에서 한다.
     *
     * [requested] 는 **주장이지 선택이 아니다.** `null` 이면 서버가 원본에서 정하고
     * (계약 `enforcement.on_absent`), 값을 주면 그 값과 **같아야** 한다. 나가는 형식은
     * 어느 쪽이든 [ExportFormat.ofSource] 가 정한 그 값이다.
     */
    fun export(
        ownerId: UUID,
        conversionId: UUID,
        requested: ExportFormat?,
    ): ExportFile {
        val prepared = transaction.inTransaction { prepare(ownerId, conversionId, requested) }
        return exporter.export(prepared.title, prepared.body, prepared.format)
    }

    private fun prepare(
        ownerId: UUID,
        conversionId: UUID,
        requested: ExportFormat?,
    ): PreparedExport {
        val stored =
            conversions.findOwnedExport(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        val format = agreedFormat(stored, requested)
        requireDone(stored)
        val draft = requireDraft(stored)
        val reviewed =
            open(stored.result.id, stored.result.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT)
        val items =
            open(stored.result.id, stored.result.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                ?.let(maskedItems::decode)
                .orEmpty()
                .map(::toMaskedItem)
        return PreparedExport(
            title = stored.documentTitle,
            format = format,
            body = restoredBody(draft, reviewed, items),
        )
    }

    /**
     * 이 변환이 내보낼 형식. 요청이 그것과 다르면 **409**다.
     *
     * **소유 판정 뒤, 완료 판정 앞이다.** 뒤인 것은 남의 변환에서 형식 불일치를 알려 주면
     * 404로 가려 둔 사실이 형식 축으로 새기 때문이고(계약 `x-why-409-and-not-422`),
     * 앞인 것은 이 거절이 **기다려도 바뀌지 않는** 사실이기 때문이다 — 결국 거절될 요청에
     * 「끝난 뒤에 다시 오라」고 답하면 틀린 행동을 권하게 된다.
     */
    private fun agreedFormat(
        stored: StoredExport,
        requested: ExportFormat?,
    ): ExportFormat {
        val derived =
            ExportFormat.ofSource(stored.result.sourceFormat)
                ?: throw ConflictException(EXPORT_FORMAT_UNAVAILABLE_MESSAGE)
        if (requested != null && requested != derived) {
            throw ConflictException(EXPORT_FORMAT_MISMATCH_MESSAGE)
        }
        return derived
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
        val format: ExportFormat,
        val body: String,
    ) {
        override fun toString(): String = "PreparedExport(제목 ${title.length}자, ${format.extension}, 본문 ${body.length}자)"
    }

    private companion object {
        const val UNREADABLE_EXPORT_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}

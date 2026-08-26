package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.formatPreservationOf
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/** 변환 조회 유스케이스 — **내** 변환 한 건의 상태와 결과를 읽는다. */
class ConversionQueryService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val transaction: TransactionRunner,
) {
    /** 내 변환 한 건의 **상태와 결과**를 읽는다. 완료 전에는 결과 필드가 비어 있다. */
    fun read(
        ownerId: UUID,
        conversionId: UUID,
    ): ConversionView {
        val stored =
            transaction.inTransaction { conversions.findOwnedResult(ownerId, conversionId) }
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        return if (stored.status.exposesResult) completed(stored) else beforeDone(stored)
    }

    /**
     * 완료 전 응답 — **결과 필드는 하나도 실리지 않고** 배열 둘은 빈 목록이다(X-E3).
     *
     * **형식 셋은 여기서도 실린다.** 문서 메타라 변환 완료 여부와 무관하고, 계약이
     * `ConversionResponse` 설명에서 「형식 셋은 결과 필드가 아니다」로 그것을 고정한다 —
     * 실패한 변환 화면에서도 「이 문서는 DOCX 였다」는 사실은 여전히 참이다.
     */
    private fun beforeDone(stored: StoredConversion): ConversionView =
        ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            formatPreservation = formatPreservationOf(stored.hasStoredOriginal),
            easyText = null,
            editedText = null,
            reviewedAt = null,
            maskedItems = emptyList(),
            missingPlaceholders = emptyList(),
            model = null,
            providerName = null,
            inputTokens = null,
            outputTokens = null,
            failureCode = stored.failureCode,
        )

    private fun completed(stored: StoredConversion): ConversionView =
        ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            formatPreservation = formatPreservationOf(stored.hasStoredOriginal),
            easyText = open(stored.id, stored.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT),
            editedText = open(stored.id, stored.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT),
            reviewedAt = stored.reviewedAt,
            maskedItems =
                open(stored.id, stored.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                    ?.let(maskedItems::decode)
                    ?: emptyList(),
            missingPlaceholders = stored.missingPlaceholders,
            model = stored.model,
            providerName = stored.providerName,
            inputTokens = stored.inputTokens,
            outputTokens = stored.outputTokens,
            failureCode = stored.failureCode,
        )

    /** 암호문 한 열을 연다. 열이 비어 있으면 `null` — **빈 문자열로 접지 않는다.** */
    private fun open(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = column?.let { cipher.decrypt(it, record, field) }
}

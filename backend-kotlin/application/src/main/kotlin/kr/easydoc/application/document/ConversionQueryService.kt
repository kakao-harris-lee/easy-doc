package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionView
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
        val exposed = stored.status.exposesResult
        return ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            easyText = open(exposed, stored, stored.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT),
            editedText = open(exposed, stored, stored.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT),
            reviewedAt = stored.reviewedAt,
            // **`null` 이 아니라 빈 목록이다**(계약 `masked_items` — X-E3). 대기 중 변환의
            // 대응표 열은 NULL 이고, 그 상태의 뜻은 「가린 것이 아직 없다」다.
            maskedItems =
                open(exposed, stored, stored.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                    ?.let(maskedItems::decode)
                    ?: emptyList(),
            missingPlaceholders = stored.missingPlaceholders,
            model = stored.model,
            providerName = stored.providerName,
            inputTokens = stored.inputTokens,
            outputTokens = stored.outputTokens,
            failureCode = stored.failureCode,
        )
    }

    /**
     * 암호문 한 열을 연다. 열이 비어 있으면 `null` — **빈 문자열로 접지 않는다.**
     * [exposed] 가 거짓이면 **열지 않는다**: 열고 버리면 버릴 평문이 생기고, 열 수 없는 완료 전
     * 행이 500 이 되는데 계약은 200 이다.
     */
    private fun open(
        exposed: Boolean,
        stored: StoredConversion,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = if (exposed) column?.let { cipher.decrypt(it, stored.id, field) } else null
}

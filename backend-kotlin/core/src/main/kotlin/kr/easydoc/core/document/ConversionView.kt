package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import java.time.Instant
import java.util.UUID

/** 변환 한 건의 **조회 결과** — 계약 `ConversionResponse` 의 13필드에 1:1 대응한다. */
data class ConversionView(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    val easyText: PlainBody?,
    val editedText: PlainBody?,
    val reviewedAt: Instant?,
    val maskedItems: List<MaskedItemView>,
    val missingPlaceholders: List<String>,
    val model: String?,
    val providerName: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val failureCode: String?,
) {
    /** 로그 허용목록 그대로 — 식별자·상태·실패 코드와 **개수**뿐이다. */
    override fun toString(): String =
        "ConversionView($id, ${status.wireName}, failure=$failureCode, " +
            "masked=${maskedItems.size}, missing=${missingPlaceholders.size})"
}

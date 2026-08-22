package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.privacy.CONTENT_MASK

/** 마스킹 항목 한 건. 계약 `components/schemas/MaskedItemResponse` — 세 필드가 전부다. */
data class MaskedItemResponse(
    @get:JsonProperty("category") val category: String,
    @get:JsonProperty("placeholder") val placeholder: String,
    @get:JsonProperty("original") val original: String,
) {
    /**
     * **원값을 찍지 않는다.** 자리표시자는 라벨이라 남긴다 — 계약이
     * `missing_placeholders` 를 두고 *"라벨뿐이라 개인정보가 아니다"* 라고 적은 것과 같은 판단.
     */
    override fun toString(): String =
        "MaskedItemResponse(category=$category, placeholder=$placeholder, original=$CONTENT_MASK)"

    companion object {
        fun of(item: MaskedItemView): MaskedItemResponse =
            MaskedItemResponse(
                category = item.category.label,
                placeholder = item.placeholder,
                // 이 저장소에서 가린 값이 평문 문자열이 되는 **유일한** 호출이다.
                original = item.original.reveal(),
            )
    }
}

/**
 * `GET /conversions/{conversion_id}` 응답. 계약 `components/schemas/ConversionResponse` —
 * **열세 필드가 전부다.**
 */
data class ConversionResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("document_id") val documentId: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("easy_text") val easyText: String?,
    @get:JsonProperty("edited_text") val editedText: String?,
    @get:JsonProperty("reviewed_at") val reviewedAt: String?,
    @get:JsonProperty("masked_items") val maskedItems: List<MaskedItemResponse>,
    @get:JsonProperty("missing_placeholders") val missingPlaceholders: List<String>,
    @get:JsonProperty("model") val model: String?,
    @get:JsonProperty("provider_name") val providerName: String?,
    @get:JsonProperty("input_tokens") val inputTokens: Int?,
    @get:JsonProperty("output_tokens") val outputTokens: Int?,
    @get:JsonProperty("failure_code") val failureCode: String?,
) {
    /** 본문 둘은 표식과 길이만, 마스킹 항목은 **개수만** 남긴다. */
    override fun toString(): String =
        "ConversionResponse(id=$id, documentId=$documentId, status=$status, " +
            "easyText=$CONTENT_MASK ${easyText?.length ?: 0}자, editedText=$CONTENT_MASK ${editedText?.length ?: 0}자, " +
            "reviewedAt=$reviewedAt, maskedItems=${maskedItems.size}건, " +
            "missingPlaceholders=$missingPlaceholders, model=$CONTENT_MASK, providerName=$CONTENT_MASK, " +
            "inputTokens=$inputTokens, outputTokens=$outputTokens, failureCode=$failureCode)"

    companion object {
        fun of(view: ConversionView): ConversionResponse =
            ConversionResponse(
                id = view.id.toString(),
                documentId = view.documentId.toString(),
                status = view.status.wireName,
                // `PlainBody` 를 벗기는 **유일한** 자리. DTO 가 그 타입을 들지 않는 사유는 클래스 KDoc.
                easyText = view.easyText?.value,
                editedText = view.editedText?.value,
                reviewedAt = view.reviewedAt?.toString(),
                maskedItems = view.maskedItems.map(MaskedItemResponse::of),
                missingPlaceholders = view.missingPlaceholders,
                model = view.model,
                providerName = view.providerName,
                inputTokens = view.inputTokens,
                outputTokens = view.outputTokens,
                failureCode = view.failureCode,
            )
    }
}

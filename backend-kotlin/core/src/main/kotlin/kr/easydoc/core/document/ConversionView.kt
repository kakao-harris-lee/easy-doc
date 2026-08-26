package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.easyread.ExportFormat
import java.time.Instant
import java.util.UUID

/**
 * 변환 한 건의 **조회 결과** — 계약 `ConversionResponse` 의 16필드에 1:1 대응한다.
 *
 * **형식 셋([sourceFormat]·[exportFormat]·[formatPreservation])은 결과 필드가 아니다.**
 * 문서 메타에서 오므로 완료 전에도 그대로 실린다 — 그래서 [carriesResult] 가 세지 않는다.
 */
data class ConversionView(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    /** 이 변환이 딸린 문서의 원본 형식. 계약 `SourceFormat`. */
    val sourceFormat: SourceFormat,
    /**
     * 내려받을 때 **써야 하는** 형식 — [sourceFormat] 이 정한다([ExportFormat.ofSource]).
     * 원본이 PDF 면 `null` 이고, 그것은 「같은 형식으로 내보낼 수단이 없다」는 뜻이다.
     */
    val exportFormat: ExportFormat?,
    /** 서식 유지 판정. `null` 은 「유지 불가」가 아니라 **서버가 아직 판정하지 않았다**. */
    val formatPreservation: FormatPreservation?,
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
    /**
     * 계약 `ConversionResponse.description` 의 **「결과 필드」 아홉** 중 하나라도 값을 들었는가.
     * 완료 전에 나가는 것은 `id`·`document_id`·`status`·`failure_code` 와 **형식 셋** 일곱이다.
     */
    val carriesResult: Boolean
        get() =
            listOf(easyText, editedText, reviewedAt, model, providerName, inputTokens, outputTokens)
                .any { it != null } ||
                maskedItems.isNotEmpty() ||
                missingPlaceholders.isNotEmpty()

    /**
     * 로그 허용목록 그대로 — 식별자·상태·실패 코드와 **개수**뿐이다.
     * 형식은 문서 메타이지 사용자 콘텐츠가 아니라 남긴다(`Document.toString` 과 같은 판단).
     */
    override fun toString(): String =
        "ConversionView($id, ${status.wireName}, ${sourceFormat.wireName}, failure=$failureCode, " +
            "masked=${maskedItems.size}, missing=${missingPlaceholders.size})"
}

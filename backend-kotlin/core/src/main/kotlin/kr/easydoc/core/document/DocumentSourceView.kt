package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import java.util.UUID

/**
 * 문서 한 건의 **원문 조회 결과** — 계약 `DocumentSourceResponse` 의 네 필드에 1:1 대응한다.
 *
 * **[sourceText] 는 마스킹 전 원문이다.** 저장된 값이 사용자가 올린 그대로이고 마스킹은
 * LLM 에 넘기기 직전에만 적용되므로, 이 값에 주민등록번호·카드번호가 그대로 들어 있을 수
 * 있다. 그 사실이 계약 `x-private-response-headers.applies_to` 에 이 오퍼레이션이 실린
 * 근거다.
 *
 * 파일 업로드였다면 **파서가 뽑아낸 텍스트**이지 원본 파일 바이트가 아니다 — 원본 바이트는
 * `document_originals` 에 따로 살고 내보내기가 쓴다.
 *
 * `data class` 가 아닌 것은 [ConversionView] 와 다른 판단이 아니라 **[toString] 을 손으로
 * 쥐기 위해서다** — 컴파일러가 만들어 주는 `toString` 은 본문을 그대로 찍는다.
 */
class DocumentSourceView(
    val documentId: UUID,
    val sourceFormat: SourceFormat,
    val charCount: Int,
    val sourceText: PlainBody,
) {
    /** **본문을 찍지 않는다.** 식별자·형식과 길이만 남긴다 — [PlainBody] 의 규약과 같다. */
    override fun toString(): String = "DocumentSourceView($documentId, ${sourceFormat.wireName}, ${charCount}자)"
}

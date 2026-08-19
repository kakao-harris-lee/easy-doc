package kr.easydoc.core.document

import java.time.Instant
import java.util.UUID

/**
 * 저장된 문서 한 건의 **비밀 아닌 부분**.
 *
 * 원본: `app/models/document.py::Document` 중 API 가 실제로 쓰는 필드.
 *
 * ## 원문이 여기 없다
 *
 * `source_text_encrypted` 는 이 타입에 담기지 않는다. 담으면 목록 조회가 문서 전체
 * 암호문을 힙에 올리게 되고(목록은 20건씩 온다), 무엇보다 **원문을 들 이유가 있는 자리와
 * 없는 자리가 타입으로 갈리지 않는다.** 본문이 필요한 경로(조회·내보내기)는
 * `EncryptedContent` 를 따로 읽는다.
 *
 * ## `user_id`·`workspace_id` 가 없다
 *
 * 소유자는 **조회 조건으로만** 쓰인다(`Workspace` 와 같은 규칙). 작업 공간 식별자도 계약의
 * 어떤 응답(`DocumentCreatedResponse`·`DocumentListItem`)에도 실리지 않는다. 응답에 실리지
 * 않는 값을 타입에 담으면 실수로 실릴 자리가 생긴다 — 필요해지는 커밋이 그때 더한다.
 *
 * ## `data class` 가 아닌 이유
 *
 * 컴파일러가 만드는 `toString()` 에 [title] 이 그대로 실린다. 제목은 사용자가 적었거나
 * **본문 첫 줄에서 유도한** 값이라 둘 다 사용자 콘텐츠이고, 계약이 이 필드에 사적 응답
 * 헤더를 요구한 이유도 그것이다(`x-private-response-headers.applies_to`). 손으로 쓴
 * [toString] 이 길이만 남긴다 — `SensitiveToStringReachTest` 의 「일반 class 의 손으로 쓴
 * toString」 축이 이 재정의를 실제로 시험한다.
 */
class Document(
    val id: UUID,
    val title: String,
    val sourceFormat: SourceFormat,
    val charCount: Int,
    val createdAt: Instant,
    val retentionExpiresAt: Instant,
) {
    /** 제목은 길이만 남긴다. 형식·글자 수는 로그 허용목록(문서 ID·길이·상태) 안이다. */
    override fun toString(): String = "Document($id, ${sourceFormat.wireName}, 제목 ${title.length}자, ${charCount}자)"
}

/**
 * 목록 한 줄 — 문서 메타 + **최신 변환**의 상태.
 *
 * 계약 `DocumentListItem` 이 요구하는 조합이다. 변환이 하나도 없으면 세 값이 모두 `null` 이다.
 *
 * ## `reviewedAt` 이 왜 함께 오는가
 *
 * 목록에서 "검수함/초안"을 표시하려면 [status] 만으로는 알 수 없다 — `done` 은 "AI 변환이
 * 끝났다"는 뜻일 뿐이다(계약 `DocumentListItem.reviewed_at`).
 *
 * `data class` 가 아닌 이유는 [Document] 와 같다 — 안에 제목이 들어 있다.
 */
class DocumentListing(
    val document: Document,
    val conversionId: UUID?,
    val status: ConversionStatus?,
    val reviewedAt: Instant?,
) {
    override fun toString(): String = "DocumentListing($document, $conversionId, ${status?.wireName}, $reviewedAt)"
}

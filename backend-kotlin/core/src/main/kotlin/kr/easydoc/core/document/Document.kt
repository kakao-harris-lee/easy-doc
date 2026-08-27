package kr.easydoc.core.document

import java.time.Instant
import java.util.UUID

/** 저장된 문서 한 건의 **비밀 아닌 부분**. */
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

/** 목록 한 줄 — 문서 메타 + **최신 변환**의 상태. */
class DocumentListing(
    val document: Document,
    val conversionId: UUID?,
    val status: ConversionStatus?,
    val reviewedAt: Instant?,
    /**
     * 그 최신 변환에 피드백을 마지막으로 제출한 시각. 없으면 `null`.
     *
     * [reviewedAt] 과 **다른 사실이라** 함께 든다 — 목록이 「검수함」을 그리는 근거가 둘로
     * 갈린다(수정본 저장·의견 제출). 계약 `DocumentListItem.feedback_submitted_at` 참고.
     */
    val feedbackSubmittedAt: Instant?,
) {
    override fun toString(): String =
        "DocumentListing($document, $conversionId, ${status?.wireName}, $reviewedAt, $feedbackSubmittedAt)"
}

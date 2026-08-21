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
) {
    override fun toString(): String = "DocumentListing($document, $conversionId, ${status?.wireName}, $reviewedAt)"
}

package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.application.document.AcceptedUpload
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.privacy.CONTENT_MASK

// 민감 필드 부재 검증: `DocumentDtoLeakTest`.

/** `POST /documents` 의 요청·응답 본문. */
data class DocumentTextRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("text") val text: String,
        /** 사용자가 적어 준 제목. 없으면 계약 `x-title-policy.fallback_title` 이다. */
        @param:JsonProperty("title")
        @param:JsonSetter(nulls = Nulls.SET)
        val title: String?,
        /**
         * 담을 작업 공간. 생략·`null` 이면 기본(가장 먼저 만든) 공간이다. 여는 사유는 [title] 과 같다.
         *
         * **파싱하지 않은 원문으로 받는다.** `UUID` 로 선언하면 형식 판정이 Jackson 역직렬화
         * 시점에 일어나 배열 `detail` 이 나가고, 계약이 정한 검사 순서(본문 길이가 앞선다)보다
         * 앞질러 버린다. multipart 팔과 **같은 함수**가 같은 문자열 `detail` 을 내야 한다.
         */
        @param:JsonProperty("workspace_id")
        @param:JsonSetter(nulls = Nulls.SET)
        val workspaceId: String?,
    ) {
        /** **본문도 제목도 작업 공간 원문도 찍지 않는다** — 셋 다 사용자가 준 임의 문자열이다. */
        override fun toString(): String =
            "DocumentTextRequest(text=$CONTENT_MASK ${text.length}자, title=$CONTENT_MASK, workspaceId=$CONTENT_MASK)"
    }

/**
 * 업로드 접수 응답. 계약 `components/schemas/DocumentCreatedResponse` — 네 필드가 전부다.
 *
 * 생성자와 `copy()` 가 `private` 인 것은 **[of] 가 유일한 조립 지점**임을 컴파일러가 지키게
 * 하려는 것이다. 규율로만 두면 우회가 눈에 띄지 않는다.
 */
@ConsistentCopyVisibility
data class DocumentCreatedResponse private constructor(
    @get:JsonProperty("document_id") val documentId: String,
    @get:JsonProperty("conversion_id") val conversionId: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("char_count") val charCount: Int,
) {
    companion object {
        fun of(accepted: AcceptedUpload): DocumentCreatedResponse =
            DocumentCreatedResponse(
                documentId = accepted.documentId.toString(),
                conversionId = accepted.conversionId.toString(),
                status = accepted.status.wireName,
                charCount = accepted.charCount,
            )
    }
}

/** 목록 한 줄. 계약 `components/schemas/DocumentListItem` — 아홉 필드가 전부다. 조립은 [of] 뿐이다. */
@ConsistentCopyVisibility
data class DocumentListItemResponse private constructor(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("title") val title: String,
    @get:JsonProperty("source_format") val sourceFormat: String,
    @get:JsonProperty("char_count") val charCount: Int,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("retention_expires_at") val retentionExpiresAt: String,
    @get:JsonProperty("conversion_id") val conversionId: String?,
    @get:JsonProperty("status") val status: String?,
    @get:JsonProperty("reviewed_at") val reviewedAt: String?,
) {
    /** 제목은 표식과 길이만 남긴다. `Document.toString` 과 같은 형태다. */
    override fun toString(): String =
        "DocumentListItemResponse(id=$id, title=$CONTENT_MASK ${title.length}자, sourceFormat=$sourceFormat, " +
            "charCount=$charCount, createdAt=$createdAt, retentionExpiresAt=$retentionExpiresAt, " +
            "conversionId=$conversionId, status=$status, reviewedAt=$reviewedAt)"

    companion object {
        fun of(listing: DocumentListing): DocumentListItemResponse =
            DocumentListItemResponse(
                id = listing.document.id.toString(),
                title = listing.document.title,
                sourceFormat = listing.document.sourceFormat.wireName,
                charCount = listing.document.charCount,
                createdAt = listing.document.createdAt.toString(),
                retentionExpiresAt = listing.document.retentionExpiresAt.toString(),
                conversionId = listing.conversionId?.toString(),
                status = listing.status?.wireName,
                reviewedAt = listing.reviewedAt?.toString(),
            )
    }
}

/** `GET /documents` 응답. 계약 `components/schemas/DocumentListResponse` — 네 필드다. */
data class DocumentListResponse(
    @get:JsonProperty("items") val items: List<DocumentListItemResponse>,
    @get:JsonProperty("limit") val limit: Int,
    @get:JsonProperty("offset") val offset: Int,
    @get:JsonProperty("has_more") val hasMore: Boolean,
) {
    companion object {
        /** 한 건 더 읽어 온 목록을 응답으로 자른다. */
        fun of(
            fetched: List<DocumentListing>,
            limit: Int,
            offset: Int,
        ): DocumentListResponse =
            DocumentListResponse(
                items = fetched.take(limit).map(DocumentListItemResponse::of),
                limit = limit,
                offset = offset,
                hasMore = fetched.size > limit,
            )
    }
}

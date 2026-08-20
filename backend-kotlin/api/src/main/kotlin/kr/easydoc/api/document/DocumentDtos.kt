package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.application.document.AcceptedUpload
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.privacy.CONTENT_MASK
import java.util.UUID

/**
 * `POST /documents` 의 요청·응답 본문.
 *
 * ## 필드 이름을 명시한다
 *
 * 계약이 모든 JSON 필드를 **snake_case** 로 못박았다(`info.description`). Jackson 의 기본
 * 이름 결정은 Kotlin 프로퍼티 이름을 그대로 쓰므로 `documentId` 가 그대로 나가 계약 위반이
 * 된다. 전역 네이밍 전략을 켜지 않고 **필드마다 이름을 적는 이유**는 `AuthDtos.kt`·
 * `WorkspaceDtos.kt` 와 같다 — 전략은 클래스가 하나 늘 때 조용히 적용 범위 밖으로 새지만
 * 애너테이션은 그 자리에 남는다.
 *
 * `@JsonCreator` 를 붙이는 이유도 같다: 이 프로젝트는 `jackson-module-kotlin` 을 쓰지
 * 않으므로 파라미터 이름 정보 없이 생성자 바인딩이 서려면 이름을 명시해야 한다.
 *
 * ## 저장·평문 타입을 **하나도 담지 않는다** (X2)
 *
 * `PlainBody`·`MaskedText`·`ModelDraft`·`ReviewedBody`·`EncryptedContent` 중 어느 것도
 * 이 파일의 DTO 파라미터 타입에 없다. `PlainBody` 는 `@JvmInline value class(String)` 이라
 * Jackson 이 **그냥 문자열로 직렬화한다** — 응답 DTO 가 그것을 들면 봉인 전 평문이 그대로
 * 나간다. 금지를 애너테이션(`@JsonIgnore`)으로 두지 않는 이유는 애너테이션은 새 DTO 가
 * 안 붙이면 조용히 새기 때문이다(`CLAUDE.md` 규칙 4 — 은폐형이 아니라 탐지형).
 * 강제는 [kr.easydoc.api.DocumentDtoLeakTest] 가 **타입 부재**로 한다.
 */
data class DocumentTextRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("text") val text: String,
        /**
         * 사용자가 적어 준 제목. 없으면 계약 `x-title-policy.fallback_title` 이다.
         *
         * **`@JsonSetter(nulls = Nulls.SET)` 이 필요하다.** 전역 기본값이
         * [Nulls.FAIL] 이라(`JsonRequestStrictnessConfig`) 명시적 `null` 과 필드 누락이
         * 둘 다 「필수 필드 누락」으로 끊긴다. 그런데 계약은 이 필드를
         * `anyOf: [{type: string}, {type: 'null'}]` 로 두었고, React `createDocumentFromText`
         * 는 **제목이 없을 때 `title: null` 을 언제나 실어 보낸다**
         * (`frontend/src/api/client.ts` — `title: title ?? null`). 여는 것이 눈에 보이는
         * 자리이므로 애너테이션으로 연다.
         */
        @param:JsonProperty("title")
        @param:JsonSetter(nulls = Nulls.SET)
        val title: String?,
        /** 담을 작업 공간. 생략·`null` 이면 기본(가장 먼저 만든) 공간이다. 여는 사유는 [title] 과 같다. */
        @param:JsonProperty("workspace_id")
        @param:JsonSetter(nulls = Nulls.SET)
        val workspaceId: UUID?,
    ) {
        /**
         * **본문도 제목도 찍지 않는다.**
         *
         * 요청 DTO 는 특히 위험하다 — 역직렬화·검증 실패의 진단 로그가 요청 객체를 통째로
         * 찍는 것이 가장 흔한 형태이고, 여기 담긴 것은 사용자가 방금 붙여넣은 **문서 본문**
         * 이다(개인정보 포함 여부와 무관하게 로그 금지 — `CLAUDE.md` 보안 규칙).
         * 길이는 남긴다: 로그에 허용된 것이 "문서 ID·길이·처리 상태"까지다.
         */
        override fun toString(): String =
            "DocumentTextRequest(text=$CONTENT_MASK ${text.length}자, title=$CONTENT_MASK, workspaceId=$workspaceId)"
    }

/**
 * 업로드 접수 응답. 계약 `components/schemas/DocumentCreatedResponse` — 네 필드가 전부다.
 *
 * **계약에 없는 필드를 더하지 않는다.** 계약 테스트가 최상위 키 집합을 `required` 와
 * **정확히** 대조하므로 하나만 더해도 빨개진다.
 *
 * 식별자를 `String` 으로 든다 — `UUID` 를 그대로 두면 Jackson 의 직렬화 설정이 바뀔 때
 * 표현이 조용히 달라진다(`WorkspaceResponse.created_at` 이 같은 이유로 문자열이다).
 * `status` 도 enum 이 아니라 `wireName` 문자열이다: enum 이름을 바꾸는 순간 응답 바이트가
 * 바뀌는데 그 사실이 DTO 에는 안 보인다.
 *
 * `toString()` 을 재정의하지 않는다 — 담긴 것이 식별자·상태·문자 수뿐이라 샐 것이 없다.
 */
data class DocumentCreatedResponse(
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

/**
 * 목록 한 줄. 계약 `components/schemas/DocumentListItem` — 아홉 필드가 전부다.
 *
 * ## 널이어야 하는 넷을 **널로 둔다** (X-E2)
 *
 * `conversion_id`·`status`·`reviewed_at` 은 변환이 없으면 `null` 이고, 계약은 그 셋을
 * `required` 에 넣어 두었다 — 즉 **값이 없어도 키는 나가야 한다.** Jackson 기본 포함 정책이
 * `ALWAYS` 라 널도 실리지만, 그 사실에 기대지 않도록 계약 테스트가 「완료 전 항목에서도
 * 키가 하나도 생략되지 않는다」를 단언한다(DL-2).
 *
 * ## `status` 가 enum 이 아니라 문자열이다
 *
 * [DocumentCreatedResponse] 와 같은 판단이다 — enum 이름을 바꾸는 순간 응답 바이트가 바뀌는데
 * 그 사실이 DTO 에는 안 보인다. 시각도 [java.time.Instant.toString] 결과를 문자열로 굳힌다
 * (`WorkspaceResponse.created_at` 과 같은 사유).
 *
 * ## `data class` 이지만 [toString] 을 손으로 쓴다
 *
 * [title] 이 사용자가 적어 준 문자열이다. 컴파일러가 만드는 `toString()` 은 그것을 그대로
 * 찍고, 목록 응답은 스무 건이 한 번에 오므로 한 줄이 스무 개의 제목을 로그에 남긴다.
 * `SensitiveToStringReachTest` 의 「민감 필드를 든 data class」 축이 이 재정의를 시험한다.
 */
data class DocumentListItemResponse(
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

/**
 * `GET /documents` 응답. 계약 `components/schemas/DocumentListResponse` — 네 필드다.
 *
 * ## 총 개수 필드가 **없다**
 *
 * 계약이 그 이유를 적었다 — 전수 `COUNT` 는 목록 조회마다 비싸고 화면은 다음 쪽 유무만
 * 쓴다. 그래서 `has_more` 를 **한 건 더 읽어**(`limit + 1`) 판정한다. 계약에 없는 필드를
 * 더하는 것도 위반이라(계약 테스트가 키 집합을 정확히 대조한다) 편의로 `total` 을 붙이지
 * 않는다 — 계약 `x-change-policy` O-16 이 그 추가를 이미 판정해 두었다.
 *
 * `limit`·`offset` 은 **요청에 쓰인 값을 그대로 되돌려준다.** 클라이언트가 다음 쪽을
 * 조립할 때 자기가 보낸 값을 기억하지 않아도 되게 하는 값이다.
 *
 * `toString()` 을 재정의하지 않는다 — 담긴 것이 수·불리언과 [DocumentListItemResponse] 뿐이고,
 * 그 항목이 스스로 제목을 가린다.
 */
data class DocumentListResponse(
    @get:JsonProperty("items") val items: List<DocumentListItemResponse>,
    @get:JsonProperty("limit") val limit: Int,
    @get:JsonProperty("offset") val offset: Int,
    @get:JsonProperty("has_more") val hasMore: Boolean,
) {
    companion object {
        /**
         * 한 건 더 읽어 온 목록을 응답으로 자른다.
         *
         * [fetched] 는 `limit + 1` 건까지 들어 있다. 그 한 건은 **다음 쪽의 존재를 알리는
         * 데만** 쓰고 응답에 싣지 않는다 — 실으면 사용자가 요청한 개수보다 많이 나간다.
         */
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

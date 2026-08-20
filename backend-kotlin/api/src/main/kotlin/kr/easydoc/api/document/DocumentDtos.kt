package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.application.document.AcceptedUpload
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

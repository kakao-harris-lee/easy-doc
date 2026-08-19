package kr.easydoc.api.workspace

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing

/**
 * `/workspaces` 네 오퍼레이션의 요청·응답 본문.
 *
 * ## 필드 이름을 명시한다
 *
 * 계약이 모든 JSON 필드를 **snake_case** 로 못박았다(`info.description`). Jackson 의 기본
 * 이름 결정은 Kotlin 프로퍼티 이름을 그대로 쓰므로 `documentCount` 가 그대로 나가 계약
 * 위반이 된다. 전역 네이밍 전략을 켜지 않고 **필드마다 이름을 적는 이유**는, 전략은
 * 클래스가 하나 늘 때 조용히 적용 범위 밖으로 새지만 애너테이션은 그 자리에 남기 때문이다.
 *
 * `@JsonCreator` 를 붙이는 이유: 이 프로젝트는 `jackson-module-kotlin` 을 쓰지 않는다.
 * 파라미터 이름 정보 없이도 생성자 바인딩이 서려면 이름을 명시한 생성자를 지목해야 한다.
 *
 * ## `name` 에 Bean Validation 을 달지 않는다 (계약 F3)
 *
 * [WorkspaceNameRequest.name] 은 계약 `x-request-field-constraints` 가 지목한 다섯 필드 중
 * 하나이고, **양방향으로 갈렸던** 자리다 — `maxLength: 50` 은 제어문자를 포함한 원시 55자
 * (정규화하면 45자)를 잘못 거절하고, `minLength: 1` 은 서비스가 거절하는 `"   "` 를
 * 통과시킨다. 게다가 위반 응답이 422 **배열** + 영문 문구가 되어 계약(422 **문자열** +
 * 한국어)과 어긋난다. 판정은 `application` 의 `WorkspaceNameRules` 가 한다.
 *
 * 이 금지는 주석이 아니라 [kr.easydoc.api.RequestFieldConstraintLayerTest] 가 계약 파일을
 * 읽어 상시 확인한다 — 이 DTO 가 생기는 순간 그 검사의 도달 범위에 들어온다.
 */
data class WorkspaceNameRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("name") val name: String,
    ) {
        /**
         * **이름을 찍지 않는다** — [Workspace.toString] 과 같은 이유(A-3).
         *
         * 요청 DTO 는 특히 위험하다. 역직렬화 실패·검증 실패의 진단 로그가 요청 객체를
         * 통째로 찍는 것이 가장 흔한 형태이고, 여기 담긴 것은 **사용자가 방금 적은 문자열**이다.
         */
        override fun toString(): String = "WorkspaceNameRequest(name=${Workspace.NAME_MASK})"
    }

/**
 * 작업 공간 한 건. 계약 `components/schemas/WorkspaceResponse` — `POST`·`PATCH` 응답이다.
 *
 * **`document_count` 를 담지 않는다.** 계약이 그것을 [WorkspaceListItemResponse] 에만 두었고,
 * 계약에 없는 필드를 더하는 것도 계약 위반이다(계약 테스트가 키 집합을 **정확히** 대조한다).
 *
 * **소유자 식별자도 담지 않는다** — 계약의 `required` 는 셋뿐이고, 사용자 ID 노출 범위는
 * 좁을수록 좋다.
 *
 * `created_at` 은 [java.time.Instant.toString] 이 만드는 ISO-8601 UTC 문자열이다
 * (`2026-08-19T01:02:03.456789Z`). Jackson 의 날짜 직렬화 설정에 기대지 않고 문자열로
 * 굳히는 이유는, 그 설정이 바뀌면 계약의 `format: date-time` 이 조용히 epoch 숫자가 되기
 * 때문이다 — 그리고 그것은 이 DTO 를 읽어서는 보이지 않는다.
 */
data class WorkspaceResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("created_at") val createdAt: String,
) {
    /** 이름을 찍지 않는다 — [Workspace.toString] 과 같은 이유(A-3). */
    override fun toString(): String = "WorkspaceResponse(id=$id, name=${Workspace.NAME_MASK}, createdAt=$createdAt)"

    companion object {
        fun of(workspace: Workspace): WorkspaceResponse =
            WorkspaceResponse(
                id = workspace.id.toString(),
                name = workspace.name,
                createdAt = workspace.createdAt.toString(),
            )
    }
}

/**
 * 목록 한 줄. 계약 `components/schemas/WorkspaceListItem` = `WorkspaceResponse` + `document_count`.
 *
 * 계약이 `allOf` 로 적은 것을 상속이 아니라 **평평한 클래스**로 옮긴다. Jackson 에서
 * data class 를 상속하려면 상위 클래스를 `open` 으로 열어야 하는데, 그러면 응답 DTO 가
 * 확장 가능해져 하위 타입이 계약 밖 필드를 실을 수 있는 통로가 생긴다.
 */
data class WorkspaceListItemResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("created_at") val createdAt: String,
    @get:JsonProperty("document_count") val documentCount: Int,
) {
    /** 이름을 찍지 않는다 — [Workspace.toString] 과 같은 이유(A-3). */
    override fun toString(): String =
        "WorkspaceListItemResponse(id=$id, name=${Workspace.NAME_MASK}, " +
            "createdAt=$createdAt, documentCount=$documentCount)"

    companion object {
        fun of(listing: WorkspaceListing): WorkspaceListItemResponse {
            val workspace = WorkspaceResponse.of(listing.workspace)
            return WorkspaceListItemResponse(
                id = workspace.id,
                name = workspace.name,
                createdAt = workspace.createdAt,
                documentCount = listing.documentCount,
            )
        }
    }
}

/**
 * `GET /workspaces` 응답. 계약 `components/schemas/WorkspaceListResponse`.
 *
 * **`items` 하나만 든다** — 페이지네이션이 없다. `DocumentListResponse` 와 달리
 * `limit`·`offset`·`has_more` 가 계약에 없고, 없는 필드를 더하는 것은 계약 위반이다.
 */
data class WorkspaceListResponse(
    @get:JsonProperty("items") val items: List<WorkspaceListItemResponse>,
)

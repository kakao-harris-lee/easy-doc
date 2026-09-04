package kr.easydoc.api.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Size

/**
 * `POST /auth/oauth/{provider}/start` 요청.
 *
 * **필드 길이를 스키마 층(Bean Validation)에서 잰다** — `SignupRequest`·`LoginRequest`
 * 다섯 필드와 다른 결정이다(그 파일 KDoc: 이메일·비밀번호는 정규화·서비스 판정이 얽혀
 * 도메인 규칙이라 문자열 422). 여기 필드(`redirect_uri`·`code`·`state`)는 사용자가
 * 타이핑하는 값이 아니라 SPA 가 그대로 옮기는 프로토콜 토큰이라, "완전히 빈 값"은
 * 정규화·형식 판정이 필요 없는 순수한 스키마 위반이다 — 통과했다면 제공자를 불렀을
 * 왕복(`SocialLoginProvider.exchange`)을 값이 비어 있다는 이유만으로 하지 않으려고
 * 컨트롤러 진입 전에 끊는다(계약 `minLength: 1`, 리뷰 후속 조치).
 */
data class OAuthStartRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("redirect_uri")
        @field:Size(min = 1)
        val redirectUri: String,
    )

/** `POST /auth/oauth/{provider}/start` 응답. 계약 `OAuthStartResponse`. */
data class OAuthStartResponse(
    @get:JsonProperty("authorization_url") val authorizationUrl: String,
    @get:JsonProperty("state") val state: String,
)

/** `POST /auth/oauth/{provider}/callback` 요청. 길이 결정은 [OAuthStartRequest] KDoc 참고. */
data class OAuthCallbackRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("code")
        @field:Size(min = 1)
        val code: String,
        @param:JsonProperty("state")
        @field:Size(min = 1)
        val state: String,
        @param:JsonProperty("redirect_uri")
        @field:Size(min = 1)
        val redirectUri: String,
    ) {
        /** 인가 코드가 로그·오류 메시지로 새지 않게 한다 — `SignupRequest`·`LoginRequest` 와 같은 이유. */
        override fun toString(): String = "OAuthCallbackRequest(state=$state, redirectUri=$redirectUri)"
    }

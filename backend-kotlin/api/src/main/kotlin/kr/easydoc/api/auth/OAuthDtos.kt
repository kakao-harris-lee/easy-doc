package kr.easydoc.api.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/** `POST /auth/oauth/{provider}/start` 요청. */
data class OAuthStartRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("redirect_uri") val redirectUri: String,
    )

/** `POST /auth/oauth/{provider}/start` 응답. 계약 `OAuthStartResponse`. */
data class OAuthStartResponse(
    @get:JsonProperty("authorization_url") val authorizationUrl: String,
    @get:JsonProperty("state") val state: String,
)

/** `POST /auth/oauth/{provider}/callback` 요청. */
data class OAuthCallbackRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("code") val code: String,
        @param:JsonProperty("state") val state: String,
        @param:JsonProperty("redirect_uri") val redirectUri: String,
    ) {
        /** 인가 코드가 로그·오류 메시지로 새지 않게 한다 — `SignupRequest`·`LoginRequest` 와 같은 이유. */
        override fun toString(): String = "OAuthCallbackRequest(state=$state, redirectUri=$redirectUri)"
    }

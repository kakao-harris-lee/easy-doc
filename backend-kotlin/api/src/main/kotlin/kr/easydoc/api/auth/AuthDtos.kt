package kr.easydoc.api.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.user.User

/** `/auth` 세 경로의 요청·응답 본문. */
data class SignupRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("email") val email: String,
        @param:JsonProperty("password") val password: String,
    ) {
        /** 비밀번호가 로그·오류 메시지로 새지 않게 한다. `data class` 기본 `toString()` 을 덮는다. */
        override fun toString(): String = "SignupRequest(...)"
    }

/** 로그인 요청. 가입과 달리 길이·형식 규칙을 적용하지 않는다(계약: 422 는 필드 누락뿐). */
data class LoginRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("email") val email: String,
        @param:JsonProperty("password") val password: String,
    ) {
        override fun toString(): String = "LoginRequest(...)"
    }

/** 사용자 공개 표현. 계약 `components/schemas/UserResponse`. */
data class UserResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("email") val email: String,
    @get:JsonProperty("email_verified") val emailVerified: Boolean,
) {
    /**
     * **이메일을 찍지 않는다.** 형제 요청 DTO 둘(`SignupRequest`·`LoginRequest`)이 같은
     * 이유로 이미 가리고 있는데 응답 DTO 만 빠져 있었다(게이트 23 privacy-gate 3a).
     * `/auth/me` 는 요청마다 이 객체를 만든다.
     */
    override fun toString(): String = "UserResponse(id=$id, email=$CONTENT_MASK, emailVerified=$emailVerified)"

    companion object {
        fun of(user: User): UserResponse =
            UserResponse(id = user.id.toString(), email = user.email, emailVerified = user.emailVerifiedAt != null)
    }
}

/** `/auth/email-verification/confirm` 요청. 계약 `ConfirmEmailVerificationRequest`. */
data class ConfirmEmailVerificationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("code") val code: String,
    ) {
        /** 코드가 로그·오류 메시지로 새지 않게 한다. */
        override fun toString(): String = "ConfirmEmailVerificationRequest(...)"
    }

/** 액세스 토큰 응답. 계약 `components/schemas/TokenResponse`. */
data class TokenResponse(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("token_type") val tokenType: String,
    @get:JsonProperty("expires_in") val expiresIn: Long,
) {
    override fun toString(): String = "TokenResponse(tokenType=$tokenType, expiresIn=$expiresIn)"
}

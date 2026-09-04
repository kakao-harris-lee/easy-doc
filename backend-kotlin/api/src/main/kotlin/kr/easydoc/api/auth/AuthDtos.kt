package kr.easydoc.api.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.auth.SocialLoginProviderId
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
    @get:JsonProperty("identities") val identities: List<UserIdentityResponse>,
) {
    /**
     * **이메일을 찍지 않는다.** 형제 요청 DTO 둘(`SignupRequest`·`LoginRequest`)이 같은
     * 이유로 이미 가리고 있는데 응답 DTO 만 빠져 있었다(게이트 23 privacy-gate 3a).
     * `/auth/me` 는 요청마다 이 객체를 만든다.
     */
    override fun toString(): String = "UserResponse(id=$id, email=$CONTENT_MASK, emailVerified=$emailVerified)"

    companion object {
        /**
         * [identities] 는 기본값 빈 목록이다 — `signup`(2.10.0에도 항상 비밀번호 계정,
         * 아직 아무 신원도 잇지 않은 상태)이 그 자리에서 부를 때 매번 빈 목록을 만들어
         * 넘기지 않아도 되게 한다. `/auth/me`(연결된 신원이 있을 수 있다)만 실제 값을 준다.
         */
        fun of(
            user: User,
            identities: List<SocialLoginProviderId> = emptyList(),
        ): UserResponse =
            UserResponse(
                id = user.id.toString(),
                email = user.email,
                emailVerified = user.emailVerifiedAt != null,
                identities = identities.map(UserIdentityResponse::of),
            )
    }
}

/** `UserResponse.identities` 의 항목 하나. 계약 `components/schemas/UserIdentityResponse`. */
data class UserIdentityResponse(
    @get:JsonProperty("provider") val provider: String,
) {
    /**
     * 길이만 남긴다. `provider` 자체는 공개 enum 값(`google`)이라 개인정보는 아니지만,
     * 필드 하나짜리 래퍼 DTO 는 `SensitiveToStringReachTest` 가 "감싼 쪽이 가린다"
     * 전제로 기계적으로 재는 대상이다 — `WorkspaceNameRequest`·`MaskedText` 와 같은 이유.
     */
    override fun toString(): String = "UserIdentityResponse(${provider.length}자)"

    companion object {
        fun of(provider: SocialLoginProviderId): UserIdentityResponse = UserIdentityResponse(provider.wireValue)
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

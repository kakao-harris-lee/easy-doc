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
    @get:JsonProperty("phone_verified") val phoneVerified: Boolean,
    /**
     * `users.password_hash IS NOT NULL`인지 나타낸다. 화면이 연결 해제가 마지막 로그인 수단을
     * 없애는지 미리 판정할 수 있는 값이다(정본은 `x-social-login.explicit_linking`).
     */
    @get:JsonProperty("has_password") val hasPassword: Boolean,
    @get:JsonProperty("identities") val identities: List<UserIdentityResponse>,
    /**
     * `users.is_admin` 표시값이다. 실제 관리자 API 접근은 매 요청 DB를 다시 읽는 `AdminGuard`가
     * 판정하며, 이메일이 미검증이면 이 값이 참이어도 403이다.
     */
    @get:JsonProperty("is_admin") val isAdmin: Boolean,
) {
    /** `/auth/me` 응답에는 이메일을 로그나 오류 메시지에 남기지 않는다. */
    override fun toString(): String =
        "UserResponse(id=$id, email=$CONTENT_MASK, emailVerified=$emailVerified, phoneVerified=$phoneVerified, " +
            "hasPassword=$hasPassword, " +
            "isAdmin=$isAdmin)"

    companion object {
        /** [identities] 를 생략하는 호출은 아직 연결된 신원이 없는 계정으로 처리한다. */
        fun of(
            user: User,
            identities: List<SocialLoginProviderId> = emptyList(),
        ): UserResponse =
            UserResponse(
                id = user.id.toString(),
                email = user.email,
                emailVerified = user.emailVerifiedAt != null,
                phoneVerified = user.phoneVerifiedAt != null,
                hasPassword = user.hasPassword,
                identities = identities.map(UserIdentityResponse::of),
                isAdmin = user.isAdmin,
            )
    }
}

/** `UserResponse.identities` 의 항목 하나. 계약 `components/schemas/UserIdentityResponse`. */
data class UserIdentityResponse(
    @get:JsonProperty("provider") val provider: String,
) {
    /** 공개 enum인 [provider]도 래퍼 DTO에서는 길이만 남긴다. */
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

data class RequestPhoneVerificationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("phone_number") val phoneNumber: String,
    ) {
        override fun toString(): String = "RequestPhoneVerificationRequest(...)"
    }

data class ConfirmPhoneVerificationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("code") val code: String,
    ) {
        override fun toString(): String = "ConfirmPhoneVerificationRequest(...)"
    }

data class PhoneVerificationResponse(
    @get:JsonProperty("phone_verified") val phoneVerified: Boolean,
    @get:JsonProperty("granted_credits") val grantedCredits: Int,
)

/** `POST /auth/password` 요청. 계약 `SetPasswordRequest`. */
data class SetPasswordRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("new_password") val newPassword: String,
    ) {
        /** 비밀번호가 로그·오류 메시지로 새지 않게 한다. */
        override fun toString(): String = "SetPasswordRequest(...)"
    }

/** `POST /auth/password-reset/request` 요청. 계약 `PasswordResetRequest`. */
data class PasswordResetRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("email") val email: String,
    ) {
        /** 이메일이 로그·오류 메시지로 새지 않게 한다 — `SignupRequest`와 같은 규약. */
        override fun toString(): String = "PasswordResetRequest(...)"
    }

/** `POST /auth/password-reset/confirm` 요청. 계약 `PasswordResetConfirmRequest`. */
data class PasswordResetConfirmRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("email") val email: String,
        @param:JsonProperty("code") val code: String,
        @param:JsonProperty("new_password") val newPassword: String,
    ) {
        /** 이메일·코드·비밀번호가 로그·오류 메시지로 새지 않게 한다. */
        override fun toString(): String = "PasswordResetConfirmRequest(...)"
    }

/**
 * `POST /auth/me/deletion` 요청. 계약 `DeleteAccountRequest`.
 *
 * [password]는 비밀번호가 있는 계정(`readMe.has_password: true`)에만 필수다 — 소셜 전용
 * 계정은 `null`로 보내도 된다. [confirmation]은 두 경우 모두 필수이며 정확히
 * `"탈퇴합니다"`여야 한다(`DeleteAccountService.CONFIRMATION_PHRASE`).
 */
data class DeleteAccountRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("password") val password: String?,
        @param:JsonProperty("confirmation") val confirmation: String,
    ) {
        /** 비밀번호가 로그·오류 메시지로 새지 않게 한다. */
        override fun toString(): String = "DeleteAccountRequest(...)"
    }

/** 액세스 토큰 응답. 계약 `components/schemas/TokenResponse`. */
data class TokenResponse(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("token_type") val tokenType: String,
    @get:JsonProperty("expires_in") val expiresIn: Long,
) {
    override fun toString(): String = "TokenResponse(tokenType=$tokenType, expiresIn=$expiresIn)"
}

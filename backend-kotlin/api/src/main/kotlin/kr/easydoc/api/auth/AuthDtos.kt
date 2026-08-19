package kr.easydoc.api.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.user.User

/**
 * `/auth` 세 경로의 요청·응답 본문.
 *
 * ## 필드 이름을 명시한다
 *
 * 계약이 모든 JSON 필드를 **snake_case** 로 못박았다(`info.description`). Jackson 의
 * 기본 이름 결정은 Kotlin 프로퍼티 이름을 그대로 쓰므로 `accessToken` 이 그대로 나가
 * 계약 위반이 된다. 전역 네이밍 전략을 켜지 않고 **필드마다 이름을 적는 이유**는,
 * 전략은 클래스가 하나 늘 때 조용히 적용 범위 밖으로 새지만 애너테이션은 그 자리에
 * 남기 때문이다.
 *
 * `@JsonCreator` 를 붙이는 이유: 이 프로젝트는 `jackson-module-kotlin` 을 쓰지 않는다
 * (api 락파일 실측). 파라미터 이름 정보 없이도 생성자 바인딩이 서려면 이름을 명시한
 * 생성자를 지목해야 한다.
 *
 * ## 다섯 요청 필드에 Bean Validation 을 달지 않는다 (계약 F3)
 *
 * `SignupRequest.email`·`.password` 는 계약 `x-request-field-constraints` 가 지목한
 * 다섯 필드 중 둘이다. `@Size`·`@NotBlank`·`@Email` 을 붙이면 두 가지가 깨진다 —
 * ⑴ 스키마 층은 **원시 값**에 걸리는데 `email` 은 *정규화한 뒤*를 재야 한다,
 * ⑵ 오류 모양이 422 **배열** + 영문 문구가 되는데 계약은 422 **문자열** + 한국어다.
 * 판정은 `application` 의 `CredentialRules` 가 한다.
 *
 * 이 금지는 주석이 아니라 [kr.easydoc.api.RequestFieldConstraintLayerTest] 가 계약 파일을
 * 읽어 상시 확인한다.
 */
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

/**
 * 사용자 공개 표현. 계약 `components/schemas/UserResponse`.
 *
 * **`password_hash` 를 담지 않는다** — 계약이 "절대 포함하지 않는다"로 못박았다.
 * `created_at` 도 담지 않는다: 계약의 `required` 는 `id`·`email` 둘뿐이고, 계약에 없는
 * 필드를 더하는 것도 계약 위반이다(X-E1/E2 는 키 집합을 **정확히** 대조한다).
 */
data class UserResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("email") val email: String,
) {
    /**
     * **이메일을 찍지 않는다.** 형제 요청 DTO 둘(`SignupRequest`·`LoginRequest`)이 같은
     * 이유로 이미 가리고 있는데 응답 DTO 만 빠져 있었다(게이트 23 privacy-gate 3a).
     * `/auth/me` 는 요청마다 이 객체를 만든다.
     *
     * **가리는 것은 `toString()` 뿐이다** — 계약 `UserResponse` 의 `required` 가
     * `id`·`email` 이므로 직렬화 값은 그대로여야 한다. 두 축을 섞지 않는다.
     */
    override fun toString(): String = "UserResponse(id=$id, email=$CONTENT_MASK)"

    companion object {
        fun of(user: User): UserResponse = UserResponse(id = user.id.toString(), email = user.email)
    }
}

/**
 * 액세스 토큰 응답. 계약 `components/schemas/TokenResponse`.
 *
 * 세 필드 전부 snake_case 이며, Jackson 기본 전략이 깨는 이름이 셋 다 여기 있다.
 * `toString()` 을 덮어 토큰이 로그로 새지 않게 한다 — 이 본문이 곧 자격증명이다.
 */
data class TokenResponse(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("token_type") val tokenType: String,
    @get:JsonProperty("expires_in") val expiresIn: Long,
) {
    override fun toString(): String = "TokenResponse(tokenType=$tokenType, expiresIn=$expiresIn)"
}

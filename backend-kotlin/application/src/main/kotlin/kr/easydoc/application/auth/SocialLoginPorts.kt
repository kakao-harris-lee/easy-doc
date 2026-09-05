package kr.easydoc.application.auth

import java.time.Duration
import java.util.UUID

// 소셜 로그인(Google, backlog §1.4) 유스케이스가 바깥 세계에 요구하는 것들 — [AuthPorts.kt]
// 와 같은 자리다. `SocialLoginService` 는 이 파일의 포트만 알고, 구현은 `infrastructure`
// (Google adapter · JDBC state/identity 저장소)가 준다.

/**
 * 지원하는 소셜 로그인 제공자. **오늘은 `google`·`kakao` 둘이다**(계약 2.13.0) — 계약
 * `paths./auth/oauth/{provider}/start.parameters[provider].schema.enum` 이 `[google,
 * kakao]` 를 연다. `naver` 는 여전히 예약만 됐고 이 enum 에 없다.
 *
 * **문자열 → enum 해석은 이 타입이 하지 않는다.** API 경계의
 * `kr.easydoc.api.auth.SocialLoginProviderIdConverter`(Spring `Converter`)가 그 자리다 —
 * enum 밖 값(예약된 값 포함, 그 밖 전부)은 스키마 층 422 **배열**로 나가야 한다
 * (`ValueSlotInvariantReachTest`, `ExportFormatConverter` 와 같은 자리). 이 서비스
 * (`SocialLoginService`)는 이미 해석된 [SocialLoginProviderId] 만 받는다 — 원시 문자열을
 * 다시 파싱하지 않는다.
 */
enum class SocialLoginProviderId(val wireValue: String) {
    GOOGLE("google"),
    KAKAO("kakao"),
}

/** 제공자가 돌려준 신원 — ID 토큰 검증까지 마친 뒤의 결과. */
data class SocialIdentity(
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
) {
    /** **이메일을 찍지 않는다.** `User`·`Workspace` 와 같은 이유(개인정보). */
    override fun toString(): String = "SocialIdentity(providerUserId=$providerUserId, emailVerified=$emailVerified)"
}

/**
 * 제공자 하나(예: Google)와의 OAuth Authorization Code 왕복. `infrastructure` 의 어댑터가
 * 구현한다. 벤더 SDK 타입을 이 인터페이스 밖으로 내지 않는다(CLAUDE.md 아키텍처 규칙).
 */
interface SocialLoginProvider {
    /** 이 제공자에 등록된(허용된) redirect_uri 인지. 허용 목록은 어댑터가 소유한 설정이다. */
    fun supportsRedirectUri(redirectUri: String): Boolean

    /** 사용자를 보낼 제공자 인가 URL을 만든다. `state`·`nonce` 는 호출자가 미리 발급한 값이다. */
    fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String

    /**
     * 인가 코드를 토큰으로 교환하고 ID 토큰을 검증해 신원을 낸다.
     *
     * 실패는 예외로만 알린다 — 반환값에 성공/실패 플래그를 섞지 않는다(CLAUDE.md
     * "Map, Any, boolean flag 조합을 공개 경계에 사용하지 않는다"):
     *   - 코드가 거절됐다(잘못됨·만료·재사용) 또는 ID 토큰이 무효(서명·`aud`·`nonce`·`iss`
     *     불일치) → [kr.easydoc.core.exceptions.InvalidCredentialsException]
     *   - 제공자에 닿지 못했다(타임아웃·연결 실패·5xx) →
     *     [kr.easydoc.core.exceptions.ExternalServiceUnavailableException]
     */
    fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity
}

/** [OAuthStateStore.issue] 가 돌려주는 한 쌍 — CSRF 방지용 `state`, 리플레이 방지용 `nonce`. */
data class OAuthChallenge(
    val state: String,
    val nonce: String,
)

/**
 * OAuth `state`·`nonce` 저장소. 여러 API 인스턴스가 상태를 공유해야 하므로(계약 설계
 * 결정) 인메모리가 아니라 DB(`oauth_states`, `V6__user_identities.sql`)에 둔다.
 *
 * **`userId` 가 로그인·연결 두 흐름을 가른다**(V8, backlog §1.4 명시적 연결). 로그인
 * 흐름(`SocialLoginService.start`/`callback`)은 `null`을 쓴다 — 아직 아무도 인증되지
 * 않은 채로 발급되는 state 다. 연결 흐름(`linkStart`/`linkCallback`)은 요청한 사용자의
 * id 를 싣는다 — `linkCallback`이 그 값과 Bearer 토큰의 사용자를 대조해 다르면(또는
 * `null`이면, 즉 로그인 state 가 연결 콜백에 잘못 왔다면) 400 으로 거절한다. 반대로
 * 연결 state 가 로그인 콜백에 오면 `boundUserId != null` 로 같은 방식으로 걸린다.
 */
interface OAuthStateStore {
    /**
     * `provider`+`redirect_uri` 에 묶인 새 challenge 를 발급한다. `ttl` 이 지나면 무효다.
     * [userId] 는 연결 흐름 전용이다 — 로그인 흐름은 생략하고(`null`) 부른다.
     */
    fun issue(
        provider: SocialLoginProviderId,
        redirectUri: String,
        ttl: Duration,
        userId: UUID? = null,
    ): OAuthChallenge

    /**
     * 단발 소비. 유효(존재·미만료·미사용·`provider`+`redirect_uri` 일치)하면 발급 당시
     * `nonce` 와 [ConsumedOAuthState.boundUserId] 를 돌려주며 **그 자리에서 소진한다**
     * (재호출은 항상 `null`). 그 밖은 전부 `null` — 사유를 구분하지 않는다(어느 사유든
     * 같은 400). `boundUserId` 검증(로그인/연결 흐름 판정, 호출자 일치)은 이 저장소가
     * 아니라 `SocialLoginService` 가 한다 — 저장소는 발급 당시 값을 그대로 돌려줄 뿐이다.
     */
    fun consume(
        provider: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): ConsumedOAuthState?
}

/** [OAuthStateStore.consume] 의 결과 — 단발 소비된 `nonce` 와 발급 당시 바인딩된 사용자. */
data class ConsumedOAuthState(
    val nonce: String,
    val boundUserId: UUID?,
)

/** 사용자와 소셜 신원의 연결 한 건. */
data class UserIdentity(
    val id: UUID,
    val userId: UUID,
    val provider: SocialLoginProviderId,
    val providerUserId: String,
)

/** `user_identities` 저장소. */
interface UserIdentityRepository {
    /** `(provider, provider_user_id)` 로 찾는다 — 계약 `unique(provider, provider_user_id)`. */
    fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity?

    /**
     * 이 사용자가 이 제공자에 이미 연결한 신원이 있는지 본다 — 사용자당 제공자 하나
     * 불변식(`SocialLoginService.linkCallback`, backlog §1.4 명시적 연결). 있으면
     * 두 번째 연결 시도는 409 다.
     */
    fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity?

    /** 이 사용자가 연결한 신원 전체 — `readMe.identities` (backlog §1.4 명시적 연결). */
    fun findAllByUser(userId: UUID): List<UserIdentity>

    /** 기존 사용자에 새 신원을 연결한다. 가입 트랜잭션 안에서 사용자 생성과 함께 불린다. */
    fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity
}

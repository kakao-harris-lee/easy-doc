package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.EmailVerificationService
import kr.easydoc.application.auth.OAuthStateStore
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserIdentityRepository
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.auth.VerificationCodeStore
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.mail.MailSender
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.auth.google.GoogleOAuthSettings
import kr.easydoc.infrastructure.auth.google.GoogleSocialLoginProvider
import kr.easydoc.infrastructure.auth.kakao.KakaoOAuthSettings
import kr.easydoc.infrastructure.auth.kakao.KakaoSocialLoginProvider
import kr.easydoc.infrastructure.auth.naver.NaverOAuthSettings
import kr.easydoc.infrastructure.auth.naver.NaverSocialLoginProvider
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

// 인증 조립 — **이 모듈이 소유한다.**
//
// `LlmProviderConfiguration` 과 같은 자리이고 이유도 같다: 설정값과 구현 클래스를 함께
// 볼 수 있는 모듈이 `infrastructure` 하나뿐이다. `api`·`worker` 는
// `runtimeOnly(project(":infrastructure"))` 라 `JwtAccessTokens` 타입을 컴파일 시점에
// 보지 못하고, `application` 은 `infrastructure` 를 아예 의존하지 않는다.
//
// 그래서 설정 바인딩도 여기 있다. 종전에 `api` 의 `EasyDocProperties.AuthProperties` 에
// 있던 `easydoc.auth.*` 는 **읽는 쪽과 쓰는 쪽이 서로를 볼 수 없어 아무도 조립할 수 없는
// 설정**이었고, 그 자리를 이 파일이 넘겨받는다(YAML 키와 환경변수 이름은 그대로다).
// 같은 접두사를 두 곳에 두면 소유자가 둘이 되므로 `EasyDocProperties` 쪽에서는 지운다.

/** 인증 설정. 바인딩 접두사는 `easydoc.auth`. */
@ConfigurationProperties(prefix = "easydoc.auth")
data class AuthProperties(
    val jwtSecret: Secret = Secret.EMPTY,
    val jwtExpireMinutes: Long = 60,
    val minSecretBytes: Int = 32,
    val maxConcurrentHashes: Int = 4,
    val maxHashWaitMillis: Long = 250,
    val argon2: Argon2Properties = Argon2Properties(),
)

/** Argon2id 파라미터. 기본값은 계약 `x-auth.password_hash` 가 적은 조합이다. */
data class Argon2Properties(
    val memoryKib: Int = 65536,
    val iterations: Int = 3,
    val parallelism: Int = 4,
    val saltLength: Int = 16,
    val hashLength: Int = 32,
)

/**
 * 이메일 인증 설정. 바인딩 접두사는 `easydoc.email-verification`.
 *
 * 값 셋(TTL·쿨다운·시도 상한)이 운영 중 바뀔 수 있어 코드 상수가 아니라 구성값이다
 * (프로젝트 `CLAUDE.md` 「상수와 구성 관리」). 기본값은 위임 지침의 설계 결정과 같다 —
 * 10분 TTL, 60초 재발송 쿨다운, 5회 오답 상한.
 */
@ConfigurationProperties(prefix = "easydoc.email-verification")
data class EmailVerificationProperties(
    val codeTtlMinutes: Long = 10,
    val resendCooldownSeconds: Long = 60,
    val maxAttempts: Int = 5,
)

/** 소셜 로그인 공통 설정(제공자를 가리지 않는다). 바인딩 접두사는 `easydoc.oauth`. */
@ConfigurationProperties(prefix = "easydoc.oauth")
data class OAuthProperties(
    /** `state`·`nonce` 의 유효 기간(분). backlog §1.4 설계 결정 — 10분. */
    val stateTtlMinutes: Long = 10,
)

/**
 * Google OAuth 설정. 바인딩 접두사는 `easydoc.oauth.google`.
 *
 * **`clientId`·`clientSecret` 이 비어 있으면 google provider 는 등록되지 않는다**
 * ([socialLoginProviders]) — 키 없는 로컬 개발 기동을 막지 않는다(계약 결정, backlog §1.4).
 * `redirectUris` 필드 이름은 환경변수 `EASYDOC_OAUTH_GOOGLE_REDIRECT_URIS` 와 맞춘 것이다
 * (relaxed binding: `redirect-uri-allowlist` 였다면 `_ALLOWLIST` 접미사가 붙었을 것).
 */
@ConfigurationProperties(prefix = "easydoc.oauth.google")
data class GoogleOAuthProperties(
    val clientId: String = "",
    val clientSecret: Secret = Secret.EMPTY,
    /**
     * 콤마로 구분한 목록. 로컬 기본값은 프런트 개발 서버의 콜백 경로 **둘**이다 — 로그인
     * (`oauthStart`/`oauthCallback`)이 쓰는 `/auth/google/callback` 과 명시적 연결
     * (`oauthLinkStart`/`oauthLinkCallback`, 2.10.0, backlog §1.4)이 쓰는
     * `/auth/google/link/callback`. 이 값이 비어 있는(로컬 기본값만 쓰는) 배포에서
     * 연결 콜백이 여기 없으면 `oauthLinkStart` 가 422 `redirect_uri_not_allowed` 로
     * 거절한다 — 두 콜백 다 이 배포의 SPA 가 실제로 리디렉션하는 경로이므로 기본값이
     * 둘 다 담아야 한다(리뷰 후속 조치).
     */
    val redirectUris: List<String> = listOf(DEFAULT_LOGIN_REDIRECT_URI, DEFAULT_LINK_REDIRECT_URI),
    /**
     * JWKS(서명 검증 키) 캐시 TTL(분). 콜백마다 새로 받으면 Google 에 불필요한 부하를
     * 준다 — [kr.easydoc.infrastructure.auth.google.GoogleSocialLoginProvider] 가 이
     * 기간 동안은 캐시를 쓰고, 모르는 `kid`(키 회전)를 만나면 만료 전이라도 즉시 한 번
     * 다시 받는다.
     */
    val jwksCacheMinutes: Long = DEFAULT_JWKS_CACHE_MINUTES,
    /**
     * 토큰 교환·JWKS 호출의 연결·읽기 타임아웃(ms) — 하나의 손잡이로 묶는다
     * (`MailProperties.timeoutMs` 와 같은 방침, CLAUDE.md 「상수와 구성 관리」). 이전에는
     * `GoogleSocialLoginProvider` 가 이 값을 코드 상수(`GOOGLE_CONNECT_TIMEOUT`·
     * `GOOGLE_READ_TIMEOUT`)로만 가졌다 — 운영 중 조정할 방법이 없었다(리뷰 후속 조치,
     * 2026-09-05). 기본값은 그 상수 중 더 큰 쪽([kr.easydoc.infrastructure.auth.google.GOOGLE_READ_TIMEOUT])과 같다.
     */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun isConfigured(): Boolean = clientId.isNotBlank() && !clientSecret.isBlank()

    companion object {
        const val DEFAULT_LOGIN_REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val DEFAULT_LINK_REDIRECT_URI = "http://localhost:5173/auth/google/link/callback"
        private const val DEFAULT_JWKS_CACHE_MINUTES = 60L
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/**
 * 카카오 OAuth 설정. 바인딩 접두사는 `easydoc.oauth.kakao` — [GoogleOAuthProperties] 와
 * 같은 계약(키 없으면 [socialLoginProviders] 가 등록하지 않는다, 기본 redirect_uri 는
 * 로그인·연결 콜백 둘 다 담는다).
 */
@ConfigurationProperties(prefix = "easydoc.oauth.kakao")
data class KakaoOAuthProperties(
    val clientId: String = "",
    val clientSecret: Secret = Secret.EMPTY,
    val redirectUris: List<String> = listOf(DEFAULT_LOGIN_REDIRECT_URI, DEFAULT_LINK_REDIRECT_URI),
    val jwksCacheMinutes: Long = DEFAULT_JWKS_CACHE_MINUTES,
    /** [GoogleOAuthProperties.timeoutMs] 와 같은 계약 — 그 필드 KDoc. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun isConfigured(): Boolean = clientId.isNotBlank() && !clientSecret.isBlank()

    companion object {
        const val DEFAULT_LOGIN_REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
        const val DEFAULT_LINK_REDIRECT_URI = "http://localhost:5173/auth/kakao/link/callback"
        private const val DEFAULT_JWKS_CACHE_MINUTES = 60L
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/**
 * 네이버 OAuth 설정. 바인딩 접두사는 `easydoc.oauth.naver` — [GoogleOAuthProperties] ·
 * [KakaoOAuthProperties] 와 같은 계약(키 없으면 [socialLoginProviders] 가 등록하지
 * 않는다, 기본 redirect_uri 는 로그인·연결 콜백 둘 다 담는다). 네이버는 OIDC 가 없어
 * JWKS 캐시가 필요 없다 — 그래서 `jwksCacheMinutes` 필드가 없다(구글·카카오와의
 * 유일한 구조적 차이, `NaverSocialLoginProvider` KDoc).
 */
@ConfigurationProperties(prefix = "easydoc.oauth.naver")
data class NaverOAuthProperties(
    val clientId: String = "",
    val clientSecret: Secret = Secret.EMPTY,
    val redirectUris: List<String> = listOf(DEFAULT_LOGIN_REDIRECT_URI, DEFAULT_LINK_REDIRECT_URI),
    /** [GoogleOAuthProperties.timeoutMs] 와 같은 계약 — 그 필드 KDoc. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun isConfigured(): Boolean = clientId.isNotBlank() && !clientSecret.isBlank()

    companion object {
        const val DEFAULT_LOGIN_REDIRECT_URI = "http://localhost:5173/auth/naver/callback"
        const val DEFAULT_LINK_REDIRECT_URI = "http://localhost:5173/auth/naver/link/callback"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/**
 * 인증 빈 조립.
 *
 * `TooManyFunctions` 를 억제한다: 조립 지점의 함수 수는 이 클래스의 복잡도가 아니라
 * **협력자의 수**다 — `DocumentConfiguration` 과 같은 근거(그 클래스 KDoc). 억제는 이
 * 클래스 하나에 걸리고 도메인·유스케이스 코드로 번지지 않는다.
 */
@Suppress("TooManyFunctions")
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {
    @Bean
    fun passwordHasher(properties: AuthProperties): PasswordHasher =
        Argon2PasswordHasher(
            policy =
                Argon2Policy(
                    variant = "argon2id",
                    version = ARGON2_VERSION_13,
                    memoryKib = properties.argon2.memoryKib,
                    iterations = properties.argon2.iterations,
                    parallelism = properties.argon2.parallelism,
                    saltLength = properties.argon2.saltLength,
                    hashLength = properties.argon2.hashLength,
                ),
            maxConcurrentHashes = properties.maxConcurrentHashes,
            maxWaitMillis = properties.maxHashWaitMillis,
        )

    /**
     * `Clock.systemUTC()` 를 쓴다. 만료 판정에 허용 오차가 없으므로(계약
     * `x-auth.clock_skew_seconds`) **서버 시계는 NTP 동기를 전제한다** — 계약이 그 전제를
     * 명시했다. 시계를 주입 가능하게 두는 것은 테스트가 만료 경계를 재기 위해서다.
     */
    @Bean
    fun accessTokens(properties: AuthProperties): AccessTokens =
        JwtAccessTokens(
            secret = properties.jwtSecret,
            lifetime = Duration.ofMinutes(properties.jwtExpireMinutes),
            minSecretBytes = properties.minSecretBytes,
            clock = Clock.systemUTC(),
        )

    @Bean
    fun userRepository(jdbcClient: JdbcClient): UserRepository = JdbcUserRepository(jdbcClient)

    @Bean
    fun workspaceRepository(jdbcClient: JdbcClient): WorkspaceRepository = JdbcWorkspaceRepository(jdbcClient)

    @Bean
    fun transactionRunner(transactionManager: PlatformTransactionManager): TransactionRunner =
        SpringTransactionRunner(TransactionTemplate(transactionManager))

    /** 조립 지점의 매개변수 수는 협력자의 수다 — 클래스 KDoc과 같은 근거로 억제한다. */
    @Suppress("LongParameterList")
    @Bean
    fun authService(
        users: UserRepository,
        workspaces: WorkspaceRepository,
        passwordHasher: PasswordHasher,
        accessTokens: AccessTokens,
        transactionRunner: TransactionRunner,
        emailVerification: EmailVerificationService,
    ): AuthService =
        AuthService(
            users = users,
            workspaces = workspaces,
            passwords = passwordHasher,
            accessTokens = accessTokens,
            transaction = transactionRunner,
            emailVerification = emailVerification,
        )

    @Bean
    fun verificationCodeStore(jdbcClient: JdbcClient): VerificationCodeStore =
        JdbcVerificationCodeStore(jdbcClient, Clock.systemUTC())

    @Bean
    fun emailVerificationService(
        users: UserRepository,
        codes: VerificationCodeStore,
        mailSender: MailSender,
        properties: EmailVerificationProperties,
    ): EmailVerificationService =
        EmailVerificationService(
            users = users,
            codes = codes,
            mail = mailSender,
            codeTtl = Duration.ofMinutes(properties.codeTtlMinutes),
            resendCooldown = Duration.ofSeconds(properties.resendCooldownSeconds),
            maxAttempts = properties.maxAttempts,
        )

    @Bean
    fun oauthStateStore(jdbcClient: JdbcClient): OAuthStateStore = JdbcOAuthStateStore(jdbcClient, Clock.systemUTC())

    @Bean
    fun userIdentityRepository(jdbcClient: JdbcClient): UserIdentityRepository = JdbcUserIdentityRepository(jdbcClient)

    /**
     * 등록된 소셜 로그인 제공자 — google·kakao·naver 각각 키가 있을 때만 등록한다(전부
     * 없으면 빈 맵, 그 provider 를 요청하면 `SocialLoginService` 가 422 로 응답한다).
     */
    @Suppress("LongParameterList")
    @Bean
    fun socialLoginProviders(
        google: GoogleOAuthProperties,
        kakao: KakaoOAuthProperties,
        naver: NaverOAuthProperties,
    ): Map<SocialLoginProviderId, SocialLoginProvider> =
        buildMap {
            if (google.isConfigured()) {
                put(
                    SocialLoginProviderId.GOOGLE,
                    GoogleSocialLoginProvider(
                        GoogleOAuthSettings(
                            clientId = google.clientId,
                            clientSecret = google.clientSecret,
                            redirectUriAllowlist = google.redirectUris.toSet(),
                            jwksCacheTtl = Duration.ofMinutes(google.jwksCacheMinutes),
                            connectTimeout = Duration.ofMillis(google.timeoutMs),
                            readTimeout = Duration.ofMillis(google.timeoutMs),
                        ),
                    ),
                )
            }
            if (kakao.isConfigured()) {
                put(
                    SocialLoginProviderId.KAKAO,
                    KakaoSocialLoginProvider(
                        KakaoOAuthSettings(
                            clientId = kakao.clientId,
                            clientSecret = kakao.clientSecret,
                            redirectUriAllowlist = kakao.redirectUris.toSet(),
                            jwksCacheTtl = Duration.ofMinutes(kakao.jwksCacheMinutes),
                            connectTimeout = Duration.ofMillis(kakao.timeoutMs),
                            readTimeout = Duration.ofMillis(kakao.timeoutMs),
                        ),
                    ),
                )
            }
            if (naver.isConfigured()) {
                put(
                    SocialLoginProviderId.NAVER,
                    NaverSocialLoginProvider(
                        NaverOAuthSettings(
                            clientId = naver.clientId,
                            clientSecret = naver.clientSecret,
                            redirectUriAllowlist = naver.redirectUris.toSet(),
                            connectTimeout = Duration.ofMillis(naver.timeoutMs),
                            readTimeout = Duration.ofMillis(naver.timeoutMs),
                        ),
                    ),
                )
            }
        }

    /** `SocialLoginService` 생성자 매개변수 상한을 지키려고 저장소 셋을 묶는다(그 클래스 KDoc). */
    @Bean
    fun socialLoginRepositories(
        users: UserRepository,
        identities: UserIdentityRepository,
        workspaces: WorkspaceRepository,
    ): SocialLoginRepositories = SocialLoginRepositories(users, identities, workspaces)

    /** 조립 지점의 매개변수 수는 협력자의 수다 — 클래스 KDoc과 같은 근거로 억제한다. */
    @Suppress("LongParameterList")
    @Bean
    fun socialLoginService(
        providers: Map<SocialLoginProviderId, SocialLoginProvider>,
        states: OAuthStateStore,
        repositories: SocialLoginRepositories,
        accessTokens: AccessTokens,
        transactionRunner: TransactionRunner,
        oauthProperties: OAuthProperties,
        emailVerification: EmailVerificationService,
    ): SocialLoginService =
        SocialLoginService(
            providers = providers,
            states = states,
            repositories = repositories,
            accessTokens = accessTokens,
            transaction = transactionRunner,
            stateTtl = Duration.ofMinutes(oauthProperties.stateTtlMinutes),
            emailVerification = emailVerification,
        )

    private companion object {
        /** `Argon2Parameters.ARGON2_VERSION_13`. 인코더가 만드는 PHC 의 `v=` 값이다. */
        const val ARGON2_VERSION_13 = 19
    }
}

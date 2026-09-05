package kr.easydoc.infrastructure.auth.google

import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.auth.oidc.OidcJwksVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration

/**
 * Google OAuth 2.0 Authorization Code 어댑터 — `SocialLoginProvider` 의 Google 구현.
 *
 * 벤더 SDK 를 쓰지 않는다(CLAUDE.md·`LlmProviderConfiguration` 과 같은 방침) — 토큰 교환은
 * 평범한 form-urlencoded POST 다. ID 토큰 검증(서명·`iss`/`aud`/`exp`/`nonce`·JWKS 캐시)은
 * [OidcJwksVerifier] 에 위임한다 — `KakaoSocialLoginProvider` 와 그 로직을 그대로
 * 복제하고 있던 것을 리뷰 지적으로 뽑아냈다(그 클래스 KDoc).
 *
 * **엔드포인트 URL 은 설정으로 열지 않는다** — `OpenAiProvider`·`AnthropicProvider` 와 같은
 * 이유(호출 대상을 운영자가 임의 호스트로 바꿀 수 있는 설정 표면을 만들지 않는다). 기본값
 * (Google 프로토콜 불변식)을 그대로 쓰고, 테스트만 생성자로 다른 값을 넣는다.
 */
class GoogleSocialLoginProvider(
    private val settings: GoogleOAuthSettings,
    clock: Clock = Clock.systemUTC(),
) : SocialLoginProvider {
    private val log = LoggerFactory.getLogger(GoogleSocialLoginProvider::class.java)
    private val json = JsonMapper.builder().build()

    private val jwksVerifier =
        OidcJwksVerifier(
            jwksUri = settings.jwksUri,
            issuers = GOOGLE_ISSUERS,
            audience = settings.clientId,
            jwksCacheTtl = settings.jwksCacheTtl,
            providerLabel = PROVIDER_LABEL,
            clock = clock,
            connectTimeout = settings.connectTimeout,
            readTimeout = settings.readTimeout,
        )

    private val client: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build(),
                ).apply { setReadTimeout(settings.readTimeout) },
            ).build()

    override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri in settings.redirectUriAllowlist

    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String {
        val query =
            listOf(
                "client_id" to settings.clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to "openid email",
                "state" to state,
                "nonce" to nonce,
            ).joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        return "${settings.authorizationEndpoint}?$query"
    }

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity = verifyIdToken(idTokenFrom(tokenResponseBody(code, redirectUri)), nonce)

    // ------------------------------------------------------------------ ① 토큰 교환

    private fun tokenResponseBody(
        code: String,
        redirectUri: String,
    ): String =
        try {
            rawTokenBody(code, redirectUri)
        } catch (_: HttpClientErrorException) {
            // Google 은 잘못된/만료된/재사용된 코드에 400 `invalid_grant` 를 낸다.
            // 벤더 오류 텍스트를 그대로 내보내지 않는다 — 로그인 실패와 같은 문구다.
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        } catch (exc: RestClientException) {
            // 5xx·타임아웃·연결 실패 전부 여기로 온다(`HttpServerErrorException`·
            // `ResourceAccessException` 모두 `RestClientException` 하위).
            throw unreachable(exc::class.java.simpleName)
        }

    private fun rawTokenBody(
        code: String,
        redirectUri: String,
    ): String {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("code", code)
                add("client_id", settings.clientId)
                add("client_secret", settings.clientSecret.reveal())
                add("redirect_uri", redirectUri)
                add("grant_type", "authorization_code")
            }
        return client
            .post()
            .uri(settings.tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(ByteArray::class.java)
            ?.toString(StandardCharsets.UTF_8)
            ?: throw unreachable("응답 본문 없음")
    }

    private fun idTokenFrom(rawJson: String): String {
        val node =
            try {
                json.readTree(rawJson)
            } catch (exc: JacksonException) {
                throw unreachable("토큰 응답 형식 오류: ${exc::class.java.simpleName}")
            }
        return node.path("id_token").stringValue("").ifEmpty { throw unreachable("id_token 없음") }
    }

    // ------------------------------------------------------------------ ② ID 토큰 검증

    private fun verifyIdToken(
        idToken: String,
        expectedNonce: String,
    ): SocialIdentity {
        val claims = jwksVerifier.verify(idToken, expectedNonce)
        return SocialIdentity(
            providerUserId = claims.sub,
            email = claims.email,
            emailVerified = claims.emailVerified,
        )
    }

    /**
     * `detail` 은 **로그에만** 남는다. `ExternalServiceUnavailableException` 은
     * `GlobalExceptionHandler.mappingFor` 에 명시적으로 매핑돼 있어(502) 그 예외의
     * `message` 가 **그대로** 응답 `detail` 로 나간다 — 매핑되지 않은 도메인 예외처럼
     * 고정 문구로 대체되지 않는다. 그래서 예외 메시지 자체를 계약 고정 문구
     * ([PROVIDER_UNREACHABLE_MESSAGE])로 두고, 진단 정보(HTTP 상태·예외 타입 이름)는
     * 별도 로그 한 줄로만 남긴다 — 벤더 오류 텍스트가 응답에 실리지 않는다.
     */
    private fun unreachable(detail: String): ExternalServiceUnavailableException {
        log.warn("구글 소셜 로그인 제공자에 닿지 못했다: {}", detail)
        return ExternalServiceUnavailableException(PROVIDER_UNREACHABLE_MESSAGE)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")

        /** [OidcJwksVerifier] 의 로그·불통 메시지에 쓰이는 표식 — "구글에 연결하지 못했습니다". */
        const val PROVIDER_LABEL = "구글"

        /** 계약 401 예시와 같은 값(재사용 — `x-auth` 401 두 갈래 불변식을 지킨다). */
        const val CODE_REJECTED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        /** 계약 `components/responses/BadGateway` 의 `provider_unreachable` 예시와 같은 값. */
        const val PROVIDER_UNREACHABLE_MESSAGE = "구글에 연결하지 못했습니다"
    }
}

/** [GoogleSocialLoginProvider] 설정. 엔드포인트 기본값은 Google 프로토콜 불변식 — 테스트만 바꾼다. */
data class GoogleOAuthSettings(
    val clientId: String,
    val clientSecret: Secret,
    val redirectUriAllowlist: Set<String>,
    val authorizationEndpoint: String = GOOGLE_AUTHORIZATION_ENDPOINT,
    val tokenEndpoint: String = GOOGLE_TOKEN_ENDPOINT,
    val jwksUri: String = GOOGLE_JWKS_URI,
    val connectTimeout: Duration = GOOGLE_CONNECT_TIMEOUT,
    val readTimeout: Duration = GOOGLE_READ_TIMEOUT,
    /** 서명 검증 캐시 TTL. 운영 중 조정될 수 있는 값이라 구성값이다(CLAUDE.md). */
    val jwksCacheTtl: Duration = GOOGLE_JWKS_CACHE_TTL_DEFAULT,
)

const val GOOGLE_AUTHORIZATION_ENDPOINT: String = "https://accounts.google.com/o/oauth2/v2/auth"
const val GOOGLE_TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
const val GOOGLE_JWKS_URI: String = "https://www.googleapis.com/oauth2/v3/certs"

val GOOGLE_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
val GOOGLE_READ_TIMEOUT: Duration = Duration.ofSeconds(15)

/** `easydoc.oauth.google.jwks-cache-minutes` 미설정 시 기본값. */
val GOOGLE_JWKS_CACHE_TTL_DEFAULT: Duration = Duration.ofMinutes(60)

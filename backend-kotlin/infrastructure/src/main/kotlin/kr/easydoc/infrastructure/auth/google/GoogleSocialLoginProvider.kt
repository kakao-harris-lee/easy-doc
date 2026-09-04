package kr.easydoc.infrastructure.auth.google

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
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
import java.security.interfaces.RSAPublicKey
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Google OAuth 2.0 Authorization Code 어댑터 — `SocialLoginProvider` 의 Google 구현.
 *
 * 벤더 SDK 를 쓰지 않는다(CLAUDE.md·`LlmProviderConfiguration` 과 같은 방침) — 토큰 교환은
 * 평범한 form-urlencoded POST, ID 토큰 검증은 Google 공개 JWKS 로 이미 쓰고 있는
 * nimbus-jose-jwt 로 한다(`JwtAccessTokens` 가 같은 라이브러리로 우리 HS256 토큰을 다룬다 —
 * 여기는 Google 이 서명한 RS256 토큰이라 검증기만 다르다).
 *
 * **엔드포인트 URL 은 설정으로 열지 않는다** — `OpenAiProvider`·`AnthropicProvider` 와 같은
 * 이유(호출 대상을 운영자가 임의 호스트로 바꿀 수 있는 설정 표면을 만들지 않는다). 기본값
 * (Google 프로토콜 불변식)을 그대로 쓰고, 테스트만 생성자로 다른 값을 넣는다.
 *
 * **함수를 잘게 쪼갠 이유(detekt `ThrowsCount`)**: 실패 갈래가 많은 검증 로직을 한 함수에
 * 모으면 함수당 던지는 예외 수 상한(2)을 넘는다. 각 함수가 "한 가지를 확인하고 실패하면
 * 던진다"로 좁아지도록 나눴다 — 부수 효과로 각 갈래에 이름이 붙어 읽기도 쉬워졌다.
 * 그 결과 `TooManyFunctions` 도 억제한다 — 늘어난 것은 책임이 아니라 실패 갈래마다 붙은
 * 이름이다(`DocumentConfiguration`·`AuthConfiguration` 과 같은 "억제는 이 클래스 하나"
 * 원칙).
 */
@Suppress("TooManyFunctions")
class GoogleSocialLoginProvider(
    private val settings: GoogleOAuthSettings,
    private val clock: Clock = Clock.systemUTC(),
) : SocialLoginProvider {
    private val log = LoggerFactory.getLogger(GoogleSocialLoginProvider::class.java)
    private val json = JsonMapper.builder().build()

    /**
     * JWKS 캐시 — [signingKeyFor] 만 읽고 쓴다. `cacheLock` 은 갱신 자리에서만 잠근다:
     * 캐시가 신선하고 아는 `kid` 면 잠금 없이 바로 돌려준다(콜백마다 매번 도는 자리라
     * 잠금 경합을 피한다). 잠금 안에서 조건을 **다시** 확인하는 이유는 여러 스레드가
     * 동시에 캐시 미스를 만나면 그중 하나만 실제로 받아 오게 하려는 것이다 — 다른
     * 스레드들은 잠금을 얻은 뒤 그 스레드가 이미 채운 결과를 본다.
     */
    private val cacheLock = Any()

    @Volatile private var cachedKeys: Map<String, RSAKey> = emptyMap()

    @Volatile private var cachedAt: Instant = Instant.EPOCH

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
        val jwt = parseSignedJwt(idToken)
        requireValidSignature(jwt)
        return verifiedIdentity(parsedClaims(jwt), expectedNonce)
    }

    private fun parseSignedJwt(idToken: String): SignedJWT =
        try {
            SignedJWT.parse(idToken)
        } catch (_: ParseException) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }

    private fun requireValidSignature(jwt: SignedJWT) {
        val key = signingKeyFor(jwt.header.keyID)
        val signatureValid =
            try {
                jwt.verify(RSASSAVerifier(key))
            } catch (_: JOSEException) {
                false
            }
        if (!signatureValid) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }
    }

    private fun parsedClaims(jwt: SignedJWT): JWTClaimsSet =
        try {
            jwt.jwtClaimsSet
        } catch (_: ParseException) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }

    /**
     * `sub`·`exp` 존재, `iss`·`aud`·`exp`·`nonce` 일치를 한 번에 확인한다.
     *
     * 여섯 조건을 한 `if` 로 묶어 `ComplexCondition` 을 억제한다 — 쪼개면 각 조각이 검증
     * 결과를 함수 경계 밖으로 넘겨야 하고, 그러면 `subject` 의 non-null 스마트 캐스트가
     * 깨진다(로컬 `val` 는 같은 식 안에서만 좁혀진다). 조건 하나하나에 이미 이름이
     * 붙어 있다(`sub`·`exp`·`iss`·`aud`·`exp`·`nonce`) — 나누어 얻을 것이 적다.
     */
    @Suppress("ComplexCondition")
    private fun verifiedIdentity(
        claims: JWTClaimsSet,
        expectedNonce: String,
    ): SocialIdentity {
        val subject = claims.subject
        val expiresAt = claims.expirationTime
        if (subject == null ||
            expiresAt == null ||
            claims.issuer !in GOOGLE_ISSUERS ||
            claims.audience?.contains(settings.clientId) != true ||
            !clock.instant().isBefore(expiresAt.toInstant()) ||
            claims.getStringClaim("nonce") != expectedNonce
        ) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }
        return SocialIdentity(
            providerUserId = subject,
            email = claims.getStringClaim("email"),
            emailVerified = claims.getBooleanClaim("email_verified") ?: false,
        )
    }

    /**
     * Google JWKS 에서 토큰이 가리키는 키(`kid`)를 찾는다. [cachedKeysFor] 가 캐시를
     * 다룬다 — 이 함수는 그 결과에서 키 하나를 고르기만 한다.
     */
    private fun signingKeyFor(keyId: String?): RSAPublicKey {
        val keys = cachedKeysFor(keyId)
        val jwk = keyId?.let { keys[it] } ?: keys.values.firstOrNull()
        return jwk?.toRSAPublicKey() ?: throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
    }

    /**
     * 캐시가 신선하고(`jwksCacheTtl` 이내) 이 `kid` 를 안다면 그대로 돌려준다. 아니면
     * (비었거나, 낡았거나, 모르는 `kid` — 키 회전) 새로 받아 **전체를 교체**한다.
     * `keyId` 가 `null` 이면 항상 캐시를 그대로 쓴다(첫 키를 쓰는 갈래라 특정 `kid` 를
     * 모른다는 조건이 성립하지 않는다) — 비어 있을 때만 채운다.
     */
    private fun cachedKeysFor(keyId: String?): Map<String, RSAKey> {
        val snapshot = cachedKeys
        if (isUsable(snapshot, keyId)) return snapshot

        return synchronized(cacheLock) {
            // 잠금을 얻는 사이 다른 스레드가 이미 채웠을 수 있다 — 다시 확인한다.
            val current = cachedKeys
            if (isUsable(current, keyId)) {
                current
            } else {
                val refreshed = jwkSetFrom(fetchJwksRaw()).keys.filterIsInstance<RSAKey>().associateBy { it.keyID }
                cachedKeys = refreshed
                cachedAt = clock.instant()
                refreshed
            }
        }
    }

    private fun isUsable(
        keys: Map<String, RSAKey>,
        keyId: String?,
    ): Boolean =
        keys.isNotEmpty() &&
            Duration.between(cachedAt, clock.instant()) < settings.jwksCacheTtl &&
            (keyId == null || keys.containsKey(keyId))

    private fun fetchJwksRaw(): String =
        try {
            client
                .get()
                .uri(settings.jwksUri)
                .retrieve()
                .body(ByteArray::class.java)
                ?.toString(StandardCharsets.UTF_8)
                ?: throw unreachable("JWKS 응답 없음")
        } catch (exc: RestClientException) {
            throw unreachable(exc::class.java.simpleName)
        }

    private fun jwkSetFrom(raw: String): JWKSet =
        try {
            JWKSet.parse(raw)
        } catch (exc: ParseException) {
            throw unreachable("JWKS 형식 오류: ${exc::class.java.simpleName}")
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

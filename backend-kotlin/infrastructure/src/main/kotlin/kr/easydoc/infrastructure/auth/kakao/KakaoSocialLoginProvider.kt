package kr.easydoc.infrastructure.auth.kakao

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
import tools.jackson.databind.JsonNode
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
 * 카카오 OAuth 2.0 Authorization Code 어댑터 — `SocialLoginProvider` 의 카카오 구현
 * (backlog §1.4, `GoogleSocialLoginProvider` 다음 제공자).
 *
 * **두 경로.** 인가 코드 교환 응답에 `id_token`(OIDC, `scope=openid`) 이 실려 있으면
 * Google 어댑터와 같은 방식(JWKS 서명 검증 · `iss`/`aud`/`exp`/`nonce` 대조)으로 그 자리에서
 * 신원을 낸다. 없으면(카카오 앱이 OIDC 를 켜지 않았거나 스코프에서 `openid` 가 빠진 배포)
 * 액세스 토큰으로 `GET https://kapi.kakao.com/v2/user/me` 를 불러 `kakao_account` 를 읽는다.
 *
 * **이메일은 항상 있다고 가정하지 않는다** — 카카오는 비즈 앱 전환 전에는 이메일 동의
 * 자체를 열 수 없고, 열려 있어도 `is_email_valid`/`is_email_verified` 가 거짓이거나 필드
 * 자체가 없을 수 있다(backlog §1.4 조사). 이 어댑터는 있는 그대로
 * [SocialIdentity.emailVerified] 로 옮길 뿐이다 — "검증된 이메일만 계정을 잇는다"는
 * 판정은 `SocialLoginService.requireVerifiedEmail` 의 몫이다(Google 과 같은 경계).
 * OIDC 경로는 ID 토큰의 `email` claim 이 "이메일 동의 + 유효할 때만" 실린다는 카카오
 * 문서 설명을 따라 그 claim 이 있으면 검증된 것으로 본다 — 카카오 ID 토큰에는 Google
 * 과 달리 별도의 `email_verified` claim 이 없다.
 *
 * 엔드포인트 URL 을 설정으로 열지 않는 이유·`TooManyFunctions` 억제 이유는
 * `GoogleSocialLoginProvider` 와 같다(그 클래스 KDoc) — 실패 갈래마다 이름 붙은 작은
 * 함수가 늘었을 뿐 책임이 갈린 것이 아니다. JWKS 캐시 구현도 그 클래스와 동일한 계약
 * (신선하고 아는 `kid`면 잠금 없이 재사용, 모르는 `kid`를 만나면 정확히 한 번 갱신)이다.
 */
@Suppress("TooManyFunctions")
class KakaoSocialLoginProvider(
    private val settings: KakaoOAuthSettings,
    private val clock: Clock = Clock.systemUTC(),
) : SocialLoginProvider {
    private val log = LoggerFactory.getLogger(KakaoSocialLoginProvider::class.java)
    private val json = JsonMapper.builder().build()

    /** [GoogleSocialLoginProvider] 와 같은 캐시 계약 — 그 클래스 KDoc 참고. */
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
                "scope" to "openid account_email",
                "state" to state,
                "nonce" to nonce,
            ).joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        return "${settings.authorizationEndpoint}?$query"
    }

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity {
        val tokenNode = tokenResponseNode(code, redirectUri)
        val idToken = tokenNode.path("id_token").stringValue("")
        return if (idToken.isNotEmpty()) {
            verifyIdToken(idToken, nonce)
        } else {
            identityFromUserInfo(accessTokenFrom(tokenNode))
        }
    }

    // ------------------------------------------------------------------ ① 토큰 교환

    private fun tokenResponseNode(
        code: String,
        redirectUri: String,
    ): JsonNode = parseJson(tokenResponseBody(code, redirectUri))

    private fun tokenResponseBody(
        code: String,
        redirectUri: String,
    ): String =
        try {
            rawTokenBody(code, redirectUri)
        } catch (_: HttpClientErrorException) {
            // 카카오는 잘못된/만료된/재사용된 코드에 400 을 낸다 — Google 과 같은 갈래.
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        } catch (exc: RestClientException) {
            throw unreachable(exc::class.java.simpleName)
        }

    private fun rawTokenBody(
        code: String,
        redirectUri: String,
    ): String {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", settings.clientId)
                add("client_secret", settings.clientSecret.reveal())
                add("redirect_uri", redirectUri)
                add("code", code)
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

    private fun parseJson(rawJson: String): JsonNode =
        try {
            json.readTree(rawJson)
        } catch (exc: JacksonException) {
            throw unreachable("응답 형식 오류: ${exc::class.java.simpleName}")
        }

    private fun accessTokenFrom(node: JsonNode): String =
        node.path("access_token").stringValue("").ifEmpty { throw unreachable("access_token 없음") }

    // ------------------------------------------------------------------ ② ID 토큰 검증(OIDC)

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
     * `sub`·`exp` 존재, `iss`·`aud`·`exp`·`nonce` 일치를 한 번에 확인한다 —
     * `GoogleSocialLoginProvider.verifiedIdentity` 와 같은 근거(`ComplexCondition` 억제).
     * 카카오 ID 토큰에는 `email_verified` claim 이 없다 — `email` claim 자체가 "이메일
     * 동의 + 유효할 때만" 실린다는 문서 설명을 따라 그 claim 유무로 검증 여부를 가른다.
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
            claims.issuer != KAKAO_ISSUER ||
            claims.audience?.contains(settings.clientId) != true ||
            !clock.instant().isBefore(expiresAt.toInstant()) ||
            claims.getStringClaim("nonce") != expectedNonce
        ) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }
        val email = claims.getStringClaim("email")
        return SocialIdentity(
            providerUserId = subject,
            email = email,
            emailVerified = email != null,
        )
    }

    /**
     * 카카오 JWKS 에서 토큰이 가리키는 키(`kid`)를 찾는다 —
     * `GoogleSocialLoginProvider.signingKeyFor` 와 같은 계약.
     */
    private fun signingKeyFor(keyId: String?): RSAPublicKey {
        val keys = cachedKeysFor(keyId)
        val jwk = keyId?.let { keys[it] } ?: keys.values.firstOrNull()
        return jwk?.toRSAPublicKey() ?: throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
    }

    /** `GoogleSocialLoginProvider.cachedKeysFor` 와 같은 캐시 갱신 계약(그 클래스 KDoc). */
    private fun cachedKeysFor(keyId: String?): Map<String, RSAKey> {
        val snapshot = cachedKeys
        if (isUsable(snapshot, keyId)) return snapshot

        return synchronized(cacheLock) {
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

    // ------------------------------------------------------------------ ③ userinfo 대체 경로

    /**
     * `id_token` 이 없을 때의 대체 경로 — 액세스 토큰으로 `kapi.kakao.com/v2/user/me` 를
     * 불러 `id`·`kakao_account.email`·`is_email_valid`·`is_email_verified` 를 읽는다.
     * 이메일 필드 자체가 없으면(동의 안 함) `email` 은 `null` 이다.
     */
    private fun identityFromUserInfo(accessToken: String): SocialIdentity {
        val node = parseJson(userInfoResponseBody(accessToken))
        val id = node.path("id").longValue(MISSING_ID)
        if (id == MISSING_ID) {
            throw unreachable("id 없음")
        }
        val account = node.path("kakao_account")
        val email = account.path("email").stringValue("").ifEmpty { null }
        val emailVerified =
            account.path("is_email_valid").booleanValue(false) && account.path("is_email_verified").booleanValue(false)
        return SocialIdentity(
            providerUserId = id.toString(),
            email = email,
            emailVerified = emailVerified,
        )
    }

    private fun userInfoResponseBody(accessToken: String): String =
        try {
            client
                .get()
                .uri(settings.userInfoEndpoint)
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .body(ByteArray::class.java)
                ?.toString(StandardCharsets.UTF_8)
                ?: throw unreachable("응답 본문 없음")
        } catch (_: HttpClientErrorException) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        } catch (exc: RestClientException) {
            throw unreachable(exc::class.java.simpleName)
        }

    /** `GoogleSocialLoginProvider.unreachable` 과 같은 계약 — 그 함수 KDoc. */
    private fun unreachable(detail: String): ExternalServiceUnavailableException {
        log.warn("카카오 소셜 로그인 제공자에 닿지 못했다: {}", detail)
        return ExternalServiceUnavailableException(PROVIDER_UNREACHABLE_MESSAGE)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val KAKAO_ISSUER = "https://kauth.kakao.com"

        /** 계약 401 예시와 같은 값(재사용 — `x-auth` 401 두 갈래 불변식을 지킨다). */
        const val CODE_REJECTED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        /** 계약 `components/responses/BadGateway` 의 `provider_unreachable` 예시와 같은 값. */
        const val PROVIDER_UNREACHABLE_MESSAGE = "카카오에 연결하지 못했습니다"

        /** 카카오 회원번호는 항상 양수라 이 값을 "없음" 표식으로 쓴다. */
        const val MISSING_ID = Long.MIN_VALUE
    }
}

/** [KakaoSocialLoginProvider] 설정. 엔드포인트 기본값은 카카오 프로토콜 불변식 — 테스트만 바꾼다. */
data class KakaoOAuthSettings(
    val clientId: String,
    val clientSecret: Secret,
    val redirectUriAllowlist: Set<String>,
    val authorizationEndpoint: String = KAKAO_AUTHORIZATION_ENDPOINT,
    val tokenEndpoint: String = KAKAO_TOKEN_ENDPOINT,
    val jwksUri: String = KAKAO_JWKS_URI,
    val userInfoEndpoint: String = KAKAO_USERINFO_ENDPOINT,
    val connectTimeout: Duration = KAKAO_CONNECT_TIMEOUT,
    val readTimeout: Duration = KAKAO_READ_TIMEOUT,
    /** 서명 검증 캐시 TTL. 운영 중 조정될 수 있는 값이라 구성값이다(CLAUDE.md). */
    val jwksCacheTtl: Duration = KAKAO_JWKS_CACHE_TTL_DEFAULT,
)

/** 카카오 공식 문서(developers.kakao.com) 확인값 — 2026-09-05. */
const val KAKAO_AUTHORIZATION_ENDPOINT: String = "https://kauth.kakao.com/oauth/authorize"
const val KAKAO_TOKEN_ENDPOINT: String = "https://kauth.kakao.com/oauth/token"
const val KAKAO_JWKS_URI: String = "https://kauth.kakao.com/.well-known/jwks.json"
const val KAKAO_USERINFO_ENDPOINT: String = "https://kapi.kakao.com/v2/user/me"

val KAKAO_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
val KAKAO_READ_TIMEOUT: Duration = Duration.ofSeconds(15)

/** `easydoc.oauth.kakao.jwks-cache-minutes` 미설정 시 기본값. */
val KAKAO_JWKS_CACHE_TTL_DEFAULT: Duration = Duration.ofMinutes(60)

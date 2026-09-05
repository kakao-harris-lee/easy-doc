package kr.easydoc.infrastructure.auth.oidc

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.security.interfaces.RSAPublicKey
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * `GoogleSocialLoginProvider`·`KakaoSocialLoginProvider` 가 그대로 복제하고 있던 OIDC ID
 * 토큰 검증 로직 — JWKS 캐시(신선하고 아는 `kid` 면 잠금 없이 재사용, 모르는 `kid`(키 회전)를
 * 만나면 캐시가 만료 전이라도 정확히 한 번 다시 받는다) · 서명 검증 · `iss`/`aud`/`exp`/`nonce`
 * 대조를 한 곳에 모은다(리뷰 지적 — 두 어댑터가 ~150~200줄을 그대로 복제했다). 동작은 두
 * 어댑터가 각자 갖고 있던 것과 같다 — `GoogleSocialLoginProviderTest`·
 * `KakaoSocialLoginProviderTest` 가 그대로 초록이다.
 *
 * **이메일 검증 판정(`emailVerified`)은 두 제공자가 공유할 수 있는 하나의 규칙으로
 * 정리했다**: `email_verified` claim 이 있으면 그 값을 그대로 쓰고, 없으면 `email` claim
 * 유무로 판정한다. Google 은 `email` 이 있을 때 항상 `email_verified` 를 명시적으로 실으므로
 * (Google 문서) 첫 갈래만 타 기존 동작과 같고, 카카오는 애초에 `email_verified` claim
 * 자체가 없어(어댑터 KDoc) 두 번째 갈래만 타 기존 카카오 동작과 같다 — 두 어댑터 테스트가
 * 이 등가성을 고정한다.
 *
 * `providerLabel` 은 로그와 [ExternalServiceUnavailableException] 메시지에만 쓰인다 —
 * 계약이 고정한 제공자별 문구("구글에 연결하지 못했습니다"·"카카오에 연결하지 못했습니다")를
 * 그대로 재현하기 위한 매개변수화일 뿐, 그 밖의 엔드포인트·스코프·userinfo 매핑·(JWKS 가 아닌
 * 토큰 교환의) 오류 매핑은 여전히 각 어댑터가 갖는다.
 *
 * **`LongParameterList`·`TooManyFunctions` 억제 이유**: 매개변수 8개는 두 어댑터가 각자
 * 갖던 협력자·설정(JWKS URL·발급자 집합·클라이언트 id·캐시 TTL·시계·타임아웃 둘)을 그대로
 * 옮겨 받은 것이고, 함수 11개는 실패 갈래마다 이름 붙은 작은 함수로 나눈 결과다 —
 * `GoogleSocialLoginProvider`(리팩터 이전)·`DocumentConfiguration` 과 같은 "억제는 이
 * 클래스 하나" 원칙.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class OidcJwksVerifier(
    private val jwksUri: String,
    private val issuers: Set<String>,
    private val audience: String,
    private val jwksCacheTtl: Duration,
    private val providerLabel: String,
    private val clock: Clock = Clock.systemUTC(),
    connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) {
    private val log = LoggerFactory.getLogger(OidcJwksVerifier::class.java)

    /** [GoogleSocialLoginProvider] 가 갖고 있던 것과 같은 캐시 계약(이 클래스 KDoc 참고). */
    private val cacheLock = Any()

    @Volatile private var cachedKeys: Map<String, RSAKey> = emptyMap()

    @Volatile private var cachedAt: Instant = Instant.EPOCH

    private val client: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                ).apply { setReadTimeout(readTimeout) },
            ).build()

    /**
     * ID 토큰을 검증하고 신원 claim 을 낸다. 실패는 예외로만 알린다(어댑터
     * `SocialLoginProvider.exchange` 와 같은 계약):
     *   - 서명·`iss`·`aud`·`exp`·`nonce` 불일치 → [InvalidCredentialsException]
     *   - JWKS 엔드포인트에 닿지 못했다 → [ExternalServiceUnavailableException]
     */
    fun verify(
        idToken: String,
        expectedNonce: String,
    ): VerifiedIdClaims {
        val jwt = parseSignedJwt(idToken)
        requireValidSignature(jwt)
        return verifiedClaims(parsedClaims(jwt), expectedNonce)
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
     * `sub`·`exp` 존재, `iss`·`aud`·`exp`·`nonce` 일치를 한 번에 확인한다 — 두 어댑터가
     * 각자 갖고 있던 `verifiedIdentity` 와 같은 근거(`ComplexCondition` 억제, 그 함수들의
     * 옛 KDoc)로 여섯 조건을 한 `if` 로 묶는다.
     */
    @Suppress("ComplexCondition")
    private fun verifiedClaims(
        claims: JWTClaimsSet,
        expectedNonce: String,
    ): VerifiedIdClaims {
        val subject = claims.subject
        val expiresAt = claims.expirationTime
        if (subject == null ||
            expiresAt == null ||
            claims.issuer !in issuers ||
            claims.audience?.contains(audience) != true ||
            !clock.instant().isBefore(expiresAt.toInstant()) ||
            claims.getStringClaim("nonce") != expectedNonce
        ) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        }
        val email = claims.getStringClaim("email")
        val emailVerified = claims.getBooleanClaim("email_verified") ?: (email != null)
        return VerifiedIdClaims(sub = subject, email = email, emailVerified = emailVerified, claims = claims)
    }

    /** JWKS 에서 토큰이 가리키는 키(`kid`)를 찾는다. [cachedKeysFor] 가 캐시를 다룬다. */
    private fun signingKeyFor(keyId: String?): RSAPublicKey {
        val keys = cachedKeysFor(keyId)
        val jwk = keyId?.let { keys[it] } ?: keys.values.firstOrNull()
        return jwk?.toRSAPublicKey() ?: throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
    }

    /**
     * 캐시가 신선하고(`jwksCacheTtl` 이내) 이 `kid` 를 안다면 그대로 돌려준다. 아니면
     * (비었거나, 낡았거나, 모르는 `kid` — 키 회전) 새로 받아 **전체를 교체**한다.
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
            Duration.between(cachedAt, clock.instant()) < jwksCacheTtl &&
            (keyId == null || keys.containsKey(keyId))

    private fun fetchJwksRaw(): String =
        try {
            client
                .get()
                .uri(jwksUri)
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

    private fun unreachable(detail: String): ExternalServiceUnavailableException {
        log.warn("{} 소셜 로그인 제공자에 닿지 못했다: {}", providerLabel, detail)
        return ExternalServiceUnavailableException("${providerLabel}에 연결하지 못했습니다")
    }

    private companion object {
        /** 계약 401 예시와 같은 값(재사용 — `x-auth` 401 두 갈래 불변식을 지킨다). */
        const val CODE_REJECTED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        /** 호출자가 안 주면 쓰는 기본값 — 두 어댑터가 실제로 넘기는 값과는 무관하다. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}

/**
 * [OidcJwksVerifier.verify] 가 검증까지 마친 뒤 돌려주는 값. **`data class` 가 아니다** —
 * `claims`(nimbus `JWTClaimsSet`, 벤더 타입)를 들고 있어 `SensitiveToStringReachTest` 의
 * 자동 탐지기(`GeneratedToStringProbes.slotFor`)가 표본을 만들 수 없는 타입이다.
 * `data class` 로 두면 그 게이트가 이 타입을 "판정 불가"로 실패시킨다. `toString()` 도
 * 재정의하지 않는다 — 재정의하면 "손으로 쓴 toString" 축(R-10)이 같은 이유로 이 타입을
 * 붙잡아 같은 실패를 낸다. 이 타입은 어댑터 내부 전용이라(공개 API·로그 어디에도 노출되지
 * 않는다) 기본 `Any.toString()` 으로도 값이 새지 않는다.
 */
class VerifiedIdClaims(
    val sub: String,
    val email: String?,
    val emailVerified: Boolean,
    private val claims: JWTClaimsSet,
) {
    /** 표준 셋(`sub`·`email`·`email_verified`) 밖의 claim 이 필요할 때만 쓰는 최소 접근자. */
    fun stringClaim(name: String): String? = claims.getStringClaim(name)
}

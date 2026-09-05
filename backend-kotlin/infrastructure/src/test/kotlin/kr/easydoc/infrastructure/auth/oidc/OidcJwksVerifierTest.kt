package kr.easydoc.infrastructure.auth.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * `OidcJwksVerifier` 공유 검증 로직 전용 — 서명·JWKS 캐시·`iss`/`aud`/`exp`/`nonce` 대조.
 * `GoogleSocialLoginProviderTest`·`KakaoSocialLoginProviderTest` 가 각자 갖고 있던 같은
 * 테스트를 이 클래스로 옮겼다(리뷰 지적 — 두 어댑터가 이 로직을 그대로 복제했다). 어댑터별
 * 나머지 경로(토큰 교환·userinfo 대체·에러 매핑)는 그 두 테스트에 남는다.
 */
class OidcJwksVerifierTest {
    private lateinit var server: JwksStubServer
    private lateinit var signingKey: RSAKey

    @BeforeEach
    fun start() {
        server = JwksStubServer()
        signingKey = RSAKeyGenerator(2048).keyID("test-key-1").generate()
        server.jwksBody = jwkSetBody(signingKey)
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    @DisplayName("정상 흐름 — 서명·claim 이 모두 유효하면 검증된 claim 을 낸다")
    fun `정상 흐름은 검증된 claim 을 낸다`() {
        val claims = verifier().verify(signedIdToken(), NONCE)

        assertThat(claims.sub).isEqualTo("test-sub-1")
        assertThat(claims.email).isEqualTo("user@example.test")
        assertThat(claims.emailVerified).isTrue()
    }

    @Test
    @DisplayName("email_verified claim 이 없으면 email 유무로 판정한다 — 카카오 계약과 같은 규칙")
    fun `email_verified 없이도 email 유무로 판정한다`() {
        val claims = verifier().verify(signedIdToken(includeEmailVerifiedClaim = false), NONCE)

        assertThat(claims.email).isEqualTo("user@example.test")
        assertThat(claims.emailVerified).isTrue()
    }

    @Test
    @DisplayName("email 도 email_verified 도 없으면 미검증이다")
    fun `email 이 없으면 email_verified 없이도 미검증이다`() {
        val claims = verifier().verify(signedIdToken(email = null, includeEmailVerifiedClaim = false), NONCE)

        assertThat(claims.email).isNull()
        assertThat(claims.emailVerified).isFalse()
    }

    @Test
    @DisplayName("nonce 가 다르면 거절된다 — 리플레이 방지")
    fun `nonce 불일치는 거절된다`() {
        val idToken = signedIdToken(nonce = "issued-nonce")

        assertThatThrownBy { verifier().verify(idToken, "different-nonce") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("aud 가 기대 클라이언트 id 와 다르면 거절된다")
    fun `aud 불일치는 거절된다`() {
        val idToken = signedIdToken(audience = "other-client-id")

        assertThatThrownBy { verifier().verify(idToken, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("iss 가 허용 목록 밖이면 거절된다")
    fun `iss 불일치는 거절된다`() {
        val idToken = signedIdToken(issuer = "https://evil.example.test")

        assertThatThrownBy { verifier().verify(idToken, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("만료된 ID 토큰은 거절된다")
    fun `만료된 토큰은 거절된다`() {
        val idToken = signedIdToken(expiresAt = Instant.now().minus(Duration.ofMinutes(5)))

        assertThatThrownBy { verifier().verify(idToken, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 서명 검증에서 거절된다 — JWKS 에 없는 키")
    fun `모르는 키 서명은 거절된다`() {
        val otherKey = RSAKeyGenerator(2048).keyID("other-key").generate()
        val idToken = signedIdToken(signingKeyOverride = otherKey)

        assertThatThrownBy { verifier().verify(idToken, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("JWKS 엔드포인트에 닿지 못하면 ExternalServiceUnavailableException 이다")
    fun `jwks 불통은 불통 예외다`() {
        val idToken = signedIdToken()
        val unreachableVerifier =
            OidcJwksVerifier(
                jwksUri = "http://127.0.0.1:1/jwks",
                issuers = setOf(ISSUER),
                audience = CLIENT_ID,
                jwksCacheTtl = Duration.ofMinutes(60),
                providerLabel = "테스트",
                connectTimeout = Duration.ofMillis(500),
                readTimeout = Duration.ofMillis(500),
            )

        assertThatThrownBy { unreachableVerifier.verify(idToken, NONCE) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    // ------------------------------------------------------------------ JWKS 캐시

    @Test
    @DisplayName("같은 kid 로 두 번 검증하면 JWKS 요청이 한 번뿐이다 — 캐시가 재사용된다")
    fun `같은 kid 는 캐시를 재사용한다`() {
        val v = verifier()
        v.verify(signedIdToken(), NONCE)

        val second = v.verify(signedIdToken(subject = "test-sub-2"), NONCE)

        assertThat(second.sub).isEqualTo("test-sub-2")
        assertThat(server.jwksRequestCount).isEqualTo(1)
    }

    @Test
    @DisplayName("모르는 kid(키 회전)를 만나면 캐시가 만료 전이라도 정확히 한 번 다시 받는다")
    fun `모르는 kid 는 캐시를 정확히 한 번 갱신한다`() {
        val v = verifier()
        v.verify(signedIdToken(), NONCE)
        assertThat(server.jwksRequestCount).isEqualTo(1)

        // 키 회전: JWKS 에 새 키가 늘었고, 새 토큰은 그 새 키로 서명됐다 — 캐시는 아직 모른다.
        val rotatedKey = RSAKeyGenerator(2048).keyID("test-key-2").generate()
        server.jwksBody = jwkSetBody(signingKey, rotatedKey)

        val claims = v.verify(signedIdToken(subject = "test-sub-3", signingKeyOverride = rotatedKey), NONCE)

        assertThat(claims.sub).isEqualTo("test-sub-3")
        assertThat(server.jwksRequestCount).isEqualTo(2)
    }

    @Test
    @DisplayName("캐시 TTL 이 지나면 같은 kid 라도 다시 받는다")
    fun `TTL 만료 후에는 같은 kid 도 다시 받는다`() {
        val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
        val v = verifier(jwksCacheTtl = Duration.ofMinutes(10), clock = clock)
        v.verify(signedIdToken(expiresAt = clock.instant().plus(Duration.ofMinutes(5))), NONCE)
        assertThat(server.jwksRequestCount).isEqualTo(1)

        clock.advance(Duration.ofMinutes(11))
        v.verify(signedIdToken(subject = "test-sub-4", expiresAt = clock.instant().plus(Duration.ofMinutes(5))), NONCE)

        assertThat(server.jwksRequestCount).isEqualTo(2)
    }

    // ------------------------------------------------------------------ 픽스처

    private fun verifier(
        jwksCacheTtl: Duration = Duration.ofMinutes(60),
        clock: Clock = Clock.systemUTC(),
    ): OidcJwksVerifier =
        OidcJwksVerifier(
            jwksUri = server.baseUrl + "/jwks",
            issuers = setOf(ISSUER),
            audience = CLIENT_ID,
            jwksCacheTtl = jwksCacheTtl,
            providerLabel = "테스트",
            clock = clock,
        )

    /** 시험용 클레임 조합기 — 각 매개변수가 검증 규칙 하나씩을 겨냥한 테스트 픽스처다. */
    @Suppress("LongParameterList")
    private fun signedIdToken(
        issuer: String = ISSUER,
        audience: String = CLIENT_ID,
        subject: String = "test-sub-1",
        nonce: String = NONCE,
        email: String? = "user@example.test",
        includeEmailVerifiedClaim: Boolean = true,
        emailVerified: Boolean = true,
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(5)),
        signingKeyOverride: RSAKey? = null,
    ): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("nonce", nonce)
                .expirationTime(Date.from(expiresAt))
        email?.let { builder.claim("email", it) }
        if (includeEmailVerifiedClaim) {
            builder.claim("email_verified", emailVerified)
        }
        val key = signingKeyOverride ?: signingKey
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), builder.build())
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun jwkSetBody(vararg keys: RSAKey): String = JWKSet(keys.map { it.toPublicJWK() }).toString()

    private companion object {
        const val CLIENT_ID = "test-client-id"
        const val ISSUER = "https://issuer.example.test"
        const val NONCE = "expected-nonce"
    }
}

/** `/jwks` 하나만 스텁한다 — `GoogleSocialLoginProviderTest.RoutedStubServer` 와 같은 필요, 경로가 하나뿐이라 더 단순하다. */
private class JwksStubServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var jwksBody: String = """{"keys":[]}"""

    /** JWKS 캐시가 실제로 요청 수를 줄이는지 재는 자리 — 캐시 테스트 전용. */
    private val requests = AtomicInteger(0)
    val jwksRequestCount: Int get() = requests.get()

    init {
        server.createContext("/jwks") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            requests.incrementAndGet()
            val payload = jwksBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
        }
    }

    override fun close() {
        server.stop(0)
    }
}

/** 만료 경계를 재기 위한 시계 — `GoogleSocialLoginProviderTest.MutableClock` 과 같은 필요. */
private class MutableClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

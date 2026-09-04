package kr.easydoc.infrastructure.auth.google

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/** Google 어댑터 — 토큰 교환·ID 토큰 검증을 HTTP 스텁 + 테스트 RSA 키로 잰다. */
class GoogleSocialLoginProviderTest {
    private lateinit var server: RoutedStubServer
    private lateinit var signingKey: RSAKey

    @BeforeEach
    fun start() {
        server = RoutedStubServer()
        signingKey = RSAKeyGenerator(2048).keyID("test-key-1").generate()
        server.jwksBody = jwkSetBody(signingKey)
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    @DisplayName("정상 흐름 — 토큰 교환 후 검증된 신원을 낸다")
    fun `정상 흐름은 검증된 신원을 낸다`() {
        server.tokenResponseBody = tokenResponseWith(signedIdToken())

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("google-sub-1")
        assertThat(identity.email).isEqualTo("user@example.test")
        assertThat(identity.emailVerified).isTrue()

        val tokenRequest = server.tokenRequests.single()
        val form = parseForm(tokenRequest)
        assertThat(form["code"]).isEqualTo("auth-code")
        assertThat(form["client_id"]).isEqualTo(CLIENT_ID)
        assertThat(form["client_secret"]).isEqualTo(CLIENT_SECRET)
        assertThat(form["redirect_uri"]).isEqualTo(REDIRECT_URI)
        assertThat(form["grant_type"]).isEqualTo("authorization_code")
    }

    @Test
    @DisplayName("nonce 가 다르면 거절된다 — 리플레이 방지")
    fun `nonce 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(signedIdToken(nonce = "issued-nonce"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, "different-nonce") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("aud 가 클라이언트 id 와 다르면 거절된다")
    fun `aud 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(signedIdToken(audience = "other-client-id"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("iss 가 Google 이 아니면 거절된다")
    fun `iss 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(signedIdToken(issuer = "https://evil.example.test"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("만료된 ID 토큰은 거절된다")
    fun `만료된 토큰은 거절된다`() {
        server.tokenResponseBody =
            tokenResponseWith(signedIdToken(expiresAt = Instant.now().minus(Duration.ofMinutes(5))))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 서명 검증에서 거절된다 — JWKS 에 없는 키")
    fun `모르는 키 서명은 거절된다`() {
        val otherKey = RSAKeyGenerator(2048).keyID("other-key").generate()
        server.tokenResponseBody = tokenResponseWith(signedIdToken(signingKeyOverride = otherKey))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("이메일 미검증(email_verified=false)도 신원 자체는 낸다 — 검증은 유스케이스 책임")
    fun `이메일 미검증도 신원을 낸다`() {
        server.tokenResponseBody = tokenResponseWith(signedIdToken(emailVerified = false))

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.emailVerified).isFalse()
    }

    @Test
    @DisplayName("토큰 엔드포인트가 400 이면(코드 거절) 401 에 해당하는 예외다")
    fun `코드 거절은 InvalidCredentialsException 이다`() {
        server.tokenResponseStatus = 400
        server.tokenResponseBody = """{"error":"invalid_grant"}"""

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("토큰 엔드포인트가 5xx 면 ExternalServiceUnavailableException 이다")
    fun `제공자 5xx 는 불통 예외다`() {
        server.tokenResponseStatus = 503
        server.tokenResponseBody = """{"error":"internal"}"""

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    @Test
    @DisplayName("제공자에 아예 닿지 못하면 ExternalServiceUnavailableException 이다")
    fun `연결 실패는 불통 예외다`() {
        val unreachableSettings =
            GoogleOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = "http://127.0.0.1:1/token",
                jwksUri = server.baseUrl + "/certs",
                connectTimeout = Duration.ofMillis(500),
                readTimeout = Duration.ofMillis(500),
            )

        assertThatThrownBy { GoogleSocialLoginProvider(unreachableSettings).exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    @Test
    @DisplayName("authorizationUrl 이 state·nonce·redirect_uri·scope 를 담는다")
    fun `인가 URL 이 필요한 파라미터를 담는다`() {
        val url = provider().authorizationUrl(state = "s1", nonce = "n1", redirectUri = REDIRECT_URI)

        assertThat(url).startsWith(GOOGLE_AUTHORIZATION_ENDPOINT)
        assertThat(url).contains("client_id=$CLIENT_ID")
        assertThat(url).contains("state=s1")
        assertThat(url).contains("nonce=n1")
        assertThat(url).contains("scope=openid")
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 지원하지 않는다")
    fun `허용 목록만 지원한다`() {
        val p = provider()

        assertThat(p.supportsRedirectUri(REDIRECT_URI)).isTrue()
        assertThat(p.supportsRedirectUri("https://evil.example.test/callback")).isFalse()
    }

    // ------------------------------------------------------------------ JWKS 캐시

    @Test
    @DisplayName("같은 kid 로 두 번 검증하면 JWKS 요청이 한 번뿐이다 — 캐시가 재사용된다")
    fun `같은 kid 는 캐시를 재사용한다`() {
        val p = provider()
        server.tokenResponseBody = tokenResponseWith(signedIdToken())
        p.exchange("auth-code", REDIRECT_URI, NONCE)

        server.tokenResponseBody = tokenResponseWith(signedIdToken(subject = "google-sub-2"))
        val second = p.exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(second.providerUserId).isEqualTo("google-sub-2")
        assertThat(server.certsRequestCount).isEqualTo(1)
    }

    @Test
    @DisplayName("모르는 kid(키 회전)를 만나면 캐시가 만료 전이라도 정확히 한 번 다시 받는다")
    fun `모르는 kid 는 캐시를 정확히 한 번 갱신한다`() {
        val p = provider()
        server.tokenResponseBody = tokenResponseWith(signedIdToken())
        p.exchange("auth-code", REDIRECT_URI, NONCE)
        assertThat(server.certsRequestCount).isEqualTo(1)

        // 키 회전: JWKS 에 새 키가 늘었고, 새 토큰은 그 새 키로 서명됐다 — 캐시는 아직 모른다.
        val rotatedKey = RSAKeyGenerator(2048).keyID("test-key-2").generate()
        server.jwksBody = jwkSetBody(signingKey, rotatedKey)
        server.tokenResponseBody =
            tokenResponseWith(signedIdToken(subject = "google-sub-3", signingKeyOverride = rotatedKey))

        val identity = p.exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("google-sub-3")
        assertThat(server.certsRequestCount).isEqualTo(2)
    }

    @Test
    @DisplayName("캐시 TTL 이 지나면 같은 kid 라도 다시 받는다")
    fun `TTL 만료 후에는 같은 kid 도 다시 받는다`() {
        val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
        val p = provider(jwksCacheTtl = Duration.ofMinutes(10), clock = clock)
        server.tokenResponseBody =
            tokenResponseWith(signedIdToken(expiresAt = clock.instant().plus(Duration.ofMinutes(5))))
        p.exchange("auth-code", REDIRECT_URI, NONCE)
        assertThat(server.certsRequestCount).isEqualTo(1)

        clock.advance(Duration.ofMinutes(11))
        server.tokenResponseBody =
            tokenResponseWith(
                signedIdToken(subject = "google-sub-4", expiresAt = clock.instant().plus(Duration.ofMinutes(5))),
            )
        p.exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(server.certsRequestCount).isEqualTo(2)
    }

    // ------------------------------------------------------------------ 픽스처

    private fun provider(
        jwksCacheTtl: Duration = GOOGLE_JWKS_CACHE_TTL_DEFAULT,
        clock: Clock = Clock.systemUTC(),
    ): GoogleSocialLoginProvider =
        GoogleSocialLoginProvider(
            GoogleOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = server.baseUrl + "/token",
                jwksUri = server.baseUrl + "/certs",
                jwksCacheTtl = jwksCacheTtl,
            ),
            clock = clock,
        )

    /** 시험용 클레임 조합기 — 각 매개변수가 검증 규칙 하나씩을 겨냥한 테스트 픽스처다. */
    @Suppress("LongParameterList")
    private fun signedIdToken(
        issuer: String = "https://accounts.google.com",
        audience: String = CLIENT_ID,
        subject: String = "google-sub-1",
        nonce: String = NONCE,
        email: String = "user@example.test",
        emailVerified: Boolean = true,
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(5)),
        signingKeyOverride: RSAKey? = null,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .expirationTime(Date.from(expiresAt))
                .build()
        val key = signingKeyOverride ?: signingKey
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun tokenResponseWith(idToken: String): String = """{"id_token":"$idToken"}"""

    private fun jwkSetBody(vararg keys: RSAKey): String =
        com.nimbusds.jose.jwk
            .JWKSet(keys.map { it.toPublicJWK() })
            .toString()

    private fun parseForm(body: String): Map<String, String> =
        body
            .split("&")
            .associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

    private companion object {
        const val CLIENT_ID = "test-client-id"
        const val CLIENT_SECRET = "test-client-secret"
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val NONCE = "expected-nonce"
    }
}

/** `/token`·`/certs` 를 각각 다른 응답으로 스텁한다 — `StubLlmServer`(llm 패키지)와 달리 경로별 분기가 필요하다. */
private class RoutedStubServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var tokenResponseStatus: Int = 200
    var tokenResponseBody: String = "{}"
    var jwksBody: String = """{"keys":[]}"""

    val tokenRequests: MutableList<String> = mutableListOf()

    /** JWKS 캐시가 실제로 요청 수를 줄이는지 재는 자리 — [GoogleSocialLoginProviderTest] 의 캐시 테스트 전용. */
    private val certsRequests = AtomicInteger(0)
    val certsRequestCount: Int get() = certsRequests.get()

    init {
        server.createContext("/token") { exchange -> handleToken(exchange) }
        server.createContext("/certs") { exchange -> handleCerts(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handleToken(exchange: HttpExchange) {
        exchange.use {
            tokenRequests += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(exchange, tokenResponseStatus, tokenResponseBody)
        }
    }

    private fun handleCerts(exchange: HttpExchange) {
        exchange.use {
            certsRequests.incrementAndGet()
            respond(exchange, 200, jwksBody)
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, payload.size.toLong())
        exchange.responseBody.write(payload)
    }

    override fun close() {
        server.stop(0)
    }
}

/** 만료 경계를 재기 위한 시계 — `JdbcOAuthStateStoreTest`(다른 패키지)와 같은 필요다. */
private class MutableClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

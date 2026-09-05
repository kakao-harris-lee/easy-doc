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
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Google 어댑터 — 토큰 교환·에러 매핑·`authorizationUrl`·redirect_uri 허용 목록을 HTTP
 * 스텁 + 테스트 RSA 키로 잰다. 서명·JWKS 캐시·`iss`/`aud`/`exp`/`nonce` 대조는
 * `OidcJwksVerifier` 가 공유하는 로직이라 `OidcJwksVerifierTest` 로 옮겼다(리뷰 지적).
 */
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

    // ------------------------------------------------------------------ 픽스처

    private fun provider(): GoogleSocialLoginProvider =
        GoogleSocialLoginProvider(
            GoogleOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = server.baseUrl + "/token",
                jwksUri = server.baseUrl + "/certs",
            ),
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
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
        jwt.sign(RSASSASigner(signingKey))
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

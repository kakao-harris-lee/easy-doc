package kr.easydoc.infrastructure.auth.kakao

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
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * 카카오 어댑터 — OIDC ID 토큰 경로와 사용자 정보 대체 경로 둘 다 HTTP 스텁 + 테스트 RSA
 * 키로 잰다. `GoogleSocialLoginProviderTest` 와 같은 방식(JDK `HttpServer` 스텁, 실제
 * 네트워크 없음).
 */
class KakaoSocialLoginProviderTest {
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

    // ------------------------------------------------------------------ OIDC 경로

    @Test
    @DisplayName("id_token 이 있으면 OIDC 경로로 검증된 신원을 낸다")
    fun `OIDC 경로는 검증된 신원을 낸다`() {
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken())

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("kakao-sub-1")
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
    @DisplayName("id_token 에 email claim 이 없으면 이메일 없는 신원이다")
    fun `id_token 에 이메일이 없으면 신원의 이메일도 null 이다`() {
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken(email = null))

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.email).isNull()
        assertThat(identity.emailVerified).isFalse()
    }

    @Test
    @DisplayName("nonce 가 다르면 거절된다 — 리플레이 방지")
    fun `nonce 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken(nonce = "issued-nonce"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, "different-nonce") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("aud 가 클라이언트 id 와 다르면 거절된다")
    fun `aud 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken(audience = "other-client-id"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("iss 가 카카오가 아니면 거절된다")
    fun `iss 불일치는 거절된다`() {
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken(issuer = "https://evil.example.test"))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("만료된 ID 토큰은 거절된다")
    fun `만료된 토큰은 거절된다`() {
        server.tokenResponseBody =
            tokenResponseWith(idToken = signedIdToken(expiresAt = Instant.now().minus(Duration.ofMinutes(5))))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 서명 검증에서 거절된다 — JWKS 에 없는 키")
    fun `모르는 키 서명은 거절된다`() {
        val otherKey = RSAKeyGenerator(2048).keyID("other-key").generate()
        server.tokenResponseBody = tokenResponseWith(idToken = signedIdToken(signingKeyOverride = otherKey))

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    // ------------------------------------------------------------------ 사용자 정보 대체 경로

    @Test
    @DisplayName("id_token 이 없으면 사용자 정보 엔드포인트로 신원을 낸다")
    fun `id_token 이 없으면 사용자 정보 경로를 탄다`() {
        server.tokenResponseBody = tokenResponseWithoutIdToken(accessToken = "access-1")
        server.userInfoBody = userInfoBodyOf(id = 111L, email = "user2@example.test", valid = true, verified = true)

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("111")
        assertThat(identity.email).isEqualTo("user2@example.test")
        assertThat(identity.emailVerified).isTrue()
        assertThat(server.userInfoAuthorizationHeader).isEqualTo("Bearer access-1")
    }

    @Test
    @DisplayName("사용자 정보 경로 — 이메일 필드 자체가 없으면 신원의 이메일은 null 이다")
    fun `사용자 정보 경로에서 이메일이 없으면 null 이다`() {
        server.tokenResponseBody = tokenResponseWithoutIdToken(accessToken = "access-2")
        server.userInfoBody = """{"id": 222}"""

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("222")
        assertThat(identity.email).isNull()
        assertThat(identity.emailVerified).isFalse()
    }

    @Test
    @DisplayName("사용자 정보 경로 — is_email_valid 나 is_email_verified 가 거짓이면 미검증이다")
    fun `사용자 정보 경로에서 미검증 이메일은 emailVerified 가 거짓이다`() {
        server.tokenResponseBody = tokenResponseWithoutIdToken(accessToken = "access-3")
        server.userInfoBody =
            userInfoBodyOf(id = 333L, email = "unverified@example.test", valid = true, verified = false)

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.email).isEqualTo("unverified@example.test")
        assertThat(identity.emailVerified).isFalse()
    }

    // ------------------------------------------------------------------ 실패 갈래

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
            KakaoOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = "http://127.0.0.1:1/token",
                jwksUri = server.baseUrl + "/jwks",
                userInfoEndpoint = server.baseUrl + "/user/me",
                connectTimeout = Duration.ofMillis(500),
                readTimeout = Duration.ofMillis(500),
            )

        assertThatThrownBy { KakaoSocialLoginProvider(unreachableSettings).exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    // ------------------------------------------------------------------ URL·redirect_uri

    @Test
    @DisplayName("authorizationUrl 이 state·nonce·redirect_uri·scope 를 담는다")
    fun `인가 URL 이 필요한 파라미터를 담는다`() {
        val url = provider().authorizationUrl(state = "s1", nonce = "n1", redirectUri = REDIRECT_URI)

        assertThat(url).startsWith(KAKAO_AUTHORIZATION_ENDPOINT)
        assertThat(url).contains("client_id=$CLIENT_ID")
        assertThat(url).contains("state=s1")
        assertThat(url).contains("nonce=n1")
        assertThat(url).contains("scope=openid")
        assertThat(url).contains("account_email")
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 지원하지 않는다")
    fun `허용 목록만 지원한다`() {
        val p = provider()

        assertThat(p.supportsRedirectUri(REDIRECT_URI)).isTrue()
        assertThat(p.supportsRedirectUri("https://evil.example.test/callback")).isFalse()
    }

    // ------------------------------------------------------------------ 픽스처

    private fun provider(): KakaoSocialLoginProvider =
        KakaoSocialLoginProvider(
            KakaoOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = server.baseUrl + "/token",
                jwksUri = server.baseUrl + "/jwks",
                userInfoEndpoint = server.baseUrl + "/user/me",
            ),
        )

    @Suppress("LongParameterList")
    private fun signedIdToken(
        issuer: String = "https://kauth.kakao.com",
        audience: String = CLIENT_ID,
        subject: String = "kakao-sub-1",
        nonce: String = NONCE,
        email: String? = "user@example.test",
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
        val key = signingKeyOverride ?: signingKey
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), builder.build())
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun tokenResponseWith(idToken: String): String = """{"access_token":"access-token","id_token":"$idToken"}"""

    private fun tokenResponseWithoutIdToken(accessToken: String): String = """{"access_token":"$accessToken"}"""

    private fun userInfoBodyOf(
        id: Long,
        email: String,
        valid: Boolean,
        verified: Boolean,
    ): String =
        """
        {"id": $id, "kakao_account": {"email": "$email", "is_email_valid": $valid, "is_email_verified": $verified}}
        """.trimIndent()

    private fun jwkSetBody(vararg keys: RSAKey): String =
        com.nimbusds.jose.jwk
            .JWKSet(keys.map { it.toPublicJWK() })
            .toString()

    private fun parseForm(body: String): Map<String, String> =
        body
            .split("&")
            .associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                java.net.URLDecoder.decode(key, StandardCharsets.UTF_8) to
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

    private companion object {
        const val CLIENT_ID = "test-client-id"
        const val CLIENT_SECRET = "test-client-secret"
        const val REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
        const val NONCE = "expected-nonce"
    }
}

/** `/token`·`/jwks`·`/user/me` 를 각각 다른 응답으로 스텁한다 — Google 테스트의 `RoutedStubServer` 와 같은 필요. */
private class RoutedStubServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var tokenResponseStatus: Int = 200
    var tokenResponseBody: String = "{}"
    var jwksBody: String = """{"keys":[]}"""
    var userInfoBody: String = """{"id": 0}"""

    val tokenRequests: MutableList<String> = mutableListOf()
    var userInfoAuthorizationHeader: String? = null
        private set

    init {
        server.createContext("/token") { exchange -> handleToken(exchange) }
        server.createContext("/jwks") { exchange -> handleJwks(exchange) }
        server.createContext("/user/me") { exchange -> handleUserInfo(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handleToken(exchange: HttpExchange) {
        exchange.use {
            tokenRequests += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(exchange, tokenResponseStatus, tokenResponseBody)
        }
    }

    private fun handleJwks(exchange: HttpExchange) {
        exchange.use {
            respond(exchange, 200, jwksBody)
        }
    }

    private fun handleUserInfo(exchange: HttpExchange) {
        exchange.use {
            userInfoAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange, 200, userInfoBody)
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

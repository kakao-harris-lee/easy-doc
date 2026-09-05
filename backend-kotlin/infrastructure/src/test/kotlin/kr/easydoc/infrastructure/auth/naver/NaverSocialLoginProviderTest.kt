package kr.easydoc.infrastructure.auth.naver

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

/**
 * 네이버 어댑터 — HTTP 스텁으로 잰다(`KakaoSocialLoginProviderTest`·
 * `GoogleSocialLoginProviderTest`와 같은 방식, 실제 네트워크 없음). 네이버는 OIDC 가
 * 없어(backlog §1.4, `x-social-login.providers.x-note`) userinfo 경로 하나뿐이다 —
 * 카카오처럼 OIDC/userinfo 두 경로를 가르는 테스트가 없다.
 */
class NaverSocialLoginProviderTest {
    private lateinit var server: RoutedStubServer

    @BeforeEach
    fun start() {
        server = RoutedStubServer()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    // ------------------------------------------------------------------ 성공 경로

    @Test
    @DisplayName("성공 — response.id·response.email 로 신원을 낸다, 이메일은 항상 미검증이다")
    fun `성공 경로는 신원을 낸다`() {
        server.tokenResponseBody = tokenResponseBody(accessToken = "access-1")
        server.userInfoBody = userInfoBodyOf(id = "naver-1", email = "user@example.test")

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("naver-1")
        assertThat(identity.email).isEqualTo("user@example.test")
        // 네이버는 email_verified 개념이 없다 — 이 어댑터는 항상 미검증으로 낸다
        // (2026-09-05 결정, x-social-login.providers.x-note).
        assertThat(identity.emailVerified).isFalse()
        assertThat(server.userInfoAuthorizationHeader).isEqualTo("Bearer access-1")

        val tokenRequest = server.tokenRequests.single()
        val form = parseForm(tokenRequest)
        assertThat(form["code"]).isEqualTo("auth-code")
        assertThat(form["client_id"]).isEqualTo(CLIENT_ID)
        assertThat(form["client_secret"]).isEqualTo(CLIENT_SECRET)
        assertThat(form["redirect_uri"]).isEqualTo(REDIRECT_URI)
        assertThat(form["grant_type"]).isEqualTo("authorization_code")
    }

    @Test
    @DisplayName("이메일이 없으면(제공 정보 미동의) 신원의 이메일도 null 이고 여전히 미검증이다")
    fun `이메일이 없으면 null 이다`() {
        server.tokenResponseBody = tokenResponseBody(accessToken = "access-2")
        server.userInfoBody = """{"resultcode":"00","message":"success","response":{"id":"naver-2"}}"""

        val identity = provider().exchange("auth-code", REDIRECT_URI, NONCE)

        assertThat(identity.providerUserId).isEqualTo("naver-2")
        assertThat(identity.email).isNull()
        assertThat(identity.emailVerified).isFalse()
    }

    // ------------------------------------------------------------------ 실패 갈래

    @Test
    @DisplayName("토큰 엔드포인트가 400 이면(코드 거절) InvalidCredentialsException 이다")
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
            NaverOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = "http://127.0.0.1:1/token",
                userInfoEndpoint = server.baseUrl + "/user/me",
                connectTimeout = Duration.ofMillis(500),
                readTimeout = Duration.ofMillis(500),
            )

        assertThatThrownBy { NaverSocialLoginProvider(unreachableSettings).exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    @Test
    @DisplayName("사용자 정보 엔드포인트가 400 이면 InvalidCredentialsException 이다")
    fun `사용자 정보 경로의 400도 InvalidCredentialsException 이다`() {
        server.tokenResponseBody = tokenResponseBody(accessToken = "access-3")
        server.userInfoStatus = 401
        server.userInfoBody = """{"error":"invalid_token"}"""

        assertThatThrownBy { provider().exchange("auth-code", REDIRECT_URI, NONCE) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    // ------------------------------------------------------------------ URL·redirect_uri

    @Test
    @DisplayName("authorizationUrl 이 state·redirect_uri 를 담는다 — nonce는 싣지 않는다(네이버 미지원)")
    fun `인가 URL 이 필요한 파라미터를 담는다`() {
        val url = provider().authorizationUrl(state = "s1", nonce = "n1", redirectUri = REDIRECT_URI)

        assertThat(url).startsWith(NAVER_AUTHORIZATION_ENDPOINT)
        assertThat(url).contains("client_id=$CLIENT_ID")
        assertThat(url).contains("state=s1")
        assertThat(url).contains("response_type=code")
        assertThat(url).doesNotContain("nonce")
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 지원하지 않는다")
    fun `허용 목록만 지원한다`() {
        val p = provider()

        assertThat(p.supportsRedirectUri(REDIRECT_URI)).isTrue()
        assertThat(p.supportsRedirectUri("https://evil.example.test/callback")).isFalse()
    }

    // ------------------------------------------------------------------ 픽스처

    private fun provider(): NaverSocialLoginProvider =
        NaverSocialLoginProvider(
            NaverOAuthSettings(
                clientId = CLIENT_ID,
                clientSecret = Secret(CLIENT_SECRET),
                redirectUriAllowlist = setOf(REDIRECT_URI),
                tokenEndpoint = server.baseUrl + "/token",
                userInfoEndpoint = server.baseUrl + "/user/me",
            ),
        )

    private fun tokenResponseBody(accessToken: String): String =
        """{"access_token":"$accessToken","token_type":"bearer","expires_in":3600}"""

    private fun userInfoBodyOf(
        id: String,
        email: String,
    ): String = """{"resultcode":"00","message":"success","response":{"id":"$id","email":"$email"}}"""

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
        const val REDIRECT_URI = "http://localhost:5173/auth/naver/callback"
        const val NONCE = "expected-nonce"
    }
}

/** `/token`·`/user/me` 를 각각 다른 응답으로 스텁한다 — Kakao 테스트의 `RoutedStubServer` 와 같은 필요. */
private class RoutedStubServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var tokenResponseStatus: Int = 200
    var tokenResponseBody: String = "{}"
    var userInfoStatus: Int = 200
    var userInfoBody: String = """{"resultcode":"00","message":"success","response":{"id":"0"}}"""

    val tokenRequests: MutableList<String> = mutableListOf()
    var userInfoAuthorizationHeader: String? = null
        private set

    init {
        server.createContext("/token") { exchange -> handleToken(exchange) }
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

    private fun handleUserInfo(exchange: HttpExchange) {
        exchange.use {
            userInfoAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange, userInfoStatus, userInfoBody)
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

package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.TestJwt
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * `/auth` 의 **실측** 계약 — 명세 §5 의 C-R 계층.
 *
 * ## 왜 MockMvc 가 아닌가
 *
 * 인증 실패 401(M-2~M-7)은 **필터·인터셉터·컨테이너가 얽힌 자리**에서 만들어진다. MockMvc 는
 * 서블릿 컨테이너를 띄우지 않고 필터를 손으로 체인에 끼워 부르므로, 인증 401 을
 * `sendError` 로 내는 구현(가장 흔한 형태)이 `/error` 재디스패치를 지나며 Spring 기본
 * 본문을 내보내는 것을 **재현하지 못한다.** 계약이 그 자리를 E-2 로 따로 지목했고,
 * 헤더 쪽에서 이미 "측정한 것처럼 보이는 통과"를 겪었다.
 *
 * 함께 여기 두는 것들 — **실물 설정에서만 잴 수 있는 값**이다.
 * `expires_in`(설정에서 온다)·`token_type`·발급 토큰의 클레임 집합, 그리고 가입이 기본
 * 작업 공간을 같은 트랜잭션에서 만드는지. 이것들을 슬라이스에서 재면 테스트 배선이 정한
 * 값을 테스트가 다시 단언하는 꼴이 된다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthEndpointReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    // ================================================================ 성공 경로

    @Test
    @DisplayName("S-1 가입이 계정과 기본 작업 공간을 함께 만든다 (같은 트랜잭션)")
    fun `가입은 기본 작업 공간까지 만든다`() {
        val email = uniqueEmail()

        val response = post("/auth/signup", credentials(email, VALID_PASSWORD))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/signup", "post"))
        val userId = UUID.fromString(bodyOf(response)["id"].toString())
        // 계정만 있고 작업 공간이 없는 사용자가 생기면 첫 업로드가 갈 곳을 잃는다.
        assertThat(database.queryInt("SELECT count(*) FROM workspaces WHERE user_id = '$userId'")).isEqualTo(1)
    }

    @Test
    @DisplayName("L-1 로그인 응답의 키 집합·token_type·expires_in 이 계약과 같다")
    fun `토큰 응답이 계약과 같다`() {
        val email = uniqueEmail()
        post("/auth/signup", credentials(email, VALID_PASSWORD))

        val response = post("/auth/login", credentials(email, VALID_PASSWORD))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/login", "post"))
        val body = bodyOf(response)
        // X-E1/X-E2 — snake_case 회귀와 키 누락·추가를 「정확히」 하나로 잡는다.
        assertThat(body.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired("TokenResponse"))
        assertThat(body["token_type"]).isEqualTo(ContractSpec.schemaPropertyConst("TokenResponse", "token_type"))
        assertThat((body["expires_in"] as Number).toLong())
            .isEqualTo(ContractSpec.authNumber("default_lifetime_seconds").toLong())
        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("L-1b 발급 토큰의 alg 와 클레임 키 집합이 계약과 정확히 같다 (개인정보 클레임 0)")
    fun `발급 토큰이 계약의 클레임만 담는다`() {
        val token = issueToken()

        assertThat(TestJwt.header(token)["alg"]).isEqualTo(ContractSpec.authText("algorithm"))
        assertThat(
            TestJwt
                .payload(token)
                .keys
                .map { it.toString() }
                .toSet(),
        ).isEqualTo(ContractSpec.authStrings("claims").toSet())
    }

    @Test
    @DisplayName("M-1 유효한 토큰 → 200 · 사적 헤더 2종(개수까지) · 본문 키 집합이 계약의 required")
    fun `내 정보가 계약과 같다`() {
        val response = get("/auth/me", issueToken())

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/me", "get"))
        assertPrivateHeaders(response)
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired("UserResponse"))
    }

    // ================================================================ 인증 실패 (C-R 의 요점)

    @Test
    @DisplayName("M-2 Authorization 헤더 없음 → 401 · WWW-Authenticate · 본문 최상위 키가 정확히 계약의 required")
    fun `헤더가 없으면 401 이고 본문이 계약 형태다`() {
        val response = get("/auth/me", token = null)

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE).orElse(null))
            .isEqualTo(ContractSpec.headerConst("WWWAuthenticateBearer"))
        // `sendError` → `/error` 로 나가면 timestamp·status·error·path 가 함께 실린다.
        // 금지 키를 열거하지 않고 「정확히 같다」로 잡는다 — 그 목록은 계약 산문에만 있다.
        assertThat(ContractSpec.schemaAllowsAdditionalProperties("ErrorResponse")).isFalse()
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired("ErrorResponse"))
        assertThat(response.headers().firstValue("content-type").orElse(""))
            .startsWith("application/json")
    }

    @Test
    @DisplayName("M-3 위조 서명·만료·typ 오값·sub 부재·삭제된 계정 → 다섯 응답이 서로 동일한 401")
    fun `토큰 무효 갈래가 서로 구분되지 않는다`() {
        val deletedUserToken = issueToken().also { deleteUserOf(it) }
        val responses =
            listOf(
                "위조 서명" to get("/auth/me", TestJwt.withBrokenSignature(issueToken())),
                "만료" to get("/auth/me", forgedToken(expiresAt = Instant.now().minusSeconds(1))),
                "typ 오값" to get("/auth/me", forgedToken(typ = "refresh")),
                "sub 부재" to get("/auth/me", forgedToken(subject = null)),
                "삭제된 계정" to get("/auth/me", deletedUserToken),
            )

        responses.forEach { (label, response) ->
            assertThat(response.statusCode()).withFailMessage("%s 가 401 이 아니다", label).isEqualTo(UNAUTHORIZED)
        }
        val distinct =
            responses.map { (_, response) ->
                response.body() to
                    response.headers().firstValue(WWW_AUTHENTICATE)
            }
        assertThat(distinct.distinct())
            .withFailMessage("무효 갈래의 응답이 서로 다르다 — 어디서 실패했는지가 새어 나간다: %s", distinct)
            .hasSize(1)
    }

    @Test
    @DisplayName("M-4 계약의 required_claims 를 하나씩 뺀 토큰은 전부 401 (케이스를 계약에서 유도한다)")
    fun `필수 클레임이 빠지면 거부한다`() {
        val required = ContractSpec.authStrings("required_claims")
        assertThat(required).isNotEmpty()

        required.forEach { claim ->
            val token = forgedToken(omit = claim)
            assertThat(get("/auth/me", token).statusCode())
                .withFailMessage("클레임 %s 이 빠진 토큰이 거부되지 않았다", claim)
                .isEqualTo(UNAUTHORIZED)
        }
    }

    @Test
    @DisplayName("M-5 typ 가 계약의 claim_typ 와 다르면 401")
    fun `용도가 다른 토큰을 거부한다`() {
        val other = ContractSpec.authText("claim_typ") + "-아님"

        assertThat(get("/auth/me", forgedToken(typ = other)).statusCode()).isEqualTo(UNAUTHORIZED)
    }

    @Test
    @DisplayName("M-6 exp 를 계약의 허용 오차 + 1초 지난 토큰은 401 (skew 를 계약에서 읽어 유도한다)")
    fun `만료 직후 토큰을 거부한다`() {
        val skew = ContractSpec.authNumber("clock_skew_seconds").toLong()
        // 허용 오차가 0 이면 exp 가 1초 전인 토큰이다. 오차가 바뀌면 이 값도 따라 바뀐다.
        val expired = forgedToken(expiresAt = Instant.now().minusSeconds(skew + 1))

        assertThat(get("/auth/me", expired).statusCode()).isEqualTo(UNAUTHORIZED)
    }

    @Test
    @DisplayName("M-7 exp 가 아직 지나지 않은 토큰은 통과한다 — M-6 의 반대쪽")
    fun `만료 전 토큰은 통과한다`() {
        // 한쪽만 걸면 「전부 거절」 구현이 M-6 을 통과한다.
        val valid = forgedToken(expiresAt = Instant.now().plusSeconds(NOT_YET_EXPIRED_SECONDS))

        assertThat(get("/auth/me", valid).statusCode()).isEqualTo(ContractSpec.successStatus("/auth/me", "get"))
    }

    // ================================================================ 도구

    /** 실제로 가입·로그인해 얻은 토큰. 위조 케이스의 `sub` 도 여기서 온다. */
    private fun issueToken(): String {
        val email = uniqueEmail()
        post("/auth/signup", credentials(email, VALID_PASSWORD))
        return bodyOf(post("/auth/login", credentials(email, VALID_PASSWORD)))["access_token"].toString()
    }

    /**
     * 위조 케이스가 공유하는 **실재하는** 사용자.
     *
     * `sub` 를 아무 UUID 로 두면 M-7(만료 전 토큰은 통과한다)이 "계정 없음"으로 401 이 되어
     * 그 케이스가 재려던 것을 재지 못한다 — 통과 쪽 단언은 다른 이유로 막히면 안 된다.
     */
    private val registeredUserId: String by lazy {
        bodyOf(post("/auth/signup", credentials(uniqueEmail(), VALID_PASSWORD)))["id"].toString()
    }

    /**
     * 계약의 클레임 이름으로 토큰을 조립한다. 이름은 계약에서 읽고 값만 여기서 정한다.
     *
     * [omit] 은 그 클레임을 아예 빼고, [subject]·[typ] 은 값을 바꾼다.
     */
    private fun forgedToken(
        subject: String? = registeredUserId,
        typ: String = ContractSpec.authText("claim_typ"),
        expiresAt: Instant = Instant.now().plusSeconds(NOT_YET_EXPIRED_SECONDS),
        omit: String? = null,
    ): String {
        val claims =
            buildMap<String, Any?> {
                subject?.let { put("sub", it) }
                put("typ", typ)
                put("exp", expiresAt.epochSecond)
            }.filterKeys { it != omit }
        return TestJwt.signHs256(
            AUTH_REACH_TEST_SECRET,
            mapOf("alg" to ContractSpec.authText("algorithm")),
            claims,
        )
    }

    /** M-3 의 「삭제된 계정」 — 토큰은 유효한데 행이 없는 상태를 만든다. */
    private fun deleteUserOf(token: String) {
        val subject = TestJwt.payload(token)["sub"].toString()
        database.execute("DELETE FROM users WHERE id = '$subject'")
    }

    private fun credentials(
        email: String,
        password: String,
    ): String = json.writeValueAsString(mapOf("email" to email, "password" to password))

    private fun post(
        path: String,
        payload: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)),
        )

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri(path)).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return send(builder)
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    /** X-D1 하한선 + X-D2b 중복 부착 부재. 값과 개수를 모두 계약에서 읽어 본다. */
    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        ContractSpec.globalResponseHeaders().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    private fun uniqueEmail(): String = "reach${counter++}@example.test"

    companion object {
        private const val UNAUTHORIZED = 401
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val VALID_PASSWORD = "correct horse battery"
        private const val NOT_YET_EXPIRED_SECONDS = 600L

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("auth_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/**
 * 이 테스트가 쓰는 서명 키.
 *
 * 32바이트 이상이어야 한다 — 계약 `x-auth.min_secret_bytes` 미만이면 인증 API 전체가
 * 503 이 되고, 그 상태는 [AuthUnavailableContractTest] 가 따로 잰다. 값이
 * `@SpringBootTest(properties=...)` 안에 들어가야 해서 컴파일 상수여야 한다.
 */
const val AUTH_REACH_TEST_SECRET: String = "test-only-signing-key-0123456789-abcdef"

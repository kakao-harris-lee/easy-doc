package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** S-13 · L-6 · M-8 — 서명 키가 계약의 하한에 못 미치는 구성 (명세 §5 의 C-P 계층). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$TOO_SHORT_SECRET"],
)
class AuthUnavailableContractTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("계약의 min_secret_bytes 미만인 키를 실제로 쓰고 있다 (이 테스트의 전제)")
    fun `전제 — 서명 키가 계약 하한 미만이다`() {
        assertThat(TOO_SHORT_SECRET.toByteArray(Charsets.UTF_8).size)
            .isLessThan(ContractSpec.authNumber("min_secret_bytes"))
    }

    @Test
    @DisplayName("S-13 가입이 503 + 문자열 detail 이다")
    fun `가입이 503 이다`() = assertServiceUnavailable(post("/auth/signup"), "/auth/signup", "post")

    @Test
    @DisplayName("L-6 로그인이 503 + 문자열 detail 이다")
    fun `로그인이 503 이다`() = assertServiceUnavailable(post("/auth/login"), "/auth/login", "post")

    @Test
    @DisplayName("M-8 유효해 보이는 토큰을 줘도 503 이다 (401 로 감추지 않는다)")
    fun `내 정보가 503 이다`() {
        val response =
            send(HttpRequest.newBuilder(uri("/auth/me")).header("Authorization", "Bearer any.token.value").GET())

        assertServiceUnavailable(response, "/auth/me", "get")
    }

    @Test
    @DisplayName("WL-7 보호 자원(작업 공간 목록)도 503 이다 — 401 로 감추지 않는다")
    fun `작업 공간 목록이 503 이다`() {
        val response =
            send(HttpRequest.newBuilder(uri("/workspaces")).header("Authorization", "Bearer any.token.value").GET())

        assertServiceUnavailable(response, "/workspaces", "get")
    }

    @Test
    @DisplayName("DC-19 업로드도 503 이다 — 500 도 401 도 아니다 (X-C6 의 둘째 팔)")
    fun `업로드가 503 이다`() {
        val response =
            send(
                HttpRequest
                    .newBuilder(uri("/documents"))
                    .header("Authorization", "Bearer any.token.value")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"text":"본문"}""", Charsets.UTF_8)),
            )

        assertServiceUnavailable(response, "/documents", "post")

        assertThat(response.statusCode()).isNotEqualTo(UNAUTHORIZED)
    }

    @Test
    @DisplayName("같은 구성에서 /health 는 200 이다 — 기동을 막지 않는다")
    fun `기동은 막지 않는다`() {
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).statusCode()).isEqualTo(OK)
    }

    private fun assertServiceUnavailable(
        response: HttpResponse<String>,
        path: String,
        method: String,
    ) {
        assertThat(response.statusCode()).isEqualTo(SERVICE_UNAVAILABLE)

        assertThat(ContractSpec.responseStatuses(path, method)).contains(SERVICE_UNAVAILABLE.toString())
        val detail = json.readValue(response.body(), Map::class.java)["detail"]
        assertThat(detail).isInstanceOf(String::class.java)
    }

    /** 가입·로그인은 본문 검증보다 먼저 설정 문제로 끊겨야 한다 — 그래서 본문은 유효한 것을 준다. */
    private fun post(path: String): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"email":"unavailable@example.test","password":"correct horse battery"}""",
                        Charsets.UTF_8,
                    ),
                ),
        )

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    companion object {
        private const val OK = 200
        private const val SERVICE_UNAVAILABLE = 503
        private const val UNAUTHORIZED = 401

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("auth_unavailable") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 계약 `x-auth.min_secret_bytes` 에 못 미치는 키. 값 자체는 의미가 없고 짧다는 것이 요점이다. */
const val TOO_SHORT_SECRET: String = "too-short"

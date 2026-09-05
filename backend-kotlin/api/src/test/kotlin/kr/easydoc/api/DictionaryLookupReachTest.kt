package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
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

/**
 * `POST /dictionary/lookup` 의 실측 계약 — 실물 색인·실물 인증을 함께 태운다 (P0-5 조각 4).
 *
 * `DictionaryLookupContractTest`(`@WebMvcTest`, 고정 대역)와 겹치지 않는다 — 그쪽은 컨트롤러의
 * 분기(401·415·422·429·200 모양)를 빠르게 재고, 이 클래스는 **실제 색인**(엔트리 2,179건)이
 * 실제 wire 응답으로 옮겨지는지를 잰다. 계획 §3.4 실측 값(`구비서류`→id 2165,
 * `시행령`→id 1775 단독)이 API 경계까지 살아 있는지가 이 파일의 존재 이유다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$DICTIONARY_REACH_TEST_SECRET",
        "easydoc.dictionary.lookup.enabled=true",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DictionaryLookupReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("실제 색인 — '구비서류'는 id 2165 치환 후보를 돌려준다")
    fun `구비서류가 치환 후보로 나온다`() {
        val response = lookup(issueToken(), "구비서류")

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(PATH, POST))
        val candidates = bodyOf(response)["candidates"] as List<*>
        assertThat(candidates).isNotEmpty()
        val candidate = candidates.first() as Map<*, *>
        assertThat(candidate["easy_term"]).isEqualTo("준비할 서류")
        assertThat(candidate["strategy"]).isEqualTo("substitute")
        assertThat(candidate["match_kind"]).isEqualTo("exact")
        assertThat(candidate["applicable"]).isEqualTo(true)
    }

    @Test
    @DisplayName("실제 색인 — '시행령'은 표제어 자체로 잡힌다 (짧은 접두 표제어가 최장일치를 이기지 않는다)")
    fun `시행령이 표제어 그대로 잡힌다`() {
        val response = lookup(issueToken(), "시행령")

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(PATH, POST))
        val candidates = bodyOf(response)["candidates"] as List<*>
        assertThat(candidates).isNotEmpty()
        val candidate = candidates.first() as Map<*, *>
        assertThat(candidate["term"]).isEqualTo("시행령")
        assertThat(candidate["match_kind"]).isEqualTo("exact")
    }

    @Test
    @DisplayName("토큰 없이 부르면 401, text/plain 은 415, 101자는 422 — 실물 배선에서도 같다")
    fun `실물 배선에서도 401 415 422 다`() {
        assertThat(post("/dictionary/lookup", null, "{\"text\":\"구비서류\"}", "application/json").statusCode())
            .isEqualTo(UNAUTHORIZED)

        val token = issueToken()
        assertThat(post("/dictionary/lookup", token, "구비서류", "text/plain").statusCode())
            .isEqualTo(UNSUPPORTED_MEDIA_TYPE)

        val tooLong = json.writeValueAsString(mapOf("text" to "가".repeat(TOO_LONG_QUERY_LENGTH)))
        val tooLongResponse = post("/dictionary/lookup", token, tooLong, "application/json")
        assertThat(tooLongResponse.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(bodyOf(tooLongResponse)["detail"]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("사용자별 분당 60회를 넘으면 61번째 호출이 429 + Retry-After 다")
    fun `61번째 호출은 429 다`() {
        val token = issueToken()

        repeat(RATE_LIMIT_PER_MINUTE) {
            assertThat(lookup(token, "구비서류").statusCode()).isEqualTo(OK)
        }

        val blocked = lookup(token, "구비서류")

        assertThat(blocked.statusCode()).isEqualTo(TOO_MANY_REQUESTS)
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent()
    }

    private fun lookup(
        token: String,
        text: String,
    ): HttpResponse<String> {
        val body = json.writeValueAsString(mapOf("text" to text))
        return post("/dictionary/lookup", token, body, "application/json")
    }

    private fun post(
        path: String,
        token: String?,
        body: String,
        contentType: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    /** 실제로 가입·로그인해 얻은 토큰. */
    private fun issueToken(): String {
        val email = uniqueEmail()
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        post("/auth/signup", null, credentials, "application/json")
        return bodyOf(post("/auth/login", null, credentials, "application/json"))["access_token"].toString()
    }

    private fun uniqueEmail(): String = "dict-reach${counter++}@example.test"

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private companion object {
        const val PATH = "/dictionary/lookup"
        const val POST = "post"

        const val OK = 200
        const val UNAUTHORIZED = 401
        const val UNSUPPORTED_MEDIA_TYPE = 415
        const val UNPROCESSABLE = 422
        const val TOO_MANY_REQUESTS = 429

        const val VALID_PASSWORD = "correct horse battery"
        const val TOO_LONG_QUERY_LENGTH = 101
        const val RATE_LIMIT_PER_MINUTE = 60

        private var counter = 0

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("dictionary_lookup_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. */
const val DICTIONARY_REACH_TEST_SECRET: String = "test-only-dictionary-signing-key-0123456789"

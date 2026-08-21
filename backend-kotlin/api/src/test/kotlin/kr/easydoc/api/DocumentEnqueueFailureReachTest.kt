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

/** DC-18 — 큐 등록이 실패하면 500 이고 저장이 전량 롤백된다 (X-C6 의 첫째 팔). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentEnqueueFailureReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("DC-18 큐 등록이 실패하면 **500**(502·503 아님) · detail 문자열 · 문서와 변환이 **하나도 남지 않는다**")
    fun `등록 실패는 500 이고 전량 롤백된다`() {
        val token = newAccount()
        val userId = subjectOf(token)

        database.execute("DROP TABLE conversion_jobs")

        val response = createFromText(token, """{"text":"본문입니다"}""")

        assertThat(response.statusCode())
            .withFailMessage("등록 실패가 %d 로 나갔다 — 계약은 500 이다", response.statusCode())
            .isEqualTo(INTERNAL_ERROR)

        assertThat(response.statusCode()).isNotEqualTo(BAD_GATEWAY)
        assertThat(response.statusCode()).isNotEqualTo(SERVICE_UNAVAILABLE)
        assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, POST))
            .withFailMessage("계약이 POST %s 에 500 을 선언하지 않는다", DOCUMENTS_PATH)
            .contains(INTERNAL_ERROR.toString())
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)

        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.responseExampleDetail(INTERNAL_ERROR_COMPONENT, UNEXPECTED_EXAMPLE))

        assertThat(database.queryInt("SELECT count(*) FROM documents WHERE user_id = '$userId'"))
            .withFailMessage("등록에 실패했는데 문서가 남았다 — 저장과 등록이 같은 트랜잭션이 아니다")
            .isZero()
        val conversionCount =
            database.queryInt(
                "SELECT count(*) FROM conversions c JOIN documents d ON d.id = c.document_id " +
                    "WHERE d.user_id = '$userId'",
            )
        assertThat(conversionCount).withFailMessage("등록에 실패했는데 변환이 남았다").isZero()
    }

    private fun newAccount(): String {
        val credentials =
            json.writeValueAsString(
                mapOf("email" to "enqueue@example.test", "password" to VALID_PASSWORD),
            )
        send(post(null, credentials, "/auth/signup"))
        return bodyOf(send(post(null, credentials, "/auth/login")))["access_token"].toString()
    }

    private fun createFromText(
        token: String,
        body: String,
    ): HttpResponse<String> = send(post(token, body, DOCUMENTS_PATH))

    private fun post(
        token: String?,
        body: String,
        path: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val POST = "post"
        private const val DETAIL = "detail"

        private const val INTERNAL_ERROR = 500
        private const val BAD_GATEWAY = 502
        private const val SERVICE_UNAVAILABLE = 503

        private const val INTERNAL_ERROR_COMPONENT = "InternalError"
        private const val UNEXPECTED_EXAMPLE = "unexpected"

        private const val VALID_PASSWORD = "correct horse battery"

        /** 이 클래스 전용 DB — 테스트가 작업 테이블을 지우므로 다른 테스트와 공유할 수 없다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_enqueue_failure") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

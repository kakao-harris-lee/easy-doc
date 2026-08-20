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
 * **DC-18 — 큐 등록이 실패하면 500 이고 저장이 전량 롤백된다** (X-C6 의 첫째 팔).
 *
 * ## 무엇이 걸려 있나
 *
 * 계약이 2026-08-20 에 502 를 **폐기**했다(`x-retired-responses`). 옛 조항은
 * *"큐 등록에 실패하면 이미 커밋된 변환을 `failure_code = \"EnqueueFailed\"` 로 표시한 뒤
 * 502"* 였고 그것은 **Redis/ARQ 전제**였다. 큐가 같은 DB 로 옮겨오면서 등록이 저장과
 * **같은 트랜잭션**에 들어갔으므로 "저장은 됐는데 등록은 실패" 라는 상태가 구조적으로
 * 성립하지 않는다. 등록이 실패하면 전량 롤백이고 그때 옳은 코드는 **500** 이다 —
 * 되돌릴 것이 없는 실패에 재시도 신호(502)를 붙이지 않는다.
 *
 * DC-19 와 **서로에 대한 부정 단언**을 갖는다: 여기가 「502·503 이 아니다」를, 저쪽이
 * 「500 이 아니다」를 단언한다. 한쪽만 두면 구현이 두 갈래를 한 코드로 합쳐도 초록이다.
 *
 * ## 왜 빈을 갈아 끼우지 않고 **테이블을 지우는가**
 *
 * 지시는 *"`ConversionQueue` 빈을 INSERT 실패를 흉내 내는 것으로 갈아 끼우고 … 던지는
 * 예외는 실제와 같은 계열(`DataAccessException` 하위)로 둔다"* 였다. 여기서는 그보다
 * **한 겹 실물에 가깝게** 간다 — 실제 어댑터(`JdbcConversionQueue`)가 실제 SQL 을 던지고
 * PostgreSQL 이 실제 오류를 낸다. 흉내 낸 빈은 「우리가 만든 예외가 어떻게 매핑되는가」를
 * 재지만, 이 방식은 **어댑터·트랜잭션·매핑을 한 줄로 꿴 실제 경로**를 잰다.
 *
 * 대가는 이 클래스가 **전용 DB 를 쓰고 그 DB 를 망가뜨린다**는 것이다. 그래서 다른
 * 테스트와 DB 를 공유하지 않고, 이 클래스 안에서도 롤백 케이스 하나만 돌린다.
 */
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

        // 큐 INSERT 가 실제로 실패하게 만든다. 작업 테이블이 없으면 어댑터의 SQL 이
        // PostgreSQL 오류를 받고 그것이 `DataAccessException` 으로 올라온다.
        database.execute("DROP TABLE conversion_jobs")

        val response = createFromText(token, """{"text":"본문입니다"}""")

        assertThat(response.statusCode())
            .withFailMessage("등록 실패가 %d 로 나갔다 — 계약은 500 이다", response.statusCode())
            .isEqualTo(INTERNAL_ERROR)
        // **502 도 503 도 아니다.** 502 는 계약이 폐기했고, 503 은 「구성이 비어 이 기능을
        // 줄 수 없다」는 다른 사건이다(DC-19).
        assertThat(response.statusCode()).isNotEqualTo(BAD_GATEWAY)
        assertThat(response.statusCode()).isNotEqualTo(SERVICE_UNAVAILABLE)
        assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, POST))
            .withFailMessage("계약이 POST %s 에 500 을 선언하지 않는다", DOCUMENTS_PATH)
            .contains(INTERNAL_ERROR.toString())
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
        // 문구도 계약에서 읽는다 — 코드에 적으면 계약이 문구를 바꿔도 옛 값으로 통과한다.
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.responseExampleDetail(INTERNAL_ERROR_COMPONENT, UNEXPECTED_EXAMPLE))

        // **전량 롤백** — 상태 코드만 재면 「500 은 냈는데 문서는 남았다」가 지나간다.
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

    // ================================================================ 요청 조립

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

        /** **이 클래스 전용 DB** — 테스트가 작업 테이블을 지우므로 다른 테스트와 공유할 수 없다. */
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

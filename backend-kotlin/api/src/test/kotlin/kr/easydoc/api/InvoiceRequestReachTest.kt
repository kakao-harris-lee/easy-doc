package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.mail.FakeMailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * 세금계산서 요청(계획 `docs/plans/2026-09-07-invoice-requests.md` §2)의 실측 계약 — 실물
 * PostgreSQL. `CreditsReachTest`와 같은 형태 — 서비스 층 분기는 `InvoiceRequestServiceTest`
 * (순수 단위)·`JdbcInvoiceRequestRepositoryTest`(저장소 단위)가 이미 재므로, 이 파일은
 * **HTTP 경계**만 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$INVOICE_REACH_TEST_SECRET",
        "easydoc.billing.operator-email=${InvoiceRequestReachTest.OPERATOR_EMAIL}",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceRequestReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mailSender: FakeMailSender

    private val json = ObjectMapper()

    @Test
    @DisplayName("I-1 유효한 요청은 201이고 운영자·요청자 메일 각 1통을 보낸다")
    fun `유효한 요청은 201 이고 메일 두 통을 보낸다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = createInvoiceRequest(token, workspaceId, contactEmail = "requester1@example.test")

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(INVOICE_REQUESTS_PATH, POST))
        val body = bodyOf(response)
        assertThat(body["status"]).isEqualTo("requested")
        assertThat(body["workspace_id"]).isEqualTo(workspaceId)

        // FakeMailSender 는 전역 빈이다 — 가입 시 이메일 인증 코드 메일도 같은 목록에
        // 쌓이므로, 이 요청이 낸 메일만 제목으로 좁혀서 본다.
        val invoiceMails = mailSender.sent.filter { it.subject.contains("세금계산서") }
        assertThat(invoiceMails.map { it.to.value }).containsExactlyInAnyOrder(
            OPERATOR_EMAIL,
            "requester1@example.test",
        )
    }

    @Test
    @DisplayName("I-2 체크섬이 틀린 사업자번호는 422이고 행이 남지 않는다")
    fun `체크섬 오류는 422 이고 행이 남지 않는다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response =
            createInvoiceRequest(
                token,
                workspaceId,
                contactEmail = "checksum@example.test",
                businessNumber = "1234567890",
            )

        assertThat(response.statusCode()).isEqualTo(422)
        val count =
            database.queryInt("SELECT count(*) FROM invoice_requests WHERE contact_email = 'checksum@example.test'")
        assertThat(count).isZero()
    }

    @Test
    @DisplayName("I-2b 전각 숫자가 섞인 사업자번호도 422다 — DB CHECK 제약(ASCII만 허용)에서 500으로 죽지 않는다")
    fun `전각 숫자 사업자번호는 422 이고 행이 남지 않는다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response =
            createInvoiceRequest(
                token,
                workspaceId,
                contactEmail = "fullwidth@example.test",
                // '０'(U+FF10)은 Char.isDigit() 이 참인 전각 숫자다 — BusinessNumber.of 가
                // ASCII 로 좁히지 않으면 이 값이 남아 CHECK 제약(business_number ~ '^[0-9]{10}$')
                // 위반으로 500이 난다.
                businessNumber = "０234567894",
            )

        assertThat(response.statusCode()).isEqualTo(422)
        val body = bodyOf(response)
        assertThat(body["detail"]).isEqualTo("사업자등록번호가 올바르지 않습니다")
        val count =
            database.queryInt("SELECT count(*) FROM invoice_requests WHERE contact_email = 'fullwidth@example.test'")
        assertThat(count).isZero()
    }

    @Test
    @DisplayName("I-3 같은 워크스페이스·같은 기간의 요청이 이미 있으면 409다")
    fun `같은 기간 중복은 409 다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        createInvoiceRequest(token, workspaceId, contactEmail = "dup1@example.test")

        val second = createInvoiceRequest(token, workspaceId, contactEmail = "dup2@example.test")

        assertThat(second.statusCode()).isEqualTo(409)
    }

    @Test
    @DisplayName("I-4 다른 사용자의 워크스페이스는 404다 — 존재 은닉")
    fun `타인 워크스페이스는 404 다`() {
        val mine = newAccount()
        val other = newAccount()
        val othersWorkspace = defaultWorkspaceId(other)

        val response = createInvoiceRequest(mine, othersWorkspace, contactEmail = "stranger@example.test")

        assertThat(response.statusCode()).isNotEqualTo(403)
        assertThat(response.statusCode()).isEqualTo(404)
    }

    @Test
    @DisplayName("I-5 토큰 없이 호출 → 401")
    fun `토큰 없는 조회는 401 이다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = listInvoiceRequests(token = null, workspaceId = workspaceId)

        assertThat(response.statusCode()).isEqualTo(401)
    }

    @Test
    @DisplayName("I-6 목록은 최신순이고 최근 50건까지만 준다")
    fun `목록은 최신순이고 상한이 있다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        val insertedIds = seedRequests(workspaceId, subjectOf(token), count = 55)

        val response = listInvoiceRequests(token, workspaceId)

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(INVOICE_REQUESTS_PATH, GET))
        val items = bodyOf(response).required("items") as List<*>
        assertThat(items).hasSize(50)
        val returnedIds = items.map { (it as Map<*, *>)["id"].toString() }
        // 가장 나중에 심은 것부터 최근 50건 — 최신순.
        assertThat(returnedIds).isEqualTo(insertedIds.takeLast(50).reversed())
    }

    private fun createInvoiceRequest(
        token: String,
        workspaceId: String,
        contactEmail: String,
        businessNumber: String = VALID_BUSINESS_NUMBER,
        period: Pair<String, String> = "2026-08-01" to "2026-08-31",
    ): HttpResponse<String> {
        val body =
            json.writeValueAsString(
                mapOf(
                    "business_number" to businessNumber,
                    "company_name" to "쉬운글 주식회사",
                    "representative_name" to "홍길동",
                    "contact_email" to contactEmail,
                    "address" to "서울시 어딘가",
                    "period_from" to period.first,
                    "period_to" to period.second,
                ),
            )
        return send(
            jsonRequest(INVOICE_REQUESTS_PATH.replace("{workspace_id}", workspaceId), token)
                .POST(bodyPublisher(body)),
        )
    }

    private fun listInvoiceRequests(
        token: String?,
        workspaceId: String,
    ): HttpResponse<String> =
        send(jsonRequest(INVOICE_REQUESTS_PATH.replace("{workspace_id}", workspaceId), token).GET())

    /** 가입하고 로그인해 토큰을 받는다. */
    private fun newAccount(): String {
        val email = "invoice${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(bodyPublisher(credentials)))
        val login = send(jsonRequest("/auth/login", null).POST(bodyPublisher(credentials)))
        return bodyOf(login).required("access_token").toString()
    }

    private fun defaultWorkspaceId(token: String): String {
        val response = send(jsonRequest("/workspaces", token).GET())
        val items = bodyOf(response).required("items") as List<*>
        return (items.single() as Map<*, *>).required("id").toString()
    }

    /** API를 거치지 않고 요청 [count]건을 직접 심는다. 삽입 순서대로 id를 돌려준다. */
    private fun seedRequests(
        workspaceId: String,
        ownerId: String,
        count: Int,
    ): List<String> =
        (0 until count).map { index ->
            val id = UUID.randomUUID().toString()
            // 부분 유니크 색인(같은 워크스페이스·같은 기간의 requested 중복 금지)을 피하려고
            // 매 건마다 서로 다른 연·월을 쓴다 — year*12+month 가 index마다 유일하다.
            val year = 2000 + index / 12
            val month = (index % 12) + 1
            database.execute(
                """
                INSERT INTO invoice_requests (
                    id, workspace_id, owner_user_id, business_number, company_name, contact_email,
                    period_from, period_to, status, requested_at
                ) VALUES (
                    '$id', '$workspaceId', '$ownerId', '$VALID_BUSINESS_NUMBER', '쉬운글 주식회사',
                    'seed@example.test', '$year-${"%02d".format(month)}-01', '$year-${"%02d".format(month)}-15',
                    'requested', now() + interval '$index seconds'
                )
                """.trimIndent(),
            )
            id
        }

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun jsonRequest(
        path: String,
        token: String?,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun bodyPublisher(payload: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    companion object {
        private const val INVOICE_REQUESTS_PATH = "/workspaces/{workspace_id}/invoice-requests"
        private const val POST = "post"
        private const val GET = "get"
        private const val VALID_PASSWORD = "correct horse battery"

        /** `220-81-62517` — 체크섬을 통과하는 실제 표기 예시(`BusinessNumberTest`와 같은 값 형태). */
        private const val VALID_BUSINESS_NUMBER = "2208162517"

        /** `easydoc.billing.operator-email` 로 배선하는 테스트 전용 운영자 주소. */
        const val OPERATOR_EMAIL: String = "billing-operator@example.test"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("invoice_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val INVOICE_REACH_TEST_SECRET: String = "invoice-reach-test-signing-key-0123456789-abc"

private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

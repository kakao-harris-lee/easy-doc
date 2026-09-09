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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 크레딧 계정(C1)의 실측 계약 — 실물 PostgreSQL, `easydoc.credits.enforced=true`.
 *
 * 서비스 층 분기(예약 성공/402/집행 꺼짐)는 `CreditAccountServiceTest`(순수 단위)·
 * `JdbcCreditAccountRepositoryTest`(저장소 단위)가 이미 재므로, 이 파일은 **HTTP
 * 경계**만 잰다 — 202/402 헤더, GET 200/404/401.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$CREDITS_REACH_TEST_SECRET",
        "easydoc.credits.enforced=true",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreditsReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("C-1 가용 크레딧이 충분하면 202 이고 X-Credit-Balance 가 예약 직후 가용 잔액이다")
    fun `충분한 가용 잔액은 202 다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        grantCredits(workspaceId, 10)

        // ceil(2500/1000) = 3.
        val response = createDocument(token, "가".repeat(2500))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        assertThat(response.headers().firstValue(CREDIT_BALANCE_HEADER)).hasValue("7")
    }

    @Test
    @DisplayName("C-2 가용 크레딧보다 큰 요청은 402 + 헤더 2종 + detail — 문서는 생기지 않는다")
    fun `가용보다 큰 요청은 402 다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        grantCredits(workspaceId, 1)

        // ceil(1001/1000) = 2.
        val response = createDocument(token, "가".repeat(1001))

        assertDeclaredStatus(response, PAYMENT_REQUIRED)
        assertThat(response.headers().firstValue(CREDIT_BALANCE_HEADER)).hasValue("1")
        assertThat(response.headers().firstValue(CREDITS_REQUIRED_HEADER)).hasValue("2")
        assertThat(bodyOf(response)["detail"]).isEqualTo("크레딧이 부족합니다. 충전 후 다시 시도하세요.")
        assertThat(documentCount(token)).isZero()
    }

    @Test
    @DisplayName("C-3 GET credits 200 — 키 집합이 정확히 WorkspaceCreditsResponse.required")
    fun `조회는 계약과 같은 키 집합을 낸다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        grantCredits(workspaceId, 50)

        val response = credits(token, workspaceId)

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(CREDITS_PATH, GET))
        val body = bodyOf(response)
        assertThat(body.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired(CREDITS_SCHEMA))
        assertThat(body["workspace_id"]).isEqualTo(workspaceId)
        assertThat(body["balance"]).isEqualTo(50)
        assertThat(body["reserved"]).isEqualTo(0)
        assertThat(body["available"]).isEqualTo(50)
        assertThat(body["enforced"]).isEqualTo(true)
        assertThat(body["transactions"]).isInstanceOf(List::class.java)
        // signupGrant 가 이 스위트에서 0(기본값)이라 부여도 건너뜀도 일어나지 않는다.
        assertThat(body["signup_grant_skipped"]).isEqualTo(false)
    }

    @Test
    @DisplayName("C-4 다른 사용자의 워크스페이스 → 404 (403 이 아니다) — 존재 은닉")
    fun `타인 워크스페이스는 404 다`() {
        val mine = newAccount()
        val other = newAccount()
        val othersWorkspace = defaultWorkspaceId(other)

        val response = credits(mine, othersWorkspace)

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)["detail"]).isEqualTo("작업 공간을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("C-6 동시 등록 2건, 가용 1 → 정확히 하나만 202, 나머지 하나는 402다 — HTTP 경계 레이스")
    fun `동시 등록은 정확히 하나만 성공한다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)
        grantCredits(workspaceId, 1)

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempt = {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // ceil(1000/1000) = 1 크레딧.
                createDocument(token, "가".repeat(1000))
            }
            val first = pool.submit<HttpResponse<String>>(attempt)
            val second = pool.submit<HttpResponse<String>>(attempt)

            val results =
                listOf(
                    first.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )

            val accepted = results.filter { it.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST) }
            val rejected = results.filter { it.statusCode() == PAYMENT_REQUIRED }
            assertThat(accepted)
                .withFailMessage("정확히 하나만 성공해야 하는데 %d 개가 성공했다 — %s", accepted.size, results.map { it.statusCode() })
                .hasSize(1)
            assertThat(rejected).hasSize(1)
            assertThat(documentCount(token)).isEqualTo(1)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    @DisplayName("C-5 토큰 없이 호출 → 401")
    fun `토큰 없는 조회는 401 이다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = credits(token = null, workspaceId = workspaceId)

        assertDeclaredStatus(response, UNAUTHORIZED)
    }

    private fun createDocument(
        token: String,
        text: String,
    ): HttpResponse<String> {
        val body = json.writeValueAsString(mapOf("text" to text))
        return send(jsonRequest(DOCUMENTS_PATH, token).POST(bodyPublisher(body)))
    }

    private fun credits(
        token: String?,
        workspaceId: String,
    ): HttpResponse<String> = send(jsonRequest(CREDITS_PATH.replace("{workspace_id}", workspaceId), token).GET())

    /** 가입하고 로그인해 토큰을 받는다. 가입은 기본 작업 공간과 크레딧 계정을 함께 만든다. */
    private fun newAccount(): String {
        val email = "credits${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(bodyPublisher(credentials)))
        // 이 파일은 이메일 인증 게이트를 재지 않는다 — createDocument 403 을 피하려고 소급
        // 인증한다(`DocumentEndpointReachTest.newAccount` 와 같은 관행).
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        val login = send(jsonRequest("/auth/login", null).POST(bodyPublisher(credentials)))
        return bodyOf(login).required("access_token").toString()
    }

    private fun defaultWorkspaceId(token: String): String {
        val response = send(jsonRequest("/workspaces", token).GET())
        val items = bodyOf(response).required("items") as List<*>
        return (items.single() as Map<*, *>).required("id").toString()
    }

    /** 시그니처 부여(`credit-grant` 프로필)를 흉내 낸다 — C1 은 그 프로필을 열지 않는다. */
    private fun grantCredits(
        workspaceId: String,
        amount: Int,
    ) {
        database.execute(
            "UPDATE workspace_credit_accounts SET balance = balance + $amount WHERE workspace_id = '$workspaceId'",
        )
    }

    private fun documentCount(token: String): Int =
        database.queryInt(
            "SELECT count(*) FROM documents WHERE user_id = '${subjectOf(token)}'",
        )

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

    private fun assertDeclaredStatus(
        response: HttpResponse<*>,
        status: Int,
    ) {
        assertThat(response.statusCode()).isEqualTo(status)
        val declared =
            if (status == PAYMENT_REQUIRED) {
                ContractSpec.responseStatuses(DOCUMENTS_PATH, POST)
            } else {
                ContractSpec.responseStatuses(CREDITS_PATH, GET)
            }
        assertThat(declared)
            .withFailMessage("계약이 %d 를 선언하지 않는다", status)
            .contains(status.toString())
    }

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val CREDITS_PATH = "/workspaces/{workspace_id}/credits"
        private const val POST = "post"
        private const val GET = "get"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val PAYMENT_REQUIRED = 402

        private const val CREDITS_SCHEMA = "WorkspaceCreditsResponse"

        private const val CREDIT_BALANCE_HEADER = "X-Credit-Balance"
        private const val CREDITS_REQUIRED_HEADER = "X-Credits-Required"

        private const val VALID_PASSWORD = "correct horse battery"
        private const val TASK_TIMEOUT_SECONDS = 10L

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("credits_reach") }

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
const val CREDITS_REACH_TEST_SECRET: String = "credits-reach-test-signing-key-0123456789-abc"

private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

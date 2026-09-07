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
import java.util.UUID

/**
 * `GET /workspaces/{workspace_id}/usage` 의 실측 계약(2.20.0, U2) — 실물 PostgreSQL.
 *
 * 기간 경계(zone 자정)·`credits` 올림 규칙·비용 미상 처리는 `UsageQueryServiceTest`(순수
 * 단위)·`JdbcUsageReadRepositoryTest`(저장소 단위)가 이미 재므로, 이 파일은 **HTTP 경계**만
 * 잰다 — 상태 코드·바디 모양·소유권 404·검증 422·비용 문자열 직렬화.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$USAGE_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceUsageReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("U-1 200 · 키 집합이 정확히 WorkspaceUsageResponse.required · 문서·호출·비용이 실제 값과 같다")
    fun `기본 집계가 계약과 같다`() {
        val token = newAccount()
        val userId = subjectOf(token)
        val workspaceId = defaultWorkspaceId(token)
        // documents/characters/credits 는 documents 표가 아니라 llm_calls.document_char_count
        // 에서 유도한다(2026-09-08 리뷰) — 그래서 두 호출이 같은 documentId 를 공유해야
        // 문서 1건으로 집계된다.
        val documentId = UUID.randomUUID().toString()
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "convert",
            documentId = documentId,
            documentCharCount = 1500,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = "0.001000",
        )
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "repair",
            documentId = documentId,
            documentCharCount = 1500,
            inputTokens = 20,
            outputTokens = 10,
            costUsd = null,
        )

        val response = usage(token, workspaceId)

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(USAGE_PATH, GET))
        val body = bodyOf(response)
        assertThat(body.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired(USAGE_SCHEMA))

        assertThat(body["documents"]).isEqualTo(1)
        assertThat(body["characters"]).isEqualTo(1500)
        // ceil(1500/1000) = 2.
        assertThat(body["credits"]).isEqualTo(2)
        assertThat(body["llm_calls"]).isEqualTo(2)
        assertThat(body["input_tokens"]).isEqualTo(120)
        assertThat(body["output_tokens"]).isEqualTo(60)
        assertThat(body["cost_unknown_calls"]).isEqualTo(1)
    }

    @Test
    @DisplayName("U-1b 실패 호출(provider_error, V18)은 llm_calls·비용에서 빠지고 failed_calls로 센다")
    fun `실패 호출은 failed_calls로 센다`() {
        val token = newAccount()
        val userId = subjectOf(token)
        val workspaceId = defaultWorkspaceId(token)
        val documentId = UUID.randomUUID().toString()
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "convert",
            documentId = documentId,
            documentCharCount = 500,
            inputTokens = 30,
            outputTokens = 15,
            costUsd = "0.002000",
        )
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "repair",
            documentId = documentId,
            documentCharCount = 500,
            inputTokens = 0,
            outputTokens = 0,
            costUsd = null,
            outcome = "provider_error",
        )

        val body = bodyOf(usage(token, workspaceId))

        assertThat(body["llm_calls"]).isEqualTo(1)
        assertThat(body["failed_calls"]).isEqualTo(1)
        assertThat(body["estimated_cost_usd"]).isEqualTo("0.002000")
    }

    @Test
    @DisplayName("U-2 예상 비용은 문자열이다 — 부동소수 오차를 피하는 계약(x-request-field-constraints 관행과 같은 이유)")
    fun `비용 필드가 문자열이다`() {
        val token = newAccount()
        val userId = subjectOf(token)
        val workspaceId = defaultWorkspaceId(token)
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "convert",
            documentId = UUID.randomUUID().toString(),
            documentCharCount = 100,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = "0.012345",
        )

        val body = bodyOf(usage(token, workspaceId))

        assertThat(body["estimated_cost_usd"]).isInstanceOf(String::class.java)
        assertThat(body["estimated_cost_usd"]).isEqualTo("0.012345")
    }

    @Test
    @DisplayName("U-3 알려진 비용이 하나도 없으면 estimated_cost_usd 가 null 이다 — \"0\"이 아니다")
    fun `알려진 비용이 없으면 null 이다`() {
        val token = newAccount()
        val userId = subjectOf(token)
        val workspaceId = defaultWorkspaceId(token)
        insertLlmCall(
            workspaceId,
            userId,
            purpose = "convert",
            documentId = UUID.randomUUID().toString(),
            documentCharCount = 100,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = null,
        )

        val body = bodyOf(usage(token, workspaceId))

        assertThat(body.keys).contains("estimated_cost_usd")
        assertThat(body["estimated_cost_usd"]).isNull()
        assertThat(body["cost_unknown_calls"]).isEqualTo(1)
    }

    @Test
    @DisplayName("U-4 호출·문서가 없는 기간은 전부 0이고 by_purpose 가 빈 배열이다")
    fun `빈 기간은 0이다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val body = bodyOf(usage(token, workspaceId, from = "2020-01-01", to = "2020-01-31"))

        assertThat(body["documents"]).isEqualTo(0)
        assertThat(body["characters"]).isEqualTo(0)
        assertThat(body["credits"]).isEqualTo(0)
        assertThat(body["llm_calls"]).isEqualTo(0)
        assertThat(body["estimated_cost_usd"]).isNull()
        assertThat(body["cost_unknown_calls"]).isEqualTo(0)
        assertThat(body["by_purpose"]).isEqualTo(emptyList<Any?>())
    }

    @Test
    @DisplayName("U-5 다른 사용자의 워크스페이스 → 404 (403 이 아니다) — 존재 은닉")
    fun `타인 워크스페이스는 404 다`() {
        val mine = newAccount()
        val other = newAccount()
        val othersWorkspace = defaultWorkspaceId(other)

        val response = usage(mine, othersWorkspace)

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)["detail"]).isEqualTo("작업 공간을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("U-6 존재하지 않는 워크스페이스도 404")
    fun `없는 워크스페이스도 404 다`() {
        val token = newAccount()

        val response = usage(token, UUID.randomUUID().toString())

        assertDeclaredStatus(response, NOT_FOUND)
    }

    @Test
    @DisplayName("U-7 형식이 어긋난 from → 422 · detail 이 문자열")
    fun `형식 오류는 422 문자열이다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = usage(token, workspaceId, from = "2026/01/01")

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertThat(bodyOf(response)["detail"]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("U-8 to가 from보다 앞 → 422 문자열")
    fun `역순 구간은 422 다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = usage(token, workspaceId, from = "2026-02-01", to = "2026-01-01")

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertThat(bodyOf(response)["detail"]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("U-9 366일을 넘는 구간 → 422 문자열")
    fun `너무 넓은 구간은 422 다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = usage(token, workspaceId, from = "2025-01-01", to = "2026-01-02")

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertThat(bodyOf(response)["detail"]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("U-10 형식 오류가 소유 판정보다 먼저다 — 타인 워크스페이스 + 잘못된 날짜는 404 가 아니라 422")
    fun `검증이 소유 판정보다 먼저다`() {
        val mine = newAccount()
        val other = newAccount()
        val othersWorkspace = defaultWorkspaceId(other)

        val response = usage(mine, othersWorkspace, from = "not-a-date")

        assertDeclaredStatus(response, UNPROCESSABLE)
    }

    @Test
    @DisplayName("U-11 토큰 없이 호출 → 401")
    fun `토큰 없는 조회는 401 이다`() {
        val token = newAccount()
        val workspaceId = defaultWorkspaceId(token)

        val response = usage(token = null, workspaceId = workspaceId)

        assertDeclaredStatus(response, UNAUTHORIZED)
    }

    private fun usage(
        token: String?,
        workspaceId: String,
        from: String? = null,
        to: String? = null,
    ): HttpResponse<String> {
        val query =
            buildList {
                if (from != null) add("from=$from")
                if (to != null) add("to=$to")
            }.joinToString("&")
        val path = ITEM_PATH.replace("{workspace_id}", workspaceId)
        val url = if (query.isEmpty()) path else "$path?$query"
        return send(jsonRequest(url, token).GET())
    }

    /** 가입하고 로그인해 토큰을 받는다. 가입은 기본 작업 공간을 함께 만든다. */
    private fun newAccount(): String {
        val email = "usage${counter++}@example.test"
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

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    @Suppress("LongParameterList")
    private fun insertLlmCall(
        workspaceId: String,
        userId: String,
        purpose: String,
        documentId: String,
        documentCharCount: Int,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: String?,
        outcome: String = "completed",
    ) {
        val costLiteral = costUsd?.let { "'$it'" } ?: "NULL"
        database.execute(
            """
            INSERT INTO llm_calls
                (id, workspace_id, user_id, document_id, purpose, provider, model, input_tokens, output_tokens,
                 estimated_cost_usd, char_count, document_char_count, outcome)
            VALUES ('${UUID.randomUUID()}', '$workspaceId', '$userId', '$documentId', '$purpose', 'anthropic',
                    'claude-sonnet-5', $inputTokens, $outputTokens, $costLiteral, 40, $documentCharCount, '$outcome')
            """.trimIndent(),
        )
    }

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
        assertThat(ContractSpec.responseStatuses(USAGE_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", USAGE_PATH, status)
            .contains(status.toString())
    }

    companion object {
        private const val ITEM_PATH = "/workspaces/{workspace_id}/usage"
        private const val USAGE_PATH = "/workspaces/{workspace_id}/usage"
        private const val GET = "get"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val UNPROCESSABLE = 422

        private const val USAGE_SCHEMA = "WorkspaceUsageResponse"

        private const val VALID_PASSWORD = "correct horse battery"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("usage_reach") }

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
const val USAGE_REACH_TEST_SECRET: String = "usage-reach-test-signing-key-0123456789-abc"

private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

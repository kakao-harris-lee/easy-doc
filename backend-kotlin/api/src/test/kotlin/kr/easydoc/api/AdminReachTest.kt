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
 * 어드민 최소(A1, 계획 `docs/plans/2026-09-07-admin-minimum.md`)의 실측 계약 — 실물
 * PostgreSQL. `CreditsReachTest`·`InvoiceRequestReachTest`와 같은 형태 — 서비스 층 분기는
 * 단위 테스트(`AdminGuardTest`·`AdminGrantServiceTest`·`Jdbc*Test`)가 이미 재므로, 이
 * 파일은 **HTTP 경계**(403/200 배선, 감사 흔적, 검색·페이지, CRUD)만 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$ADMIN_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mailSender: FakeMailSender

    private val json = ObjectMapper()

    /**
     * 스윕 대상을 **계약에서 직접 유도한다** — 손으로 적은 목록이면 새 `x-admin-only`
     * 오퍼레이션이 여기 추가되지 않아도 빌드가 초록으로 남는다. `AdminGuard`는 본문을
     * 읽기 **전**(핸들러 진입 전 인터셉터)에 막으므로 본문은 `{}`로 충분하다 — 값 자체는
     * 이 스윕이 재는 것이 아니다.
     */
    @Test
    @DisplayName("관리자가 아닌 사용자는 계약의 x-admin-only 오퍼레이션 전부에서 403이다")
    fun `관리자 전용 오퍼레이션은 비관리자에게 403이다`() {
        val token = newVerifiedAccount()
        val adminOnlyOps = ContractSpec.operations().filter { (path, method) -> ContractSpec.adminOnly(path, method) }
        assertThat(adminOnlyOps)
            .withFailMessage("x-admin-only 오퍼레이션이 하나도 없다 — 이 스윕이 아무것도 재지 않는다")
            .isNotEmpty()

        adminOnlyOps.forEach { (path, method) ->
            val response = sendAdminOperation(token, path, method)
            assertThat(response.statusCode())
                .withFailMessage(
                    "%s %s 이 403이 아니다: %d %s",
                    method.uppercase(),
                    path,
                    response.statusCode(),
                    response.body(),
                ).isEqualTo(403)
            assertThat(bodyOf(response)["detail"]).isEqualTo("관리자 권한이 필요합니다")
        }
    }

    /** 경로 변수를 무작위 UUID로 채우고, 본문을 선언한 오퍼레이션에는 빈 객체를 싣는다. */
    private fun sendAdminOperation(
        token: String?,
        path: String,
        method: String,
    ): HttpResponse<String> {
        val resolvedPath = PATH_VARIABLE.replace(path) { UUID.randomUUID().toString() }
        val request = jsonRequest(resolvedPath, token)
        val hasBody = ContractSpec.requestBodySchemaName(path, method) != null
        val publisher = if (hasBody) bodyPublisher("{}") else HttpRequest.BodyPublishers.noBody()
        return send(request.method(method.uppercase(), publisher))
    }

    @Test
    @DisplayName("is_admin은 참이어도 이메일이 미검증이면 403이다")
    fun `미검증 관리자는 403이다`() {
        val token = newAccount(verifyEmail = false)
        database.execute("UPDATE users SET is_admin = true WHERE email = '$lastEmail'")

        val response = get(token, "/admin/workspaces")

        assertThat(response.statusCode()).isEqualTo(403)
    }

    @Test
    @DisplayName("검증된 관리자는 200이다 — 회수는 다음 요청부터 즉시 403이다")
    fun `검증된 관리자는 200이고 회수는 즉시 반영된다`() {
        val token = newVerifiedAdminAccount()

        assertThat(get(token, "/admin/workspaces").statusCode()).isEqualTo(200)

        database.execute("UPDATE users SET is_admin = false WHERE email = '$lastEmail'")

        assertThat(get(token, "/admin/workspaces").statusCode()).isEqualTo(403)
    }

    @Test
    @DisplayName("워크스페이스 목록은 이름으로 검색되고 page·size로 나뉜다")
    fun `워크스페이스 검색과 페이지`() {
        val admin = newVerifiedAdminAccount()
        val unique = UUID.randomUUID().toString().take(8)
        val userToken = newVerifiedAccount()
        renameDefaultWorkspace(userToken, "검색용-$unique")

        val response = get(admin, "/admin/workspaces?q=검색용-$unique&page=1&size=20")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = bodyOf(response)
        val items = body["items"] as List<*>
        assertThat(items).hasSize(1)
        assertThat(body["page"]).isEqualTo(1)
        assertThat(body["size"]).isEqualTo(20)
        assertThat(body["total"]).isEqualTo(1)
    }

    @Test
    @DisplayName("검색어의 %·_ 는 와일드카드로 새지 않는다 — q=% 는 아무것도 매치하지 않는다")
    fun `검색어의 와일드카드 문자는 리터럴이다`() {
        val admin = newVerifiedAdminAccount()
        val unique = UUID.randomUUID().toString().take(8)
        val userToken = newVerifiedAccount()
        renameDefaultWorkspace(userToken, "특수문자-$unique")

        // '%' 가 진짜 와일드카드로 해석되면 전체가 매치돼 total 이 1보다 훨씬 커진다.
        val response = get(admin, "/admin/workspaces?q=%25&page=1&size=20")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(bodyOf(response)["total"]).isEqualTo(0)
    }

    @Test
    @DisplayName("page=0 은 422다 — 목록 오퍼레이션 공통")
    fun `page 0은 422다`() {
        val admin = newVerifiedAdminAccount()

        assertThat(get(admin, "/admin/workspaces?page=0").statusCode()).isEqualTo(422)
        assertThat(get(admin, "/admin/invoice-requests?page=0").statusCode()).isEqualTo(422)
    }

    @Test
    @DisplayName("size=101 은 422다 — 목록 오퍼레이션 공통")
    fun `size 101은 422다`() {
        val admin = newVerifiedAdminAccount()

        assertThat(get(admin, "/admin/workspaces?size=101").statusCode()).isEqualTo(422)
        assertThat(get(admin, "/admin/invoice-requests?size=101").statusCode()).isEqualTo(422)
    }

    @Test
    @DisplayName("워크스페이스 상세는 소유자 이메일·크레딧·최근 변환을 담고 본문 필드가 없다")
    fun `워크스페이스 상세는 본문을 담지 않는다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)

        val response = get(admin, "/admin/workspaces/$workspaceId")

        assertThat(response.statusCode()).isEqualTo(200)
        val bodyText = response.body()
        assertThat(bodyText).doesNotContain("easy_text")
        assertThat(bodyText).doesNotContain("masked_items")
        assertThat(bodyText).doesNotContain("edited_text")
        val bodyKeys = bodyOf(response).keys.map { it.toString() }.toSet()
        assertThat(bodyKeys).contains("summary", "transactions", "invoice_requests", "recent_conversions")
    }

    @Test
    @DisplayName("없는 워크스페이스 상세는 404다 — 관리자 전용이라 존재 은닉이 아니다")
    fun `없는 워크스페이스는 404다`() {
        val admin = newVerifiedAdminAccount()

        val response = get(admin, "/admin/workspaces/${UUID.randomUUID()}")

        assertThat(response.statusCode()).isEqualTo(404)
    }

    @Test
    @DisplayName("크레딧 조정은 actor_user_id에 관리자 id를 남긴다")
    fun `크레딧 조정은 관리자 id를 감사 흔적으로 남긴다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)

        val response = postJson(admin, "/admin/workspaces/$workspaceId/credits", creditBody(50, "manual"))

        assertThat(response.statusCode()).isEqualTo(200)
        val body = bodyOf(response)
        assertThat(body["balance"]).isEqualTo(50)

        val adminId = subjectOf(admin)
        val actorId =
            database.queryFirstColumn(
                "SELECT actor_user_id FROM credit_transactions WHERE workspace_id = '$workspaceId' " +
                    "ORDER BY created_at DESC LIMIT 1",
            )
        assertThat(actorId).containsExactly(adminId)
    }

    @Test
    @DisplayName("credits가 0이면 422다")
    fun `크레딧 조정 0은 422다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)

        val response = postJson(admin, "/admin/workspaces/$workspaceId/credits", creditBody(0, "manual"))

        assertThat(response.statusCode()).isEqualTo(422)
    }

    @Test
    @DisplayName("세금계산서 처리는 handled_by에 관리자 id를 남기고 요청자에게 메일을 보낸다")
    fun `세금계산서 처리는 handled_by 를 남기고 메일을 보낸다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)
        val requestId = createInvoiceRequest(userToken, workspaceId, "requester-${UUID.randomUUID()}@example.test")

        val response = postJson(admin, "/admin/invoice-requests/$requestId/handle", handleBody("issued"))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(bodyOf(response)["status"]).isEqualTo("issued")

        val adminId = subjectOf(admin)
        val handledBy = database.queryFirstColumn("SELECT handled_by FROM invoice_requests WHERE id = '$requestId'")
        assertThat(handledBy).containsExactly(adminId)

        val statusMails = mailSender.sent.filter { it.subject.contains("발급") }
        assertThat(statusMails).isNotEmpty()
    }

    @Test
    @DisplayName("이미 처리된 요청을 다시 처리하면 409다")
    fun `이미 처리된 요청은 409다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)
        val requestId = createInvoiceRequest(userToken, workspaceId, "requester2-${UUID.randomUUID()}@example.test")
        postJson(admin, "/admin/invoice-requests/$requestId/handle", handleBody("issued"))

        val second = postJson(admin, "/admin/invoice-requests/$requestId/handle", handleBody("rejected"))

        assertThat(second.statusCode()).isEqualTo(409)
    }

    @Test
    @DisplayName("세금계산서 목록은 status로 좁혀진다")
    fun `세금계산서 목록은 status로 좁혀진다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)
        val requestId = createInvoiceRequest(userToken, workspaceId, "requester3-${UUID.randomUUID()}@example.test")

        val requestedList = get(admin, "/admin/invoice-requests?status=requested&page=1&size=100")
        val items = (bodyOf(requestedList)["items"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertThat(items).contains(requestId)

        postJson(admin, "/admin/invoice-requests/$requestId/handle", handleBody("issued"))
        val stillRequested = get(admin, "/admin/invoice-requests?status=requested&page=1&size=100")
        val stillItems = (bodyOf(stillRequested)["items"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertThat(stillItems).doesNotContain(requestId)
    }

    @Test
    @DisplayName("오류 화면은 실패 변환을 failure_code별 건수와 최근 목록으로 낸다 — 본문 없음")
    fun `오류 화면은 실패 변환을 집계한다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)
        val code = "ProbeFailure-${UUID.randomUUID()}"
        seedFailedConversion(workspaceId, code)

        val response = get(admin, "/admin/errors")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = bodyOf(response)
        val counts = (body["counts"] as List<*>).map { it as Map<*, *> }
        assertThat(counts.any { it["failure_code"] == code }).isTrue()
        assertThat(response.body()).doesNotContain("easy_text")
    }

    @Test
    @DisplayName("오류 화면은 llm_calls의 provider 실패도 failure_class별 건수로 낸다 (V18)")
    fun `오류 화면은 provider 실패를 집계한다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)
        val failureClass = "ProbeProviderError-${UUID.randomUUID()}"
        seedProviderFailureCall(workspaceId, failureClass)

        val response = get(admin, "/admin/errors")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = bodyOf(response)
        val providerFailures = (body["provider_failures"] as List<*>).map { it as Map<*, *> }
        assertThat(providerFailures.any { it["failure_class"] == failureClass }).isTrue()
    }

    @Test
    @DisplayName("사용량 화면은 워크스페이스별 사용량 행을 JSON으로 낸다")
    fun `사용량 화면은 JSON을 낸다`() {
        val admin = newVerifiedAdminAccount()

        val response = get(admin, "/admin/usage")

        assertThat(response.statusCode()).isEqualTo(200)
        val bodyKeys = bodyOf(response).keys.map { it.toString() }.toSet()
        assertThat(bodyKeys).contains("rows", "from", "to")
    }

    @Test
    @DisplayName("공지 CRUD — 만들고 목록에 뜨고 수정하면 반영된다")
    fun `공지를 만들고 수정한다`() {
        val admin = newVerifiedAdminAccount()

        val created = postJson(admin, "/admin/announcements", announcementBody("점검 예정 안내"))
        assertThat(created.statusCode()).isEqualTo(201)
        val createdBody = bodyOf(created)
        assertThat(createdBody["active"]).isEqualTo(true)
        val id = createdBody["id"].toString()

        val list = get(admin, "/admin/announcements")
        val ids = (bodyOf(list)["items"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertThat(ids).contains(id)

        val updated = patchJson(admin, "/admin/announcements/$id", """{"active": false}""")
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(bodyOf(updated)["active"]).isEqualTo(false)
    }

    @Test
    @DisplayName("body·active 둘 다 없는 수정은 아무것도 바꾸지 않는다 — updated_at도 그대로다")
    fun `무변경 수정은 updated_at을 건드리지 않는다`() {
        val admin = newVerifiedAdminAccount()
        val created = postJson(admin, "/admin/announcements", announcementBody("무변경 확인용 공지"))
        val id = bodyOf(created)["id"].toString()
        val updatedAtBefore = bodyOf(created)["updated_at"]

        val response = patchJson(admin, "/admin/announcements/$id", "{}")

        assertThat(response.statusCode()).isEqualTo(200)
        val body = bodyOf(response)
        assertThat(body["body"]).isEqualTo("무변경 확인용 공지")
        assertThat(body["active"]).isEqualTo(true)
        assertThat(body["updated_at"]).isEqualTo(updatedAtBefore)
    }

    @Test
    @DisplayName("빈 공지 본문은 422다")
    fun `빈 공지 본문은 422다`() {
        val admin = newVerifiedAdminAccount()

        val response = postJson(admin, "/admin/announcements", announcementBody("   "))

        assertThat(response.statusCode()).isEqualTo(422)
    }

    @Test
    @DisplayName("활성 공지는 인증된 사용자면 관리자가 아니어도 볼 수 있고, 비활성은 빠진다")
    fun `활성 공지 목록은 관리자 전용이 아니다`() {
        val admin = newVerifiedAdminAccount()
        val activeCreated = postJson(admin, "/admin/announcements", announcementBody("활성 공지-${UUID.randomUUID()}"))
        val activeId = bodyOf(activeCreated)["id"].toString()
        val inactiveCreated = postJson(admin, "/admin/announcements", announcementBody("비활성 공지-${UUID.randomUUID()}"))
        val inactiveId = bodyOf(inactiveCreated)["id"].toString()
        patchJson(admin, "/admin/announcements/$inactiveId", """{"active": false}""")

        val plainUser = newVerifiedAccount()
        val response = get(plainUser, "/announcements/active")

        assertThat(response.statusCode()).isEqualTo(200)
        val ids = (bodyOf(response)["items"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertThat(ids).contains(activeId)
        assertThat(ids).doesNotContain(inactiveId)
    }

    @Test
    @DisplayName("토큰 없이 활성 공지를 부르면 401이다")
    fun `토큰 없는 활성 공지 조회는 401이다`() {
        assertThat(get(null, "/announcements/active").statusCode()).isEqualTo(401)
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun creditBody(
        credits: Int,
        reason: String,
    ): String = json.writeValueAsString(mapOf("credits" to credits, "reason" to reason))

    private fun handleBody(status: String): String = json.writeValueAsString(mapOf("status" to status))

    private fun announcementBody(body: String): String = json.writeValueAsString(mapOf("body" to body))

    private fun createInvoiceRequest(
        token: String,
        workspaceId: String,
        contactEmail: String,
    ): String {
        val body =
            json.writeValueAsString(
                mapOf(
                    "business_number" to VALID_BUSINESS_NUMBER,
                    "company_name" to "쉬운글 주식회사",
                    "representative_name" to "홍길동",
                    "contact_email" to contactEmail,
                    "address" to "서울시 어딘가",
                    "period_from" to "2026-08-01",
                    "period_to" to "2026-08-31",
                ),
            )
        val response = postJson(token, "/workspaces/$workspaceId/invoice-requests", body)
        return bodyOf(response)["id"].toString()
    }

    private fun renameDefaultWorkspace(
        token: String,
        name: String,
    ) {
        val workspaceId = defaultWorkspaceId(token)
        val body = json.writeValueAsString(mapOf("name" to name))
        send(jsonRequest("/workspaces/$workspaceId", token).method("PATCH", bodyPublisher(body)))
    }

    private fun seedFailedConversion(
        workspaceId: String,
        failureCode: String,
    ) {
        val documentId = UUID.randomUUID().toString()
        val conversionId = UUID.randomUUID().toString()
        database.execute(
            """
            INSERT INTO documents (id, user_id, title, source_format, source_text_encrypted, encryption_scheme,
                key_version, char_count, workspace_id)
            SELECT '$documentId', user_id, '오류 문서', 'text', '\x00'::bytea, 'aes256gcm-v1', 1, 4, id
            FROM workspaces WHERE id = '$workspaceId'
            """.trimIndent(),
        )
        database.execute(
            """
            INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version, failure_code)
            VALUES ('$conversionId', '$documentId', 'failed', 'aes256gcm-v1', 1, '$failureCode')
            """.trimIndent(),
        )
    }

    /** `llm_calls`(V18)에 provider_error 행 하나를 심는다 — `/admin/errors`의 `provider_failures` 대상. */
    private fun seedProviderFailureCall(
        workspaceId: String,
        failureClass: String,
    ) {
        val callId = UUID.randomUUID().toString()
        database.execute(
            """
            INSERT INTO llm_calls (id, workspace_id, user_id, purpose, provider, model, input_tokens, output_tokens,
                char_count, document_char_count, outcome, failure_class)
            SELECT '$callId', id, user_id, 'convert', 'anthropic', NULL, 0, 0, 10, 10,
                'provider_error', '$failureClass'
            FROM workspaces WHERE id = '$workspaceId'
            """.trimIndent(),
        )
    }

    /** 가입 + 이메일 검증까지 마친 계정의 토큰. */
    private fun newVerifiedAccount(): String = newAccount(verifyEmail = true)

    /** 검증된 계정을 만들고 DB에서 바로 `is_admin = true`로 만든다(`admin-grant` 프로필을 흉내 낸다). */
    private fun newVerifiedAdminAccount(): String {
        val token = newAccount(verifyEmail = true)
        database.execute("UPDATE users SET is_admin = true WHERE email = '$lastEmail'")
        return token
    }

    private fun newAccount(verifyEmail: Boolean): String {
        val email = "admin-reach${counter++}@example.test"
        lastEmail = email
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(bodyPublisher(credentials)))
        if (verifyEmail) {
            database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        }
        val login = send(jsonRequest("/auth/login", null).POST(bodyPublisher(credentials)))
        return bodyOf(login)["access_token"].toString()
    }

    private fun defaultWorkspaceId(token: String): String {
        val response = get(token, "/workspaces")
        val items = bodyOf(response)["items"] as List<*>
        return (items.single() as Map<*, *>)["id"].toString()
    }

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun get(
        token: String?,
        path: String,
    ): HttpResponse<String> = send(jsonRequest(path, token).GET())

    private fun postJson(
        token: String?,
        path: String,
        body: String,
    ): HttpResponse<String> = send(jsonRequest(path, token).POST(bodyPublisher(body)))

    private fun patchJson(
        token: String?,
        path: String,
        body: String,
    ): HttpResponse<String> = send(jsonRequest(path, token).method("PATCH", bodyPublisher(body)))

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
        private const val VALID_PASSWORD = "correct horse battery"

        /** `220-81-62517` — 체크섬을 통과하는 값(`BusinessNumberTest`와 같은 값 형태). */
        private const val VALID_BUSINESS_NUMBER = "2208162517"

        private var counter = 0
        private var lastEmail = ""

        /** 계약 경로 변수 자리 — 스윕이 무작위 UUID로 채운다. */
        private val PATH_VARIABLE = Regex("\\{[^}]+\\}")

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("admin_reach") }

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
const val ADMIN_REACH_TEST_SECRET: String = "admin-reach-test-signing-key-0123456789-abcdef"

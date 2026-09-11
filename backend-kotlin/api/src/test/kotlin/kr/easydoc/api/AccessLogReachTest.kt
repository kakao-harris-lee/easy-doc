package kr.easydoc.api

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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 접속기록(계획 `docs/plans/2026-09-11-access-log-retention.md`) 실측 계약 — 실물
 * PostgreSQL. `AdminReachTest`와 같은 형태 — 이 파일은 **관리자 API 경계 하나가 성공·거절
 * 모두 남기는지, 일반 이용자 요청이 이 표를 오염시키지 않는지**만 잰다. 유스케이스
 * 분기(outcome 매핑)는 단위 테스트(`RecordPersonalDataAccessTest`)가 이미 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$ACCESS_LOG_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessLogReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("관리자의 목록 조회 성공은 operationId·outcome=success 로 한 건 남는다")
    fun `성공 접근은 한 건 남는다`() {
        val admin = newVerifiedAdminAccount()
        val adminId = subjectOf(admin)
        val before = countRows()

        val response = get(admin, "/admin/workspaces")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(countRows()).isEqualTo(before + 1)
        val row = latestRow()
        assertThat(row.actorId).isEqualTo(adminId)
        assertThat(row.operation).isEqualTo("listAdminWorkspaces")
        assertThat(row.outcome).isEqualTo("success")
        assertThat(row.clientIp).isNotBlank()
    }

    @Test
    @DisplayName("관리자가 아닌 요청의 403도 outcome=rejected 로 남는다")
    fun `거절된 접근도 남는다`() {
        val plainUser = newVerifiedAccount()
        val userId = subjectOf(plainUser)
        val before = countRows()

        val response = get(plainUser, "/admin/workspaces")

        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(countRows()).isEqualTo(before + 1)
        val row = latestRow()
        assertThat(row.actorId).isEqualTo(userId)
        assertThat(row.operation).isEqualTo("listAdminWorkspaces")
        assertThat(row.outcome).isEqualTo("rejected")
    }

    @Test
    @DisplayName("관리자 전용이 아닌 요청은 이 표를 오염시키지 않는다")
    fun `일반 엔드포인트는 기록되지 않는다`() {
        val userToken = newVerifiedAccount()
        val before = countRows()

        val response = get(userToken, "/workspaces")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(countRows()).isEqualTo(before)
    }

    /**
     * FK 회귀 고정판 — `actor_user_id`에 FK를 걸었던 첫 시도(계획
     * `docs/plans/2026-09-11-access-log-retention.md` §3.1)가 만든 결함이다. 일반
     * 이용자가 관리자 전용 경로에서 403을 받으면 그 사람의 id가 이 표에 남는데, FK가
     * 있으면 그 뒤 탈퇴(`POST /auth/me/deletion`)가 FK 위반으로 500이 되어 **법정
     * 삭제권을 깨는 회귀**였다 — `actor_user_id`가 FK 없는 값임을 실 DB로 고정한다.
     */
    @Test
    @DisplayName("403을 받은 일반 이용자도 그 뒤 탈퇴가 성공한다 — actor_user_id FK 회귀 고정")
    fun `403 이후에도 탈퇴가 성공한다`() {
        val plainUser = newVerifiedAccount()

        val rejected = get(plainUser, "/admin/workspaces")
        assertThat(rejected.statusCode()).isEqualTo(403)

        val deletion =
            postJson(
                plainUser,
                "/auth/me/deletion",
                json.writeValueAsString(
                    mapOf("password" to VALID_PASSWORD, "confirmation" to "탈퇴합니다"),
                ),
            )

        assertThat(deletion.statusCode())
            .withFailMessage(
                "탈퇴가 실패했다(%d): %s — actor_user_id에 FK가 남아 있으면 500이 된다",
                deletion.statusCode(),
                deletion.body(),
            ).isEqualTo(204)
    }

    @Test
    @DisplayName("단건 자원 조회는 subject_scope 에 경로 변수(workspace_id)가 실린다")
    fun `단건 조회는 경로 변수를 subject_scope 로 남긴다`() {
        val admin = newVerifiedAdminAccount()
        val userToken = newVerifiedAccount()
        val workspaceId = defaultWorkspaceId(userToken)

        val response = get(admin, "/admin/workspaces/$workspaceId")

        assertThat(response.statusCode()).isEqualTo(200)
        val row = latestRow()
        assertThat(row.operation).isEqualTo("readAdminWorkspace")
        assertThat(row.subjectScope).contains(workspaceId)
    }

    /**
     * 허용 목록 회귀 고정판 — 계약이 `listAdminWorkspaces`의 `q`를 「이름·소유자 이메일
     * 부분 일치」로 정의하므로, 운영자가 이메일로 검색하면 그 이메일(제3자의 것)이
     * `subject_scope`에 그대로 실릴 뻔했다(`AdminAccessSubjectScope` 첫 구현 — 쿼리
     * 문자열을 통째로 남겼다). 값을 지우고 키만 남는지 실 DB로 고정한다.
     */
    @Test
    @DisplayName("검색어(q)는 subject_scope 에 값 없이 키만 남는다 — 이메일 등 개인정보를 가둔다")
    fun `검색어는 값이 지워진다`() {
        val admin = newVerifiedAdminAccount()
        val searchedEmail = "third-party-${counter++}@example.test"

        val response = get(admin, "/admin/workspaces?q=" + URLEncoder.encode(searchedEmail, Charsets.UTF_8))

        assertThat(response.statusCode()).isEqualTo(200)
        val row = latestRow()
        assertThat(row.subjectScope)
            .withFailMessage("검색어(제3자 이메일)가 subject_scope 에 그대로 실렸다: %s", row.subjectScope)
            .doesNotContain(searchedEmail)
        assertThat(row.subjectScope).contains("q=<생략>")
    }

    // ------------------------------------------------------------------ 헬퍼

    private data class Row(
        val actorId: String,
        val operation: String,
        val subjectScope: String?,
        val outcome: String,
        val clientIp: String,
    )

    private fun countRows(): Int = database.queryInt("SELECT count(*) FROM personal_data_access_logs")

    private fun latestRow(): Row {
        val columns =
            database.queryFirstColumn(
                "SELECT actor_user_id || '|' || operation || '|' || coalesce(subject_scope, '') || '|' || " +
                    "outcome || '|' || client_ip FROM personal_data_access_logs ORDER BY accessed_at DESC LIMIT 1",
            )
        val parts = columns.single().split("|")
        return Row(
            actorId = parts[0],
            operation = parts[1],
            subjectScope = parts[2].ifEmpty { null },
            outcome = parts[3],
            clientIp = parts[4],
        )
    }

    private fun newVerifiedAccount(): String = newAccount(verifyEmail = true)

    private fun newVerifiedAdminAccount(): String {
        val token = newAccount(verifyEmail = true)
        database.execute("UPDATE users SET is_admin = true WHERE email = '$lastEmail'")
        return token
    }

    private fun newAccount(verifyEmail: Boolean): String {
        val email = "access-log-reach${counter++}@example.test"
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

        private var counter = 0
        private var lastEmail = ""

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("access_log_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

const val ACCESS_LOG_REACH_TEST_SECRET: String = "access-log-reach-test-signing-key-0123456789-abcdef"

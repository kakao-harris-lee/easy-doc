package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryAccountDeletionRepository
import kr.easydoc.application.account.DeleteAccountService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * `POST /auth/me/deletion`의 계약 — 계획 `docs/plans/2026-09-09-account-deletion.md`,
 * 계약 2.27.0. 실 FK 동작(행 수·CASCADE·`llm_calls` SET NULL)은
 * `JdbcAccountDeletionRepositoryTest`(실 PostgreSQL)가 맡는다 — 이 슬라이스는 HTTP
 * 배선(상태 코드·헤더·바디 모양·재확인 검증 순서)만 잰다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class AccountDeletionContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var accounts: InMemoryAccountDeletionRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName("비밀번호 계정이 올바른 비밀번호·확인 문구로 탈퇴하면 204이고, 같은 토큰의 다음 요청은 401이다 — 수용 기준 1")
    fun `비밀번호 계정 탈퇴는 204다`() {
        val email = uniqueEmail()
        signup(email)
        val bearer = login(email)

        val response = deleteAccount(bearer, PASSWORD, DeleteAccountService.CONFIRMATION_PHRASE)

        assertDeclaredStatus(response, NO_CONTENT, DELETE_PATH, POST)
        assertPrivateHeaders204(response)

        val next = getAuthorized("/auth/me", bearer)
        assertDeclaredStatus(next, UNAUTHORIZED, ME_PATH, GET)
    }

    @Test
    @DisplayName("소셜 전용 계정은 확인 문구만으로 탈퇴된다 — 수용 기준 2")
    fun `소셜 전용 계정은 비밀번호 없이 탈퇴된다`() {
        val bearer = socialOnlyBearer()

        val response = deleteAccount(bearer, password = null, DeleteAccountService.CONFIRMATION_PHRASE)

        assertDeclaredStatus(response, NO_CONTENT, DELETE_PATH, POST)
        assertDeclaredStatus(getAuthorized("/auth/me", bearer), UNAUTHORIZED, ME_PATH, GET)
    }

    @Test
    @DisplayName("비밀번호가 틀리면 422이고 계정은 그대로 남는다 — 401이 아니다, 수용 기준 3")
    fun `비밀번호가 틀리면 422다`() {
        val email = uniqueEmail()
        signup(email)
        val bearer = login(email)

        val response = deleteAccount(bearer, "wrong-password", DeleteAccountService.CONFIRMATION_PHRASE)

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, DELETE_PATH, POST)
        assertThat(detailText(response)).isEqualTo(DeleteAccountService.PASSWORD_MISMATCH_MESSAGE)
        // 계정이 지워지지 않았다 — 같은 토큰이 여전히 유효하다.
        assertDeclaredStatus(getAuthorized("/auth/me", bearer), OK, ME_PATH, GET)
    }

    @Test
    @DisplayName("확인 문구가 다르면 422이고 계정은 그대로 남는다 — 수용 기준 3")
    fun `확인 문구가 다르면 422다`() {
        val email = uniqueEmail()
        signup(email)
        val bearer = login(email)

        val response = deleteAccount(bearer, PASSWORD, "확인 안 함")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, DELETE_PATH, POST)
        assertThat(detailText(response)).isEqualTo(DeleteAccountService.CONFIRMATION_MISMATCH_MESSAGE)
        assertDeclaredStatus(getAuthorized("/auth/me", bearer), OK, ME_PATH, GET)
    }

    @Test
    @DisplayName("관리자 계정은 409이고 그대로 남는다 — 수용 기준 4")
    fun `관리자 계정은 409다`() {
        val email = uniqueEmail()
        val userId = signup(email)
        val bearer = login(email)
        accounts.markAdmin(java.util.UUID.fromString(userId))

        val response = deleteAccount(bearer, PASSWORD, DeleteAccountService.CONFIRMATION_PHRASE)

        assertDeclaredStatus(response, CONFLICT, DELETE_PATH, POST)
        assertThat(detailText(response)).isEqualTo(DeleteAccountService.ADMIN_CANNOT_DELETE_MESSAGE)
        assertDeclaredStatus(getAuthorized("/auth/me", bearer), OK, ME_PATH, GET)
    }

    @Test
    @DisplayName("확인 문구 누락은 422 배열이다")
    fun `확인 문구 누락은 422다`() {
        val email = uniqueEmail()
        signup(email)
        val bearer = login(email)

        val response = postAuthorized(DELETE_PATH, bearer, json.writeValueAsString(mapOf("password" to PASSWORD)))

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, DELETE_PATH, POST)
        assertThat(body(response)["detail"]).isInstanceOf(List::class.java)
    }

    @Test
    @DisplayName("토큰 없이는 401이다")
    fun `인증 없이는 401이다`() {
        val response =
            postJson(
                DELETE_PATH,
                json.writeValueAsString(mapOf("confirmation" to DeleteAccountService.CONFIRMATION_PHRASE)),
            )

        assertDeclaredStatus(response, UNAUTHORIZED, DELETE_PATH, POST)
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun deleteAccount(
        bearer: String,
        password: String?,
        confirmation: String,
    ): MockHttpServletResponse {
        val payload =
            buildMap {
                if (password != null) put("password", password)
                put("confirmation", confirmation)
            }
        return postAuthorized(DELETE_PATH, bearer, json.writeValueAsString(payload))
    }

    private fun socialOnlyBearer(): String {
        val start =
            postJson(
                "/auth/oauth/google/start",
                json.writeValueAsString(mapOf("redirect_uri" to REDIRECT_URI)),
            )
        val state = body(start)["state"] as String
        val id = counter++
        val callback =
            postJson(
                "/auth/oauth/google/callback",
                json.writeValueAsString(
                    mapOf(
                        "code" to "google-del-sub-$id|social-del$id@example.test|true",
                        "state" to state,
                        "redirect_uri" to REDIRECT_URI,
                    ),
                ),
            )
        return body(callback)["access_token"] as String
    }

    private fun postJson(
        path: String,
        payload: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andReturn()
            .response

    private fun postAuthorized(
        path: String,
        bearer: String,
        payload: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                content = payload
            }.andReturn()
            .response

    private fun getAuthorized(
        path: String,
        bearer: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
            }.andReturn()
            .response

    private fun credentials(email: String): String =
        json.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))

    private fun signup(email: String): String = body(postJson("/auth/signup", credentials(email)))["id"] as String

    private fun login(email: String): String =
        body(postJson("/auth/login", credentials(email)))["access_token"] as String

    private fun uniqueEmail(): String = "account-deletion${counter++}@example.test"

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun detailText(response: MockHttpServletResponse): String =
        body(response)["detail"] as? String
            ?: error("detail 이 문자열이 아니다: ${response.getContentAsString(StandardCharsets.UTF_8)}")

    private fun assertDeclaredStatus(
        response: MockHttpServletResponse,
        status: Int,
        path: String,
        method: String,
    ) {
        assertThat(response.status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다", method, path, status)
            .contains(status.toString())
    }

    private fun assertPrivateHeaders204(response: MockHttpServletResponse) {
        val declared = ContractSpec.responseHeaderNames(DELETE_PATH, POST, NO_CONTENT)
        val expected = ContractSpec.globalHeaderValues()
        declared.forEach { header ->
            assertThat(response.getHeaders(header)).containsExactly(expected.getValue(header))
        }
    }

    private companion object {
        const val DELETE_PATH = "/auth/me/deletion"
        const val ME_PATH = "/auth/me"
        const val POST = "post"
        const val GET = "get"

        const val OK = 200
        const val NO_CONTENT = 204
        const val UNAUTHORIZED = 401
        const val CONFLICT = 409
        const val UNPROCESSABLE_CONTENT = 422

        const val PASSWORD = "correct-password-1"
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"

        var counter = 0
    }
}

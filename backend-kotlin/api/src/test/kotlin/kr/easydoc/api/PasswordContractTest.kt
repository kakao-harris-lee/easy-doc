package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.application.auth.PasswordResetService
import kr.easydoc.application.auth.PasswordService
import kr.easydoc.application.document.EMAIL_VERIFICATION_REQUIRED_MESSAGE
import kr.easydoc.infrastructure.mail.FakeMailSender
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
import java.util.regex.Pattern

/**
 * `POST /auth/password` · `POST /auth/password-reset/{request,confirm}` 의 계약 —
 * backlog §1.4 다음 조각, 계약 2.19.0.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class PasswordContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mailSender: FakeMailSender

    @Autowired
    private lateinit var users: InMemoryUserRepository

    private val json = ObjectMapper()

    // ------------------------------------------------------------------ setPassword

    @Test
    @DisplayName("비밀번호 없는 계정은 새 비밀번호를 만들 수 있다 — 204 · 사적 헤더 · 알림 메일 한 통")
    fun `비밀번호 설정 성공은 204다`() {
        val bearer = socialOnlyBearer()
        val email = body(getAuthorized("/auth/me", bearer))["email"] as String

        val response = setPassword(bearer, "new-password-1")

        assertDeclaredStatus(response, NO_CONTENT, SET_PASSWORD_PATH, POST)
        assertPrivateHeaders204(response)
        assertThat(body(getAuthorized("/auth/me", bearer))["has_password"]).isEqualTo(true)
        assertThat(mailSentTo(email)).containsExactly(PasswordService.CREATED_SUBJECT)
    }

    @Test
    @DisplayName("이메일이 인증되지 않은 계정은 403이다 — createDocument 게이트와 같은 문구, 알림 메일 없음")
    fun `이메일 미인증이면 403이다`() {
        // 구글·카카오는 이메일 검증을 요구해 가입이 되므로, 네이버 대역(항상 미검증을
        // 낸다, `FakeNaverSocialLoginProvider` KDoc)으로 비밀번호 없는 미인증 계정을 만든다.
        val bearer = socialOnlyUnverifiedBearer()
        val me = body(getAuthorized("/auth/me", bearer))
        check(me["email_verified"] == false) { "전제 실패 — 네이버 대역 계정이 이미 인증된 채로 만들어졌다" }
        val email = me["email"] as String

        val response = setPassword(bearer, "new-password-1")

        assertDeclaredStatus(response, FORBIDDEN, SET_PASSWORD_PATH, POST)
        assertThat(detailText(response)).isEqualTo(EMAIL_VERIFICATION_REQUIRED_MESSAGE)
        assertThat(body(getAuthorized("/auth/me", bearer))["has_password"]).isEqualTo(false)
        // 미검증 네이버 가입 자체가 이메일 인증 코드 메일을 이미 보냈다(SocialLoginService.callback)
        // — 거절된 설정 요청이 그 위에 알림 메일을 더 보태지 않았는지만 본다.
        assertThat(mailSentTo(email)).doesNotContain(PasswordService.CREATED_SUBJECT)
    }

    @Test
    @DisplayName("이미 비밀번호가 있는 계정은 409다 — 알림 메일 없음")
    fun `이미 비밀번호가 있으면 409다`() {
        val email = uniqueEmail()
        signup(email)
        users.verifyEmailFor(email)
        val bearer = login(email)

        val response = setPassword(bearer, "new-password-1")

        assertDeclaredStatus(response, CONFLICT, SET_PASSWORD_PATH, POST)
        assertThat(detailText(response)).isEqualTo(PasswordService.ALREADY_HAS_PASSWORD_MESSAGE)
        // signup 자체가 이메일 인증 코드 메일을 이미 보냈다 — 거절된 설정 요청이 그 위에
        // 알림 메일을 더 보태지 않았는지만 본다.
        assertThat(mailSentTo(email)).doesNotContain(PasswordService.CREATED_SUBJECT)
    }

    @Test
    @DisplayName("8자 미만 비밀번호는 422 문자열이다")
    fun `짧은 비밀번호는 422다`() {
        val bearer = socialOnlyBearer()

        val response = setPassword(bearer, "short")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SET_PASSWORD_PATH, POST)
        assertThat(detailText(response)).isEqualTo(ContractSpec.requestFieldConstraint(NEW_PASSWORD_FIELD).singleDetail)
    }

    @Test
    @DisplayName("토큰 없이는 401이다")
    fun `인증 없이는 401이다`() {
        val response = postJson(SET_PASSWORD_PATH, json.writeValueAsString(mapOf("new_password" to "new-password-1")))

        assertDeclaredStatus(response, UNAUTHORIZED, SET_PASSWORD_PATH, POST)
    }

    // ------------------------------------------------------------------ passwordResetRequest

    @Test
    @DisplayName("존재하는 이메일의 요청은 202이고 코드 메일을 한 통 보낸다")
    fun `요청은 202이고 메일을 보낸다`() {
        val email = uniqueEmail()
        signup(email)

        val response = requestReset(email)

        assertDeclaredStatus(response, ACCEPTED, REQUEST_PATH, POST)
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty()
        val code = latestResetCode(email)
        assertThat(code).hasSize(6)
    }

    @Test
    @DisplayName("존재하지 않는 이메일도 202다 — 응답이 존재하는 이메일과 완전히 같다")
    fun `모르는 이메일도 202이고 응답이 같다`() {
        val known = uniqueEmail()
        signup(known)

        val knownResponse = requestReset(known)
        val unknownResponse = requestReset(uniqueEmail())

        assertThat(unknownResponse.status).isEqualTo(knownResponse.status)
        assertThat(unknownResponse.contentAsByteArray).isEqualTo(knownResponse.contentAsByteArray)
        assertDeclaredStatus(unknownResponse, ACCEPTED, REQUEST_PATH, POST)
    }

    @Test
    @DisplayName("필수 필드(email) 누락은 422 배열이다")
    fun `이메일 누락은 422다`() {
        val response = postJson(REQUEST_PATH, "{}")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, REQUEST_PATH, POST)
        assertThat(body(response)["detail"]).isInstanceOf(List::class.java)
    }

    // ------------------------------------------------------------------ passwordResetConfirm

    @Test
    @DisplayName("정답 코드 확인은 200 · TokenResponse · has_password/email_verified가 참이 된다")
    fun `정답 확인은 토큰을 발급한다`() {
        val email = uniqueEmail()
        val userId = signup(email)
        requestReset(email)
        val code = latestResetCode(email)

        val response = confirmReset(email, code, "brand-new-password")

        assertDeclaredStatus(response, OK, CONFIRM_PATH, POST)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("TokenResponse"))
        val newBearer = body(response)["access_token"] as String

        val me = body(getAuthorized("/auth/me", newBearer))
        assertThat(me["id"]).isEqualTo(userId)
        assertThat(me["has_password"]).isEqualTo(true)
        assertThat(me["email_verified"]).isEqualTo(true)
        // signup 자체가 이메일 인증 코드 메일을 먼저 보냈다 — 재설정 코드 메일(요청)과
        // 변경 알림 메일이 그 뒤에 순서대로 이어진다.
        assertThat(mailSentTo(email))
            .endsWith(PasswordResetService.REQUEST_SUBJECT, PasswordResetService.CHANGED_SUBJECT)
    }

    @Test
    @DisplayName("비밀번호 없는(소셜 전용) 계정도 재설정으로 비밀번호가 생긴다")
    fun `소셜 전용 계정도 재설정으로 비밀번호가 생긴다`() {
        val bearer = socialOnlyBearer()
        val email = body(getAuthorized("/auth/me", bearer))["email"] as String
        requestReset(email)
        val code = latestResetCode(email)

        val response = confirmReset(email, code, "brand-new-password")

        assertDeclaredStatus(response, OK, CONFIRM_PATH, POST)
        val newBearer = body(response)["access_token"] as String
        assertThat(body(getAuthorized("/auth/me", newBearer))["has_password"]).isEqualTo(true)
    }

    @Test
    @DisplayName("오답 코드는 401이고, 모르는 이메일과 같은 문구다 (사유 은닉)")
    fun `오답과 모르는 이메일이 같은 401이다`() {
        val email = uniqueEmail()
        signup(email)
        requestReset(email)

        val wrongCode = confirmReset(email, "000000", "brand-new-password")
        val unknownEmail = confirmReset(uniqueEmail(), "000000", "brand-new-password")

        assertDeclaredStatus(wrongCode, UNAUTHORIZED, CONFIRM_PATH, POST)
        assertThat(detailText(wrongCode)).isEqualTo(PasswordResetService.INVALID_RESET_CODE_MESSAGE)
        assertThat(wrongCode.status).isEqualTo(unknownEmail.status)
        assertThat(detailText(unknownEmail)).isEqualTo(detailText(wrongCode))
        assertThat(wrongCode.getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .isEqualTo(unknownEmail.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        // signup의 인증 코드 메일에 이어 재설정 코드 메일(요청)까지가 전부다 — 실패한
        // 확인 둘 다 변경 알림을 보내지 않는다.
        assertThat(mailSentTo(email)).endsWith(PasswordResetService.REQUEST_SUBJECT)
        assertThat(mailSentTo(email)).doesNotContain(PasswordResetService.CHANGED_SUBJECT)
    }

    @Test
    @DisplayName("필수 필드 누락은 422 배열이다")
    fun `재설정 확인 필드 누락은 422다`() {
        val response = postJson(CONFIRM_PATH, "{}")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CONFIRM_PATH, POST)
        assertThat(body(response)["detail"]).isInstanceOf(List::class.java)
    }

    @Test
    @DisplayName("새 비밀번호가 8자 미만이면 422 문자열이고 코드를 소비하지 않는다")
    fun `짧은 새 비밀번호는 422이고 코드가 남는다`() {
        val email = uniqueEmail()
        signup(email)
        requestReset(email)
        val code = latestResetCode(email)

        val shortPasswordResponse = confirmReset(email, code, "short")

        assertDeclaredStatus(shortPasswordResponse, UNPROCESSABLE_CONTENT, CONFIRM_PATH, POST)
        assertThat(detailText(shortPasswordResponse))
            .isEqualTo(ContractSpec.requestFieldConstraint(RESET_PASSWORD_FIELD).singleDetail)

        val retry = confirmReset(email, code, "brand-new-password")
        assertDeclaredStatus(retry, OK, CONFIRM_PATH, POST)
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun setPassword(
        bearer: String,
        newPassword: String,
    ): MockHttpServletResponse =
        postAuthorized(SET_PASSWORD_PATH, bearer, json.writeValueAsString(mapOf("new_password" to newPassword)))

    private fun requestReset(email: String): MockHttpServletResponse =
        postJson(REQUEST_PATH, json.writeValueAsString(mapOf("email" to email)))

    private fun confirmReset(
        email: String,
        code: String,
        newPassword: String,
    ): MockHttpServletResponse =
        postJson(
            CONFIRM_PATH,
            json.writeValueAsString(mapOf("email" to email, "code" to code, "new_password" to newPassword)),
        )

    /** 발송된 메일 중 그 이메일로 간 가장 최근 것에서 6자리 코드를 뽑는다. */
    private fun latestResetCode(email: String): String {
        val mail = mailSender.sent.lastOrNull { it.to.value == email } ?: error("$email 로 보낸 메일이 없다")
        val matcher = CODE_PATTERN.matcher(mail.textBody)
        check(matcher.find()) { "메일 본문에서 6자리 코드를 찾지 못했다: ${mail.textBody}" }
        return matcher.group()
    }

    /**
     * `FakeMailSender` 빈은 이 슬라이스의 테스트 메서드 전부가 공유한다(Spring 테스트
     * 컨텍스트 캐싱) — 그래서 전체 `sent` 목록을 그대로 단언하면 다른 테스트가 보낸
     * 메일까지 섞인다. 이 이메일로 간 것만 걸러 제목 순서를 본다.
     */
    private fun mailSentTo(email: String): List<String> =
        mailSender.sent.filter { it.to.value == email }.map { it.subject }

    /** 소셜 로그인만으로 만든 계정(비밀번호 없음, 이메일 인증됨)의 Bearer 토큰. */
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
                        "code" to "google-pwd-sub-$id|social$id@example.test|true",
                        "state" to state,
                        "redirect_uri" to REDIRECT_URI,
                    ),
                ),
            )
        return body(callback)["access_token"] as String
    }

    /**
     * 소셜 로그인만으로 만든 계정(비밀번호 없음, **이메일 미인증**)의 Bearer 토큰.
     * 네이버 대역(`FakeNaverSocialLoginProvider`)을 쓴다 — 구글·카카오 대역은 이메일
     * 검증을 요구해 미인증 계정을 만들 수 없지만, 네이버는 실물처럼 항상 미검증을
     * 낸다(그 대역 KDoc).
     */
    private fun socialOnlyUnverifiedBearer(): String {
        val start =
            postJson(
                "/auth/oauth/naver/start",
                json.writeValueAsString(mapOf("redirect_uri" to NAVER_REDIRECT_URI)),
            )
        val state = body(start)["state"] as String
        val id = counter++
        val callback =
            postJson(
                "/auth/oauth/naver/callback",
                json.writeValueAsString(
                    mapOf(
                        "code" to "naver-pwd-sub-$id|social-unverified$id@example.test",
                        "state" to state,
                        "redirect_uri" to NAVER_REDIRECT_URI,
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

    /** 이메일 인증 여부는 맞추지 않는다 — 비밀번호 설정·재설정·로그인 어느 것도 인증을 요구하지 않는다. */
    private fun signup(email: String): String = body(postJson("/auth/signup", credentials(email)))["id"] as String

    private fun login(email: String): String =
        body(postJson("/auth/login", credentials(email)))["access_token"] as String

    private fun uniqueEmail(): String = "pwd-reset${counter++}@example.test"

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        body(response).keys.map { it.toString() }.toSet()

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
        val declared = ContractSpec.responseHeaderNames(SET_PASSWORD_PATH, POST, NO_CONTENT)
        val expected = ContractSpec.globalHeaderValues()
        declared.forEach { header ->
            assertThat(response.getHeaders(header)).containsExactly(expected.getValue(header))
        }
    }

    private companion object {
        const val SET_PASSWORD_PATH = "/auth/password"
        const val REQUEST_PATH = "/auth/password-reset/request"
        const val CONFIRM_PATH = "/auth/password-reset/confirm"
        const val POST = "post"

        const val OK = 200
        const val NO_CONTENT = 204
        const val ACCEPTED = 202
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val CONFLICT = 409
        const val UNPROCESSABLE_CONTENT = 422

        const val NEW_PASSWORD_FIELD = "SetPasswordRequest.new_password"
        const val RESET_PASSWORD_FIELD = "PasswordResetConfirmRequest.new_password"

        const val PASSWORD = "correct horse battery"
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val NAVER_REDIRECT_URI = "http://localhost:5173/auth/naver/callback"

        val CODE_PATTERN: Pattern = Pattern.compile("\\d{6}")

        var counter = 0
    }
}

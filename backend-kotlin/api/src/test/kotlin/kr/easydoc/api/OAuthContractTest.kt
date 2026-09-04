package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.FakeGoogleSocialLoginProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * `/auth/oauth/{provider}/start` · `/auth/oauth/{provider}/callback` 의 계약 —
 * backlog §1.4 P0-1. 실제 Google 을 부르지 않는다 —
 * `AuthSliceBeans.FakeGoogleSocialLoginProvider` 가 `code` 문자열
 * (`sub|email|verified`, 특수값 `reject`·`unreachable`)로 시나리오를 흉내 낸다.
 *
 * "제공자 미설정" 422 는 여기서 재지 않는다 — 이 슬라이스는 항상 google 이 등록된
 * 배선이다. 그 경로는 `AuthEndpointReachTest`(실물 설정, 기본값에 키가 없다)가 진다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class OAuthContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var fakeProvider: FakeGoogleSocialLoginProvider

    private val json = ObjectMapper()

    @Test
    @DisplayName("start 성공 — 계약의 성공 상태 · 사적 헤더 · 본문 키 집합이 정확히 required")
    fun `start 응답이 계약과 같다`() {
        val response = start(REDIRECT_URI)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(START_PATH, POST))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("OAuthStartResponse"))

        val body = body(response)
        assertThat(body["authorization_url"] as String).contains("state=").contains("nonce=")
        assertThat((body["state"] as String)).isNotBlank()
    }

    @Test
    @DisplayName("지원하지 않는 provider 는 422 배열이다 — 경로 값 자리 해석 실패는 스키마 층이다")
    fun `지원하지 않는 provider 는 422 다`() {
        val response = start(REDIRECT_URI, provider = "kakao")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        val detail = body(response)["detail"]
        assertThat(detail).isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        assertThat(items.map { it["loc"] }).contains(listOf("path", "provider"))
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 422 다")
    fun `허용 목록 밖 redirect_uri 는 422 다`() {
        val response = start("https://evil.example.test/callback")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        assertThat(detailText(response)).isEqualTo("허용되지 않은 redirect_uri 입니다")
    }

    @Test
    @DisplayName("start 의 빈 redirect_uri 는 422 배열이다 — minLength:1, 스키마 층")
    fun `start 의 빈 redirect_uri 는 422 배열이다`() {
        val response = start("")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        assertBodyValidationArray(response, "redirect_uri")
    }

    @Test
    @DisplayName("새 신원 콜백 성공 — 계약의 성공 상태 · TokenResponse 키 집합")
    fun `콜백 성공 응답이 계약과 같다`() {
        val state = startState()

        val response = callback(code = "sub-new-1|new@example.test|true", state = state)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(CALLBACK_PATH, POST))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("TokenResponse"))
        assertThat(body(response)["token_type"]).isEqualTo("bearer")
    }

    @Test
    @DisplayName("이미 연결된 신원은 새 계정을 만들지 않고 같은 사용자로 로그인한다")
    fun `기존 신원은 같은 사용자로 로그인한다`() {
        val firstToken = userIdOf(callback(code = "sub-repeat|repeat@example.test|true", state = startState()))
        val secondToken = userIdOf(callback(code = "sub-repeat|ignored@example.test|true", state = startState()))

        assertThat(secondToken).isEqualTo(firstToken)
    }

    @Test
    @DisplayName("같은 검증된 이메일의 계정이 이미 있으면 409 다 — 자동 연결하지 않는다")
    fun `이메일이 겹치면 409 다`() {
        callback(code = "sub-first|shared@example.test|true", state = startState())

        val response = callback(code = "sub-second|shared@example.test|true", state = startState())

        assertDeclaredStatus(response, CONFLICT, CALLBACK_PATH, POST)
        assertThat(detailText(response))
            .isEqualTo("이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.")
    }

    @Test
    @DisplayName("이메일이 없으면 422 다")
    fun `이메일 없으면 422 다`() {
        val response = callback(code = "sub-no-email||true", state = startState())

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이메일 정보를 확인할 수 없습니다")
    }

    @Test
    @DisplayName("이메일이 검증되지 않았으면 422 다")
    fun `이메일 미검증은 422 다`() {
        val response = callback(code = "sub-unverified|unverified@example.test|false", state = startState())

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이메일 정보를 확인할 수 없습니다")
    }

    @Test
    @DisplayName("제공자가 코드를 거절하면 401 이다 — WWW-Authenticate 헤더 포함")
    fun `코드 거절은 401 이다`() {
        val response = callback(code = "reject", state = startState())

        assertDeclaredStatus(response, UNAUTHORIZED, CALLBACK_PATH, POST)
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer")
        assertThat(detailText(response)).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다")
    }

    @Test
    @DisplayName("제공자에 닿지 못하면 502 다")
    fun `제공자 불통은 502 다`() {
        val response = callback(code = "unreachable", state = startState())

        assertDeclaredStatus(response, BAD_GATEWAY, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("구글에 연결하지 못했습니다")
    }

    @Test
    @DisplayName("발급하지 않은 state 는 400 이다")
    fun `없는 state 는 400 이다`() {
        val response = callback(code = "sub-x|x@example.test|true", state = "never-issued")

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("요청이 만료되었거나 이미 사용되었습니다")
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다")
    fun `state 재사용은 400 이다`() {
        val state = startState()
        callback(code = "sub-once|once@example.test|true", state = state)

        val response = callback(code = "sub-once|once@example.test|true", state = state)

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("redirect_uri 가 start 때와 다르면 400 이다")
    fun `redirect_uri 불일치는 400 이다`() {
        val state = startState()

        val response =
            postJson(
                "/auth/oauth/google/callback",
                json.writeValueAsString(
                    mapOf(
                        "code" to "sub-y|y@example.test|true",
                        "state" to state,
                        "redirect_uri" to "https://different.example.test/callback",
                    ),
                ),
            )

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("빈 code 는 422 배열이다 — 제공자를 왕복하지 않고 끊긴다")
    fun `빈 code 는 422 배열이고 제공자를 부르지 않는다`() {
        val state = startState()
        val callsBefore = fakeProvider.exchangeCallCount

        val response = callback(code = "", state = state)

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "code")
        assertThat(fakeProvider.exchangeCallCount)
            .withFailMessage("빈 code 검증 실패인데 제공자 exchange 가 불렸다 — 스키마 층에서 끊기지 않았다")
            .isEqualTo(callsBefore)
    }

    @Test
    @DisplayName("빈 state 는 422 배열이다")
    fun `빈 state 는 422 배열이다`() {
        val response = callback(code = "sub-z|z@example.test|true", state = "")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "state")
    }

    @Test
    @DisplayName("콜백의 빈 redirect_uri 는 422 배열이다")
    fun `콜백의 빈 redirect_uri 는 422 배열이다`() {
        val response = callback(code = "sub-w|w@example.test|true", state = startState(), redirectUri = "")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "redirect_uri")
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun start(
        redirectUri: String,
        provider: String = "google",
    ): MockHttpServletResponse =
        postJson(
            "/auth/oauth/$provider/start",
            json.writeValueAsString(mapOf("redirect_uri" to redirectUri)),
        )

    private fun startState(): String = body(start(REDIRECT_URI))["state"] as String

    private fun callback(
        code: String,
        state: String,
        redirectUri: String = REDIRECT_URI,
    ): MockHttpServletResponse =
        postJson(
            "/auth/oauth/google/callback",
            json.writeValueAsString(mapOf("code" to code, "state" to state, "redirect_uri" to redirectUri)),
        )

    /** `StubAccessTokens` 가 토큰을 `stub-token:<uuid>` 로 발급한다 — 접두사를 떼면 사용자 id 다. */
    private fun userIdOf(response: MockHttpServletResponse): String =
        (body(response)["access_token"] as String).removePrefix("stub-token:")

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

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        body(response).keys.map { it.toString() }.toSet()

    private fun detailText(response: MockHttpServletResponse): String =
        body(response)["detail"] as? String ?: error("detail 이 문자열이 아니다: ${body(response)}")

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

    /** 값·부착 개수만 잰다 — `AuthContractTest.assertPrivateHeaders` 와 같은 방식. */
    private fun assertPrivateHeaders(response: MockHttpServletResponse) {
        val expected = ContractSpec.globalHeaderValues()
        expected.forEach { (header, value) ->
            assertThat(response.getHeaders(header)).containsExactly(value)
        }
    }

    /** Bean Validation(스키마 층) 실패의 모양 — `[{loc, msg, type}]`, `loc` 이 그 필드를 지목한다. */
    private fun assertBodyValidationArray(
        response: MockHttpServletResponse,
        fieldName: String,
    ) {
        val detail = body(response)["detail"]
        assertThat(detail).isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        assertThat(items.map { it["loc"] })
            .withFailMessage("거절 항목이 %s 를 지목하지 않는다: %s", fieldName, items)
            .contains(listOf("body", fieldName))
    }

    private companion object {
        const val START_PATH = "/auth/oauth/{provider}/start"
        const val CALLBACK_PATH = "/auth/oauth/{provider}/callback"
        const val POST = "post"
        const val UNPROCESSABLE_CONTENT = 422
        const val CONFLICT = 409
        const val UNAUTHORIZED = 401
        const val BAD_GATEWAY = 502
        const val BAD_REQUEST = 400
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
    }
}

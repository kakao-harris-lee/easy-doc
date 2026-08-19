package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
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
 * `/auth/signup` · `/auth/login` 의 계약 — 명세 `03_contract-keeper_auth-test-spec.md` §2.
 *
 * ## 기대값을 코드에 적지 않는다
 *
 * 상태 코드·헤더 값·상한·`detail` 문구·스키마 키 집합을 **전부 [ContractSpec] 이 계약
 * 파일에서 읽는다**(§4 P-1~P-11). 값을 여기 적으면 계약이 바뀌어도 테스트가 옛 값으로
 * 통과한다 — 그 순간 계약이 되는 것은 계약 파일이 아니라 이 파일이다.
 *
 * ## 계층 (C-M)
 *
 * `@WebMvcTest` 다. 여기서 재는 응답은 전부 **디스패처를 통과해** 만들어진다 — 컨트롤러의
 * 성공 응답과 `@RestControllerAdvice` 가 만든 오류다. 컨테이너·필터가 만드는 응답
 * (인증 실패 401)은 대리 측정으로 잡히지 않으므로 [AuthEndpointReachTest] 가 실제 소켓으로
 * 잰다(명세 §5).
 *
 * 저장소·해시·토큰은 [AuthSliceBeans] 의 가짜다. `AuthService` 와 입력 규칙은 실물이며,
 * 이 파일이 재는 것이 정확히 그 규칙과 HTTP 표현이다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class AuthContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()

    // ================================================================ signup

    @Test
    @DisplayName("S-1 가입 성공 — 계약의 성공 상태 · 사적 헤더 2종(개수까지) · 본문 키 집합이 정확히 계약의 required")
    fun `가입 성공 응답이 계약과 같다`() {
        val response = signup(uniqueEmail(), VALID_PASSWORD)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(SIGNUP_PATH, POST))
        assertPrivateHeaders(response, SIGNUP_PATH, POST)
        // S-1b: `password_hash` 류 키의 부재는 이 「정확히」가 겸한다.
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("UserResponse"))
    }

    @Test
    @DisplayName("S-2 이미 가입된 이메일 → 409 · detail 문자열 · 사적 헤더 있음 · JSON")
    fun `중복 이메일은 409 다`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD)

        val response = signup(email, VALID_PASSWORD)

        assertDeclaredStatus(response, CONFLICT, SIGNUP_PATH, POST)
        assertDetailIsString(response)
        assertGlobalHeaders(response)
        assertJsonContentType(response)
    }

    @Test
    @DisplayName("S-2b 대소문자만 다른 이메일도 중복이다 — 정규화가 중복 판정보다 먼저다")
    fun `정규화가 중복 판정보다 먼저다`() {
        // 이 케이스가 없으면 정규화를 아예 하지 않아도 S-1~S-5 가 전건 통과한다.
        assertThat(ContractSpec.requestFieldConstraint(EMAIL_FIELD).measuresNormalized).isTrue()

        val email = uniqueEmail()
        signup(email, VALID_PASSWORD)

        assertDeclaredStatus(signup(email.uppercase(), VALID_PASSWORD), CONFLICT, SIGNUP_PATH, POST)
    }

    @Test
    @DisplayName("S-3 형식이 잘못된 이메일 → 422 · detail 이 계약 문구와 같은 **문자열**")
    fun `잘못된 이메일 형식은 422 문자열이다`() {
        val response = signup("not-an-email", VALID_PASSWORD)

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)
        assertThat(detailText(response)).isEqualTo(ContractSpec.requestFieldConstraint(EMAIL_FIELD).singleDetail)
        assertGlobalHeaders(response)
    }

    @Test
    @DisplayName("S-4 정규화 후 상한을 넘는 이메일 → 422 · 문구가 S-3 과 같다 (X-F13 거절 쪽)")
    fun `상한을 넘는 이메일은 422 이고 형식 오류와 같은 문구다`() {
        val constraint = ContractSpec.requestFieldConstraint(EMAIL_FIELD)
        val response = signup(emailOfNormalizedLength(constraint.limit + 1), VALID_PASSWORD)

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)
        assertThat(detailText(response)).isEqualTo(constraint.singleDetail)
        // 길이 위반과 형식 오류가 **같은 문구**라는 것 자체가 조항이다.
        assertThat(detailText(response)).isEqualTo(detailText(signup("not-an-email", VALID_PASSWORD)))
    }

    @Test
    @DisplayName("S-5 원시는 상한 초과, 앞 공백을 털면 정확히 상한인 이메일 → 통과 (X-F9 · X-F13 통과 쪽)")
    fun `정규화 후 상한과 같은 이메일은 통과한다`() {
        val constraint = ContractSpec.requestFieldConstraint(EMAIL_FIELD)
        // 스키마 층 maxLength 로 구현하면 원시 길이로 재므로 여기서 깨진다.
        val raw = " ".repeat(LEADING_SPACES) + emailOfNormalizedLength(constraint.limit)

        assertThat(signup(raw, VALID_PASSWORD).status).isEqualTo(ContractSpec.successStatus(SIGNUP_PATH, POST))
    }

    @Test
    @DisplayName("S-6 하한 미만 비밀번호 → 422 · detail 이 **문자열 타입**(배열이 아니다) (X-F11 거절 쪽)")
    fun `짧은 비밀번호는 422 문자열이다`() {
        val constraint = ContractSpec.requestFieldConstraint(PASSWORD_FIELD)
        val response = signup(uniqueEmail(), "a".repeat(constraint.limit - 1))

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)
        assertDetailIsString(response)
        assertThat(detailText(response)).isEqualTo(constraint.singleDetail)
    }

    @Test
    @DisplayName("S-7 하한과 정확히 같은 길이의 비밀번호 → 통과 (X-F11 통과 쪽)")
    fun `하한과 같은 비밀번호는 통과한다`() {
        val constraint = ContractSpec.requestFieldConstraint(PASSWORD_FIELD)

        assertThat(signup(uniqueEmail(), "a".repeat(constraint.limit)).status)
            .isEqualTo(ContractSpec.successStatus(SIGNUP_PATH, POST))
    }

    @Test
    @DisplayName("S-8 원시 길이 = 하한, trim 후 하한 미만인 비밀번호 → 통과 (X-F12 · 원시 축)")
    fun `원시 길이로 재므로 앞 공백도 비밀번호다`() {
        val constraint = ContractSpec.requestFieldConstraint(PASSWORD_FIELD)
        // 기대값이 게이트 15 X13(리더 판정 대기)에 종속된다. 판정 전까지 현행 조항이 기준이다.
        assertThat(constraint.measuresRaw)
            .withFailMessage("계약이 password 를 원시로 재지 않는다 — X13 판정이 반영됐다면 이 케이스를 함께 고쳐야 한다")
            .isTrue()
        val password = " ".repeat(constraint.limit - 1) + "a"

        assertThat(signup(uniqueEmail(), password).status).isEqualTo(ContractSpec.successStatus(SIGNUP_PATH, POST))
    }

    @Test
    @DisplayName("S-9 필수 필드 누락 → 422 · detail 이 **배열** · 항목 키 집합이 정확히 계약의 required")
    fun `필드 누락은 422 배열이다`() {
        val response = postJson(SIGNUP_PATH, """{"email":"user@example.test"}""")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)
        assertThat(detailOf(response)).isInstanceOf(List::class.java)
        assertValidationItemKeys(response)
        assertGlobalHeaders(response)
    }

    @Test
    @DisplayName("S-10 검증 실패 응답 바이트에 제출한 비밀번호가 없다 (X-C1)")
    fun `응답에 비밀번호가 반향되지 않는다`() {
        // 한국어 표식을 쓰지 않는다 — "비밀"처럼 오류 문구와 겹치는 조각이 나오면
        // 반향이 없는데도 doesNotContain 이 깨져 무엇을 재는지 흐려진다.
        val secret = "Pw7Zq-echo-guard"
        val tooShort = signup(uniqueEmail(), secret.take(3))
        val missingField = postJson(SIGNUP_PATH, """{"password":"$secret"}""")

        assertNoEcho(tooShort, secret.take(3))
        assertNoEcho(missingField, secret)
    }

    @Test
    @DisplayName("S-11 같은 422 에서 detail 의 두 모양이 모두 나온다 (X-C3)")
    fun `422 에 문자열과 배열이 둘 다 나온다`() {
        val domainRule = signup("not-an-email", VALID_PASSWORD)
        val schemaLayer = postJson(SIGNUP_PATH, """{"email":"user@example.test"}""")

        assertDeclaredStatus(domainRule, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)
        assertDeclaredStatus(schemaLayer, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST)

        // 계약이 union 으로 선언한 갈래를 **읽어서** 기대값으로 쓴다. 「string·array」를 코드에
        // 적으면 계약이 갈래를 바꿔도 복제본이 옛 값으로 통과한다(게이트 23 C-5).
        val declared = ContractSpec.errorDetailUnionTypes()
        val observed = listOf(detailOf(domainRule), detailOf(schemaLayer)).map { ContractSpec.observedDetailType(it) }

        assertThat(declared)
            .withFailMessage("계약이 같은 갈래를 두 번 선언했다: %s", declared)
            .doesNotHaveDuplicates()
        assertThat(observed)
            .withFailMessage(
                "계약이 선언한 갈래 %s 와 실제 관측 %s 가 다르다 — 선언한 갈래가 관측되지 않거나, 선언에 없는 모양이 나갔다.",
                declared,
                observed,
            ).containsExactlyInAnyOrderElementsOf(declared)
    }

    @Test
    @DisplayName("S-12 오류 응답의 Content-Type 이 JSON 이다 (problem+json 아님)")
    fun `오류 응답은 JSON 이다`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD)

        listOf(
            signup(email, VALID_PASSWORD),
            signup("not-an-email", VALID_PASSWORD),
            postJson(SIGNUP_PATH, """{"email":"user@example.test"}"""),
        ).forEach(::assertJsonContentType)
    }

    // ================================================================ login

    @Test
    @DisplayName("L-2 잘못된 비밀번호 → 401 · WWW-Authenticate 가 계약의 const · detail 문자열 · 사적 헤더 있음")
    fun `자격증명 실패는 401 이다`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD)

        val response = login(email, "${VALID_PASSWORD}x")

        assertDeclaredStatus(response, UNAUTHORIZED, LOGIN_PATH, POST)
        assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo(ContractSpec.headerConst("WWWAuthenticateBearer"))
        assertDetailIsString(response)
        assertGlobalHeaders(response)
    }

    @Test
    @DisplayName("L-3 없는 이메일과 틀린 비밀번호의 응답이 완전히 같다 (자격증명 갈래의 균일성)")
    fun `자격증명 실패의 두 갈래가 구분되지 않는다`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD)

        val unknownEmail = login(uniqueEmail(), VALID_PASSWORD)
        val wrongPassword = login(email, "${VALID_PASSWORD}x")

        assertThat(unknownEmail.status).isEqualTo(wrongPassword.status)
        assertThat(unknownEmail.contentAsByteArray).isEqualTo(wrongPassword.contentAsByteArray)
        assertThat(unknownEmail.getHeader(WWW_AUTHENTICATE)).isEqualTo(wrongPassword.getHeader(WWW_AUTHENTICATE))
    }

    @Test
    @DisplayName("L-4 필수 필드 누락 → 422 · detail 배열 · 항목 키 집합이 정확히 계약의 required")
    fun `로그인 필드 누락은 422 배열이다`() {
        val response = postJson(LOGIN_PATH, """{"email":"user@example.test"}""")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, LOGIN_PATH, POST)
        assertThat(detailOf(response)).isInstanceOf(List::class.java)
        assertValidationItemKeys(response)
        assertGlobalHeaders(response)
    }

    @Test
    @DisplayName("L-5 로그인 실패 응답 바이트에 제출한 비밀번호가 없다")
    fun `로그인 응답에 비밀번호가 반향되지 않는다`() {
        val secret = "Lg9Wm-echo-guard"

        val failed = login(uniqueEmail(), secret)
        val missingField = postJson(LOGIN_PATH, """{"password":"$secret"}""")

        assertNoEcho(failed, secret)
        assertNoEcho(missingField, secret)
    }

    // ================================================================ 도구

    private fun signup(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        postJson(
            SIGNUP_PATH,
            json.writeValueAsString(
                mapOf(
                    "email" to email,
                    "password" to password,
                ),
            ),
        )

    private fun login(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        postJson(
            LOGIN_PATH,
            json.writeValueAsString(
                mapOf(
                    "email" to email,
                    "password" to password,
                ),
            ),
        )

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

    /**
     * 본문을 **일반 Map 으로** 읽는다.
     *
     * `JsonNode` API 대신 Map 을 쓰는 이유: Jackson 3 에서 접근자 이름이 여럿 바뀌어
     * (`asText` → `asString` 등) 테스트가 라이브러리 판올림에 흔들린다. 여기서 재는 것은
     * **나간 바이트의 구조**이므로 언어 기본 자료구조로 충분하다.
     *
     * 인코딩을 명시한다 — `MockHttpServletResponse` 의 기본 문자셋은 ISO-8859-1 이라
     * 한국어 `detail` 이 깨진 채 비교돼 "문구가 다르다"가 인코딩 문제로 나타난다.
     */
    private fun body(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        body(response).keys.map { it.toString() }.toSet()

    private fun detailOf(response: MockHttpServletResponse): Any? = body(response)["detail"]

    private fun detailText(response: MockHttpServletResponse): String =
        detailOf(response) as? String ?: error("detail 이 문자열이 아니다: ${detailOf(response)}")

    private fun detailItems(response: MockHttpServletResponse): List<*> =
        detailOf(response) as? List<*> ?: error("detail 이 배열이 아니다: ${detailOf(response)}")

    /**
     * 상태 코드를 **응답과 계약 양쪽에** 건다 (C-1 · 명세 §0 · §4 P-1).
     *
     * 응답만 단언하면 계약에서 그 상태 선언이 지워져도 아무 테스트가 깨지지 않는다 —
     * 실측으로 확인된 자리다(`'422'`→`'400'`, `/auth/me` `'401'`→`'403'` 변이에서 빨강 0).
     * 잡히는 것은 구현 회귀뿐이고 **계약 조항의 소실**은 조용히 지나갔다.
     */
    private fun assertDeclaredStatus(
        response: MockHttpServletResponse,
        status: Int,
        path: String,
        method: String,
    ) {
        assertThat(response.status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다 — 구현이 내는 상태와 계약이 갈렸다", method, path, status)
            .contains(status.toString())
    }

    private fun assertDetailIsString(response: MockHttpServletResponse) {
        // 상태 코드가 같아 모양을 안 보면 갈린 것을 놓친다 — 계약이 detail 의 **타입**을 조항으로 둔 이유다.
        assertThat(detailOf(response))
            .withFailMessage("detail 이 문자열이 아니다: %s", response.getContentAsString(StandardCharsets.UTF_8))
            .isInstanceOf(String::class.java)
    }

    private fun assertValidationItemKeys(response: MockHttpServletResponse) {
        // `input`·`ctx` 부재를 이 「정확히」가 겸한다(X-C2). 금지 키를 열거하지 않는 이유는
        // 그 목록이 계약 산문 안에만 있어 기계가 읽지 못하기 때문이다(P-9).
        assertThat(ContractSpec.schemaAllowsAdditionalProperties("ValidationErrorItem")).isFalse()
        val required = ContractSpec.schemaRequired("ValidationErrorItem")
        detailItems(response).forEach { item ->
            val keys = (item as Map<*, *>).keys.map { it.toString() }.toSet()
            assertThat(keys).isEqualTo(required)
        }
    }

    /** X-D1 하한선 + X-D2b 중복 부착 부재. 값과 **개수**를 모두 계약에서 읽어 본다. */
    private fun assertPrivateHeaders(
        response: MockHttpServletResponse,
        path: String,
        method: String,
    ) {
        val declared = ContractSpec.responseHeaderNames(path, method, ContractSpec.successStatus(path, method))
        assertThat(declared).containsExactlyInAnyOrderElementsOf(ContractSpec.globalResponseHeaders().keys)
        val expected = ContractSpec.globalHeaderValues()
        declared.forEach { header ->
            assertThat(response.getHeaders(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.getHeaders(header))
                .containsExactly(expected.getValue(header))
        }
    }

    /** X-D2: 오류 응답에도 전역 헤더가 붙는다. 값은 컴포넌트 `const` 에서 읽는다(P-3b). */
    private fun assertGlobalHeaders(response: MockHttpServletResponse) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeaders(header)).containsExactly(value)
        }
    }

    /**
     * **응답 바이트 전체**에 값이 나타나지 않는지 본다.
     *
     * `contentAsString` 을 쓰지 않는다 — `MockHttpServletResponse` 의 기본 문자셋이
     * ISO-8859-1 이라 한국어 표식이 깨진 채 비교되고, 그러면 **반향이 있어도 통과한다.**
     * 바이트를 UTF-8 로 읽어 비교하는 것이 이 단언이 실제로 재는 조건이다.
     */
    private fun assertNoEcho(
        response: MockHttpServletResponse,
        secret: String,
    ) {
        assertThat(String(response.contentAsByteArray, StandardCharsets.UTF_8)).doesNotContain(secret)
    }

    private fun assertJsonContentType(response: MockHttpServletResponse) {
        assertThat(response.contentType)
            .withFailMessage("오류 응답의 Content-Type 이 %s 다", response.contentType)
            .startsWith(MediaType.APPLICATION_JSON_VALUE)
    }

    private fun uniqueEmail(): String = "user${counter++}@example.test"

    /**
     * 정규화 후 길이가 정확히 [length] 인 이메일을 만든다.
     *
     * 도메인부는 고정하고 로컬 파트로 길이를 맞춘다 — 도메인을 늘리면 라벨 길이 제한(63)에
     * 걸려 형식 검사에서 먼저 떨어지고, 그러면 이 케이스가 길이가 아니라 형식을 재게 된다.
     */
    private fun emailOfNormalizedLength(length: Int): String {
        val domain = "@example.test"
        require(length > domain.length) { "도메인보다 짧은 이메일은 만들 수 없다" }
        return "a".repeat(length - domain.length) + domain
    }

    private companion object {
        const val SIGNUP_PATH = "/auth/signup"
        const val LOGIN_PATH = "/auth/login"
        const val POST = "post"

        const val CONFLICT = 409
        const val UNAUTHORIZED = 401
        const val UNPROCESSABLE_CONTENT = 422

        const val WWW_AUTHENTICATE = "WWW-Authenticate"

        const val EMAIL_FIELD = "SignupRequest.email"
        const val PASSWORD_FIELD = "SignupRequest.password"

        /** 길이·형식 규칙을 통과하는 값. 상한·하한을 재는 케이스는 계약에서 읽어 따로 만든다. */
        const val VALID_PASSWORD = "correct horse battery"

        /** S-5 가 원시 길이를 상한 위로 올리는 데 쓰는 앞 공백 수. */
        const val LEADING_SPACES = 10

        var counter = 0
    }
}

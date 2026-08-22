package kr.easydoc.api

import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 하한선 열거 **전건**의 X-D1 — 전역 부착 장치를 뺀 컨텍스트에서 잰다. 분모는 계약이다. */
@WebMvcTest
@Import(AuthSliceBeans::class)
class PrivateHeaderFloorCensusTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()

    @Test
    @DisplayName("이 컨텍스트에 전역 부착 장치가 **실제로 없다** — 있으면 아래 두 케이스가 개별 부착을 재지 못한다")
    fun `전역 장치가 이 컨텍스트에 없다`() {
        val response = mockMvc.get(HEALTH_PATH).andReturn().response

        ContractSpec.globalHeaderValues().keys.forEach { header ->
            assertThat(response.getHeader(header))
                .withFailMessage(
                    "전역 부착 장치가 이 컨텍스트에 들어와 있다 — 아래 케이스가 개별 부착을 재고 있지 않다(%s)",
                    header,
                ).isNull()
        }
    }

    @Test
    @DisplayName("하한선 열거의 **구현된 자리 전부**가 전역 장치 없이도 사적 헤더 2종을 싣는다 (X-D1)")
    fun `구현된 하한선 자리가 전부 개별 부착을 진다`() {
        val expected = ContractSpec.globalHeaderValues()
        assertThat(expected)
            .withFailMessage("계약에서 읽은 헤더 목록이 비었다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        val targets = implementedTargets()
        assertThat(targets)
            .withFailMessage("하한선 목록에서 구현된 자리를 하나도 찾지 못했다 — 분모가 0 이면 초록이 아무 뜻이 없다")
            .isNotEmpty()

        targets.forEach { target ->
            val response = driveSuccess(target)

            assertThat(response.status)
                .withFailMessage(
                    "%s 가 계약의 성공 상태로 응답하지 않았다 — 하한선을 성공 경로에서 재지 못한다. 실제: %d %s",
                    target,
                    response.status,
                    response.getContentAsString(StandardCharsets.UTF_8),
                ).isEqualTo(ContractSpec.successStatus(target.path, target.method))

            expected.forEach { (header, value) ->
                assertThat(response.getHeaders(header))
                    .withFailMessage(
                        "전역 장치가 없을 때 %s 에서 %s 가 나가지 않았다(또는 개수가 다르다) — 컨트롤러의 " +
                            "개별 부착이 사라졌다는 뜻이고, 그것이 계약 하한선" +
                            "(x-private-response-headers.applies_to)의 위반이다. 실제: %s",
                        target,
                        header,
                        response.getHeaders(header),
                    ).containsExactly(value)
            }
        }
    }

    @Test
    @DisplayName("유보한 자리는 **실제로 핸들러가 없다** — 구현되면 이 케이스가 먼저 빨개져 유보를 끊는다")
    fun `유보한 자리는 아직 핸들러가 없다`() {
        val declared = floorTargets().map { it.toString() }.toSet()
        assertThat(declared)
            .withFailMessage("유보 목록이 계약에 없는 자리를 담고 있다 — 유보가 계약과 어긋났다: %s", NOT_YET_IMPLEMENTED)
            .containsAll(NOT_YET_IMPLEMENTED)

        NOT_YET_IMPLEMENTED.forEach { token ->
            val target = FloorTarget.parse(token)
            val response = probeUnimplemented(target)

            assertThat(response.status)
                .withFailMessage(
                    "%s 가 「핸들러 없음」이 아니다(실제 %d) — 구현됐다는 뜻이므로 유보를 지우고 " +
                        "요청 조립을 더해 위 케이스의 분모에 넣어라",
                    target,
                    response.status,
                ).isIn(NO_HANDLER_STATUSES)
        }
    }

    private fun floorTargets(): List<FloorTarget> = ContractSpec.privateResponseHeaderTargets().map(FloorTarget::parse)

    private fun implementedTargets(): List<FloorTarget> =
        floorTargets().filterNot { it.toString() in NOT_YET_IMPLEMENTED }

    /** **조립이 없으면 끊는다** — 건너뛰면 분모가 계약이 아니라 이 `when` 이 된다. */
    private fun driveSuccess(target: FloorTarget): MockHttpServletResponse =
        when (target.toString()) {
            "POST $SIGNUP_PATH" -> {
                signup(uniqueEmail())
            }

            "POST $LOGIN_PATH" -> {
                val email = uniqueEmail()
                signup(email)
                login(email)
            }

            "GET $ME_PATH" -> {
                authorizedGet(ME_PATH, newAccount())
            }

            "GET $DOCUMENTS_PATH" -> {
                authorizedGet(DOCUMENTS_PATH, newAccount())
            }

            "GET $CONVERSION_ITEM_PATH" -> {
                val token = newAccount()
                val conversionId = acceptDocument(token)
                authorizedGet(itemPath(CONVERSION_ITEM_PATH, GET, conversionId), token)
            }

            "GET $WORKSPACES_PATH" -> {
                authorizedGet(WORKSPACES_PATH, newAccount())
            }

            "POST $WORKSPACES_PATH" -> {
                createWorkspace(newAccount(), uniqueName())
            }

            "PATCH $WORKSPACE_ITEM_PATH" -> {
                val token = newAccount()
                val workspaceId = defaultWorkspaceId(token)
                mockMvc
                    .patch(itemPath(WORKSPACE_ITEM_PATH, PATCH, workspaceId)) {
                        header(HttpHeaders.AUTHORIZATION, bearer(token))
                        contentType = MediaType.APPLICATION_JSON
                        content = nameBody(uniqueName())
                    }.andReturn()
                    .response
            }

            else -> {
                error(
                    "계약 하한선에 새 자리가 생겼는데 요청 조립이 없다: $target — " +
                        "조립을 더해 이 자리를 분모에 넣어라(유보하려면 NOT_YET_IMPLEMENTED 와 그 사유가 필요하다)",
                )
            }
        }

    private fun probeUnimplemented(target: FloorTarget): MockHttpServletResponse {
        val token = newAccount()
        val path = target.path.replace(PATH_VARIABLE, UUID.randomUUID().toString())
        return when (target.method) {
            PUT -> {
                mockMvc
                    .put(path) {
                        header(HttpHeaders.AUTHORIZATION, bearer(token))
                        contentType = MediaType.APPLICATION_JSON
                        content = EMPTY_JSON_OBJECT
                    }.andReturn()
                    .response
            }

            GET -> {
                authorizedGet(path, token)
            }

            else -> {
                error("유보 자리의 메서드에 프로브가 없다: $target")
            }
        }
    }

    private fun newAccount(): String {
        val email = uniqueEmail()
        check(signup(email).status == ContractSpec.successStatus(SIGNUP_PATH, POST)) { "가입이 실패했다" }
        val response = login(email)
        return bodyOf(response)[ACCESS_TOKEN_PROPERTY]?.toString() ?: error("로그인 응답에 토큰이 없다")
    }

    private fun signup(email: String): MockHttpServletResponse = postJson(SIGNUP_PATH, null, credentials(email))

    private fun login(email: String): MockHttpServletResponse = postJson(LOGIN_PATH, null, credentials(email))

    private fun createWorkspace(
        token: String,
        name: String,
    ): MockHttpServletResponse = postJson(WORKSPACES_PATH, token, nameBody(name))

    private fun acceptDocument(token: String): String {
        val response = postJson(DOCUMENTS_PATH, token, json.writeValueAsString(mapOf(TEXT_PROPERTY to SAMPLE_TEXT)))
        check(response.status == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.status} ${response.getContentAsString(StandardCharsets.UTF_8)}"
        }
        return bodyOf(response)[CONVERSION_ID_PROPERTY]?.toString() ?: error("접수 응답에 변환 식별자가 없다")
    }

    private fun defaultWorkspaceId(token: String): String {
        val response = authorizedGet(WORKSPACES_PATH, token)
        val items = bodyOf(response)[ITEMS_PROPERTY] as List<*>
        return (items.firstOrNull() as? Map<*, *>)?.get(ID_PROPERTY)?.toString()
            ?: error("가입이 기본 작업 공간을 만들지 않았다")
    }

    private fun postJson(
        path: String,
        token: String?,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                token?.let { header(HttpHeaders.AUTHORIZATION, bearer(it)) }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun credentials(email: String): String =
        json.writeValueAsString(mapOf(EMAIL_PROPERTY to email, PASSWORD_PROPERTY to VALID_PASSWORD))

    private fun bearer(token: String): String = "Bearer $token"

    private fun authorizedGet(
        path: String,
        token: String,
    ): MockHttpServletResponse =
        mockMvc.get(path) { header(HttpHeaders.AUTHORIZATION, bearer(token)) }.andReturn().response

    private fun nameBody(name: String): String = json.writeValueAsString(mapOf(NAME_PROPERTY to name))

    /** P-21 — 변수 이름을 계약에서 읽는다. */
    private fun itemPath(
        template: String,
        method: String,
        value: String,
    ): String = template.replace("{${ContractSpec.pathVariable(template, method).name}}", value)

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun uniqueEmail(): String = "floor-census-${counter++}@example.test"

    private fun uniqueName(): String = "작업 공간 ${counter++}"

    private data class FloorTarget(
        val method: String,
        val path: String,
    ) {
        override fun toString(): String = "${method.uppercase()} $path"

        companion object {
            fun parse(token: String): FloorTarget {
                val parts = token.trim().split(WHITESPACE)
                require(parts.size == 2) { "하한선 목록의 항목 모양이 「메서드 경로」가 아니다: $token" }
                return FloorTarget(parts[0].lowercase(), parts[1])
            }

            private val WHITESPACE = Regex("\\s+")
        }
    }

    private companion object {
        const val HEALTH_PATH = "/health"
        const val SIGNUP_PATH = "/auth/signup"
        const val LOGIN_PATH = "/auth/login"
        const val ME_PATH = "/auth/me"
        const val DOCUMENTS_PATH = "/documents"
        const val WORKSPACES_PATH = "/workspaces"
        const val WORKSPACE_ITEM_PATH = "/workspaces/{workspace_id}"
        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"

        const val GET = "get"
        const val POST = "post"
        const val PATCH = "patch"
        const val PUT = "put"

        /** 아직 미구현인 자리. **면제가 아니라 유보다** — 구현하면 빨개진다. */
        val NOT_YET_IMPLEMENTED: Set<String> =
            setOf(
                "PUT /conversions/{conversion_id}",
                "GET /conversions/{conversion_id}/export",
            )

        val PATH_VARIABLE = Regex("\\{[^}]+}")

        val NO_HANDLER_STATUSES: Set<Int> = setOf(404, 405)

        const val EMAIL_PROPERTY = "email"
        const val PASSWORD_PROPERTY = "password"
        const val ACCESS_TOKEN_PROPERTY = "access_token"
        const val NAME_PROPERTY = "name"
        const val TEXT_PROPERTY = "text"
        const val CONVERSION_ID_PROPERTY = "conversion_id"
        const val ITEMS_PROPERTY = "items"
        const val ID_PROPERTY = "id"

        const val VALID_PASSWORD = "correct horse battery"
        const val SAMPLE_TEXT = "하한선 인구조사용 안내문 본문"
        const val EMPTY_JSON_OBJECT = "{}"

        var counter = 0
    }
}

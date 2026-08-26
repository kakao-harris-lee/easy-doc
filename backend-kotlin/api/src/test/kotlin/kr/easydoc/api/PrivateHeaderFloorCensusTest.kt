package kr.easydoc.api

import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryConversionRepository
import kr.easydoc.api.support.ServedOperations
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 하한선 열거 **전건**의 X-D1 — 전역 부착 장치를 뺀 컨텍스트에서 잰다. 분모는 계약이다. */
@WebMvcTest
@Import(AuthSliceBeans::class)
class PrivateHeaderFloorCensusTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var conversions: InMemoryConversionRepository

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("하한선 목록이 계약의 applies_to 와 **정체성으로** 같다 — 같은 개수의 경로 치환도 잡는다")
    fun `하한선 목록이 계약과 정체성으로 같다`() {
        assertThat(ContractSpec.privateResponseHeaderTargets().toSet())
            .withFailMessage(
                "계약의 x-private-response-headers.applies_to 가 이 클래스가 선언한 하한선 정체성과 다르다. " +
                    "개수만 고정하면 같은 개수의 경로 치환이 통과하므로 (메서드, 경로) 짝 자신을 고정한다. " +
                    "계약에만 있는 것: %s / 선언에만 있는 것: %s",
                ContractSpec.privateResponseHeaderTargets().toSet() - DECLARED_FLOOR_TARGETS,
                DECLARED_FLOOR_TARGETS - ContractSpec.privateResponseHeaderTargets().toSet(),
            ).isEqualTo(DECLARED_FLOOR_TARGETS)
    }

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
            .withFailMessage(
                "하한선 목록에서 구현된 자리를 %d 개만 찾았다 — 하한 %d 아래다. 분모가 줄면 초록이 덮는 범위가 " +
                    "조용히 좁아진다. 유보를 늘려 분모를 깎은 것이 아닌지 먼저 보라. 실제: %s",
                targets.size,
                MIN_FLOOR_CENSUS_TARGETS,
                targets,
            ).hasSizeGreaterThanOrEqualTo(MIN_FLOOR_CENSUS_TARGETS)

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
    @DisplayName("유보한 자리는 **엔진에 매핑이 없고** 구현된 자리는 있다 — 구현되는 순간 유보가 끊긴다")
    fun `유보한 자리는 아직 매핑이 없다`() {
        val declared = floorTargets().map { it.toString() }.toSet()
        assertThat(NOT_YET_IMPLEMENTED)
            .withFailMessage(
                "유보가 상한 %d 를 넘었다 — 유보를 늘리는 것은 위 케이스의 분모를 깎는 것과 같다: %s",
                MAX_DEFERRED_FLOOR_TARGETS,
                NOT_YET_IMPLEMENTED,
            ).hasSizeLessThanOrEqualTo(MAX_DEFERRED_FLOOR_TARGETS)
        assertThat(declared)
            .withFailMessage("유보 목록이 계약에 없는 자리를 담고 있다 — 유보가 계약과 어긋났다: %s", NOT_YET_IMPLEMENTED)
            .containsAll(NOT_YET_IMPLEMENTED)

        NOT_YET_IMPLEMENTED.forEach { token ->
            val target = FloorTarget.parse(token)

            assertThat(servedMethods(target.path))
                .withFailMessage(
                    "%s 가 이미 매핑돼 있다 — 구현됐다는 뜻이므로 유보를 지우고 요청 조립을 더해 위 케이스의 " +
                        "분모에 넣어라. HTTP 상태로 재지 않는 이유는 이 자리들이 소유권 은닉 404 를 계약이 " +
                        "요구하는 자원이라, 구현된 뒤에도 404 라서 유보가 영영 열리지 않기 때문이다. 실제: %s",
                    target,
                    servedMethods(target.path),
                ).doesNotContain(target.method)
        }

        implementedTargets().forEach { target ->
            assertThat(servedMethods(target.path))
                .withFailMessage(
                    "구현된 %s 가 엔진 매핑에 없다 — 질의가 언제나 빈 집합을 돌려주면 위의 유보 단언이 " +
                        "공허하게 통과한다. 이 팔이 그 변이를 끊는다. 실제: %s",
                    target,
                    servedMethods(target.path),
                ).contains(target.method)
        }
    }

    private fun servedMethods(path: String): Set<String> = ServedOperations.methodsOn(handlerMapping, environment, path)

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

            "GET $CONVERSION_EXPORT_PATH" -> {
                exportCompleted(newAccount())
            }

            "PUT $CONVERSION_ITEM_PATH" -> {
                putOnCompleted(CONVERSION_ITEM_PATH, reviewBody(SAMPLE_REVIEW))
            }

            "PUT $CONVERSION_FEEDBACK_PATH" -> {
                putOnCompleted(CONVERSION_FEEDBACK_PATH, feedbackBody())
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

    /**
     * 완료된 내 변환에 `PUT` 을 보낸다. 두 자리(검수 저장·피드백)가 **같은 전제**를 쓴다 —
     * `done` 이 아니면 409 라 결과를 먼저 심어야 한다.
     */
    private fun putOnCompleted(
        template: String,
        body: String,
    ): MockHttpServletResponse {
        val token = newAccount()
        val conversionId = acceptDocument(token)
        markDone(conversionId)
        return mockMvc
            .put(itemPath(template, PUT, conversionId)) {
                header(HttpHeaders.AUTHORIZATION, bearer(token))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response
    }

    private fun exportCompleted(token: String): MockHttpServletResponse {
        val conversionId = acceptDocument(token)
        markDone(conversionId)
        val format = ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA).first()
        return authorizedGet(
            "${itemPath(CONVERSION_EXPORT_PATH, GET, conversionId)}?format=$format",
            token,
        )
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

    /** 완료 상태로 만든다 — 실물에서는 워커의 UPDATE. */
    private fun markDone(conversionId: String) {
        val id = UUID.fromString(conversionId)
        conversions.complete(
            conversionId = id,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = cipher.encrypt(PlainBody(SAMPLE_DRAFT), id, EncryptedField.CONVERSION_EASY_TEXT),
                    maskedItems = null,
                    editedText = null,
                ),
            missingPlaceholders = emptyList(),
            model = SAMPLE_MODEL,
            providerName = SAMPLE_PROVIDER,
            inputTokens = SAMPLE_TOKENS,
            outputTokens = SAMPLE_TOKENS,
        )
    }

    private fun reviewBody(text: String): String = json.writeValueAsString(mapOf(EDITED_TEXT_PROPERTY to text))

    /** 배포 의향 값은 **계약에서 읽는다.** 척도 둘은 이 케이스가 재지 않는 배경 값이다. */
    private fun feedbackBody(): String =
        json.writeValueAsString(
            mapOf(
                PUBLISH_INTENT_PROPERTY to ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA).first(),
                QUALITY_SCORE_PROPERTY to SAMPLE_QUALITY_SCORE,
                MINUTES_SPENT_PROPERTY to SAMPLE_MINUTES_SPENT,
            ),
        )

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
        const val CONVERSION_EXPORT_PATH = "/conversions/{conversion_id}/export"
        const val CONVERSION_FEEDBACK_PATH = "/conversions/{conversion_id}/feedback"

        const val GET = "get"
        const val POST = "post"
        const val PATCH = "patch"
        const val PUT = "put"

        val DECLARED_FLOOR_TARGETS: Set<String> =
            setOf(
                "POST /auth/signup",
                "POST /auth/login",
                "GET /auth/me",
                "GET /documents",
                "GET /conversions/{conversion_id}",
                "PUT /conversions/{conversion_id}",
                "GET /conversions/{conversion_id}/export",
                "GET /workspaces",
                "POST /workspaces",
                "PATCH /workspaces/{workspace_id}",
                // 1.5.0 신설 — 자유 의견이 응답에 그대로 되돌아 나간다.
                "PUT /conversions/{conversion_id}/feedback",
            )

        /**
         * 유보 상한과 인구조사 하한. **실측은 유보 0 · 조사 10** 이라 유보 상한은 여유 2 다.
         * 인상은 Phase 경계에서 리더가.
         */
        const val MAX_DEFERRED_FLOOR_TARGETS = 2

        const val MIN_FLOOR_CENSUS_TARGETS = 8

        /** 아직 미구현인 자리. **면제가 아니라 유보다** — 구현하면 빨개진다. */
        val NOT_YET_IMPLEMENTED: Set<String> = emptySet()

        const val EMAIL_PROPERTY = "email"
        const val PASSWORD_PROPERTY = "password"
        const val ACCESS_TOKEN_PROPERTY = "access_token"
        const val NAME_PROPERTY = "name"
        const val TEXT_PROPERTY = "text"
        const val EDITED_TEXT_PROPERTY = "edited_text"
        const val PUBLISH_INTENT_PROPERTY = "publish_intent"
        const val QUALITY_SCORE_PROPERTY = "quality_score"
        const val MINUTES_SPENT_PROPERTY = "minutes_spent"
        const val PUBLISH_INTENT_SCHEMA = "PublishIntent"
        const val CONVERSION_ID_PROPERTY = "conversion_id"
        const val ITEMS_PROPERTY = "items"
        const val ID_PROPERTY = "id"
        const val EXPORT_FORMAT_SCHEMA = "ExportFormat"

        const val VALID_PASSWORD = "correct horse battery"
        const val SAMPLE_TEXT = "하한선 인구조사용 안내문 본문"

        /** PUT 성공 팔의 배경 값 — 이 케이스는 헤더만 잰다. */
        const val SAMPLE_DRAFT = "인구조사용 초안입니다."
        const val SAMPLE_REVIEW = "인구조사용 검수본입니다."
        const val SAMPLE_MODEL = "census-model"
        const val SAMPLE_PROVIDER = "census-provider"
        const val SAMPLE_TOKENS = 1

        /** 피드백 성공 팔의 배경 값 — 범위의 정본은 `core/pilot/ConversionFeedback.kt` 다. */
        const val SAMPLE_QUALITY_SCORE = 4
        const val SAMPLE_MINUTES_SPENT = 12

        var counter = 0
    }
}

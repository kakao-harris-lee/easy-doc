package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.core.user.PasswordHash
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `POST /documents` 의 계약 — 명세 `04_contract-keeper_documents-test-spec.md` 의 C-M 계층. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class DocumentContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName("DC-1 붙여넣기 성공 — 계약의 성공 상태 · 사적 헤더 2종(개수까지) · 최상위 키가 정확히 required")
    fun `붙여넣기 성공 응답이 계약과 같다`() {
        val response = createFromText(newOwner(), textBody("안내문 본문입니다"))

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired(CREATED_SCHEMA))
    }

    @Test
    @DisplayName("DC-2 Location 이 계약의 /conversions 경로 템플릿에 본문 conversion_id 를 끼운 것과 같다 (X-D4)")
    fun `Location 이 본문 식별자를 가리킨다`() {
        val response = createFromText(newOwner(), textBody("안내문 본문입니다"))

        val parameter = ContractSpec.pathParameters(CONVERSION_ITEM_PATH).single { it.location == PATH_LOCATION }
        val conversionId = bodyOf(response).required(CONVERSION_ID_PROPERTY).toString()
        val expected = CONVERSION_ITEM_PATH.replace("{${parameter.name}}", conversionId)

        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .withFailMessage("Location 이 본문의 conversion_id 를 가리키지 않는다")
            .isEqualTo(expected)

        assertThat(parameter.format).isEqualTo(UUID_FORMAT)
        assertThat(runCatching { UUID.fromString(conversionId) }.isSuccess).isTrue()
    }

    @Test
    @DisplayName("DC-3 성공 상태에 계약이 선언한 헤더가 전부 실려 나간다 — 선언이 비면 여기가 먼저 깨진다")
    fun `성공 응답의 선언 헤더가 전부 있다`() {
        val declared =
            ContractSpec.responseHeaderNames(
                DOCUMENTS_PATH,
                POST,
                ContractSpec.successStatus(DOCUMENTS_PATH, POST),
            )

        assertThat(declared)
            .withFailMessage("계약이 성공 응답에 헤더를 하나도 선언하지 않았다 — 대조할 대상이 없다")
            .isNotEmpty()

        val response = createFromText(newOwner(), textBody("안내문 본문입니다"))
        declared.forEach { header ->
            assertThat(response.getHeader(header))
                .withFailMessage("계약이 선언한 헤더 %s 가 응답에 없다", header)
                .isNotNull()
        }
    }

    @Test
    @DisplayName("DC-8 본문이 공백뿐 → 422 · detail 문자열 · 값이 계약 422 예시 empty_body 와 같다")
    fun `빈 본문은 422 다`() {
        val response = createFromText(newOwner(), textBody("   \n\t "))

        assertDeclaredStatus(response, UNPROCESSABLE, DOCUMENTS_PATH, POST)
        assertDetailIsString(response)
        assertThat(detailText(response)).isEqualTo(example(EMPTY_BODY_EXAMPLE))
    }

    @Test
    @DisplayName("DC-9 본문 길이 상한 초과(원시) → 422 · detail 이 **배열이 아니라** 문자열 · 값이 too_long 예시와 같다")
    fun `상한을 넘는 본문은 422 문자열이다`() {
        val limit = ContractSpec.requestFieldConstraint(TEXT_FIELD).limit

        val response = createFromText(newOwner(), textBody("가".repeat(limit + 1)))

        assertDeclaredStatus(response, UNPROCESSABLE, DOCUMENTS_PATH, POST)

        assertDetailIsString(response)
        assertThat(detailText(response)).isEqualTo(ContractSpec.requestFieldConstraint(TEXT_FIELD).singleDetail)
        assertThat(detailText(response)).isEqualTo(example(TOO_LONG_EXAMPLE))
    }

    @Test
    @DisplayName("DC-10 본문 길이가 **정확히** 상한이면 통과한다 — 경계 한쪽만 걸면 off-by-one 이 남는다")
    fun `정확히 상한인 본문은 통과한다`() {
        val limit = ContractSpec.requestFieldConstraint(TEXT_FIELD).limit

        val response = createFromText(newOwner(), textBody("가".repeat(limit)))

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        assertThat(bodyOf(response).required(CHAR_COUNT_PROPERTY)).isEqualTo(limit)
    }

    @Test
    @DisplayName("DC-11 원시 길이는 초과인데 제어문자를 걷어내면 상한 이하 → **422 다**(통과가 아니다)")
    fun `본문은 정규화 전 길이로 잰다`() {
        val constraint = ContractSpec.requestFieldConstraint(TEXT_FIELD)
        val body = "가".repeat(constraint.limit) + CONTROL_CHAR.repeat(CONTROL_CHARS)

        val response = createFromText(newOwner(), textBody(body))

        if (constraint.measuresRaw) {
            assertDeclaredStatus(response, UNPROCESSABLE, DOCUMENTS_PATH, POST)
            assertThat(detailText(response)).isEqualTo(example(TOO_LONG_EXAMPLE))
        } else {
            assertThat(response.status)
                .withFailMessage("계약이 정규화 후 측정을 지정했는데 원시 길이로 거절했다")
                .isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        }
    }

    @Test
    @DisplayName("DC-22 필수 필드 누락 → 422 · detail 배열 · 항목 키가 정확히 ValidationErrorItem.required (X-C2)")
    fun `필수 필드 누락은 422 배열이다`() {
        val response = postJson(newOwner(), "{}")

        assertDeclaredStatus(response, UNPROCESSABLE, DOCUMENTS_PATH, POST)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DC-23 같은 422 에서 문자열 모양과 배열 모양이 모두 나온다 (X-C3)")
    fun `422 의 두 모양이 모두 나온다`() {
        val declared = ContractSpec.errorDetailUnionTypes()

        val stringShaped = createFromText(newOwner(), textBody("   "))
        val arrayShaped = postJson(newOwner(), "{}")
        val observed = listOf(detailOf(stringShaped), detailOf(arrayShaped)).map { ContractSpec.observedDetailType(it) }

        assertThat(declared).doesNotHaveDuplicates()
        assertThat(observed)
            .withFailMessage(
                "계약이 선언한 갈래 %s 와 실제 관측 %s 가 다르다 — 선언한 갈래가 관측되지 않거나, 선언에 없는 모양이 나갔다.",
                declared,
                observed,
            ).containsExactlyInAnyOrderElementsOf(declared)
    }

    @Test
    @DisplayName("생략한 title·workspace_id 가 명시적 null 로 와도 접수된다 — 전역 Nulls.FAIL 을 이 두 필드에서 연다")
    fun `널 제목과 널 작업 공간을 받는다`() {
        val explicitNulls =
            json.writeValueAsString(
                mapOf(
                    TEXT_PROPERTY to "본문",
                    TITLE_PROPERTY to null,
                    WORKSPACE_ID_PROPERTY to null,
                ),
            )

        assertThat(createFromText(newOwner(), explicitNulls).status)
            .isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
    }

    private fun createFromText(
        owner: UUID,
        body: String,
    ): MockHttpServletResponse = postJson(owner, body)

    private fun postJson(
        owner: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(DOCUMENTS_PATH) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun textBody(text: String): String = json.writeValueAsString(mapOf(TEXT_PROPERTY to text))

    /** 계정과 기본 작업 공간을 함께 만든다. */
    private fun newOwner(): UUID {
        val id = users.create("doc-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않는다.
        users.markEmailVerified(id)
        return id
    }

    private fun assertPrivateHeaders(response: MockHttpServletResponse) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeaders(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.getHeaders(header))
                .containsExactly(value)
        }
    }

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

    private fun assertDetailIsString(response: MockHttpServletResponse) {
        assertThat(detailOf(response))
            .withFailMessage("detail 이 문자열이 아니다 — 스키마 층(@Size)으로 구현하면 배열이 나간다")
            .isInstanceOf(String::class.java)
    }

    private fun assertValidationArray(response: MockHttpServletResponse) {
        val items = detailOf(response)
        assertThat(items).isInstanceOf(List::class.java)
        (items as List<*>).forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet())
                .isEqualTo(ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA))
        }
    }

    private fun example(name: String): String =
        ContractSpec.pathExampleDetail(DOCUMENTS_PATH, POST, UNPROCESSABLE, name)

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        bodyOf(response).keys.map { it.toString() }.toSet()

    private fun detailOf(response: MockHttpServletResponse): Any? = bodyOf(response)["detail"]

    private fun detailText(response: MockHttpServletResponse): String = detailOf(response).toString()

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val DOCUMENTS_PATH = "/documents"
        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        const val POST = "post"
        const val PATH_LOCATION = "path"
        const val UNPROCESSABLE = 422
        const val UUID_FORMAT = "uuid"

        const val CREATED_SCHEMA = "DocumentCreatedResponse"
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"
        const val TEXT_FIELD = "DocumentTextRequest.text"

        const val TEXT_PROPERTY = "text"
        const val TITLE_PROPERTY = "title"
        const val WORKSPACE_ID_PROPERTY = "workspace_id"
        const val CONVERSION_ID_PROPERTY = "conversion_id"
        const val CHAR_COUNT_PROPERTY = "char_count"

        /** 계약 `paths./documents.post.responses.422.examples` 의 갈래 이름. */
        const val EMPTY_BODY_EXAMPLE = "empty_body"
        const val TOO_LONG_EXAMPLE = "too_long"

        /** 정규화(`stripControlChars`)하면 사라지지만 원시 길이에는 세어지는 문자. */
        const val CONTROL_CHAR = "\u0001"
        const val CONTROL_CHARS = 5
    }
}

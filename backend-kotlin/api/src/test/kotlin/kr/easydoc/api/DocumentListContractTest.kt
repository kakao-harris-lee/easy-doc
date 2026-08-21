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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `GET /documents` 의 계약 — 명세 `04_contract-keeper_documents-test-spec.md` 의 C-M 계층. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class DocumentListContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName("DL-1 200 · 사적 헤더 2종(값·**개수**) · 최상위 키가 정확히 DocumentListResponse.required (X-D1 하한선)")
    fun `목록 성공 응답이 계약과 같다`() {
        val response = list(newOwner())

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired(LIST_SCHEMA))
    }

    @Test
    @DisplayName("DL-1 계약이 200 에 선언한 헤더가 전부 실려 나간다 — 선언이 비면 여기가 먼저 깨진다")
    fun `성공 응답의 선언 헤더가 전부 있다`() {
        val declared =
            ContractSpec.responseHeaderNames(
                DOCUMENTS_PATH,
                GET,
                ContractSpec.successStatus(DOCUMENTS_PATH, GET),
            )

        assertThat(declared)
            .withFailMessage("계약이 목록 성공 응답에 헤더를 하나도 선언하지 않았다 — 대조할 대상이 없다")
            .isNotEmpty()

        val response = list(newOwner())
        declared.forEach { header ->
            assertThat(response.getHeader(header))
                .withFailMessage("계약이 선언한 헤더 %s 가 응답에 없다", header)
                .isNotNull()
        }
    }

    @Test
    @DisplayName("DL-5 limit 하한 미만 → 422 · detail 이 **배열**(문자열이 아님) · 항목 키 정확히 3 (X-C2)")
    fun `하한 미만 limit 은 422 배열이다`() {
        val range = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)

        val response = list(newOwner(), limit = range.belowMin)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DL-5 limit 상한 초과 → 422 · detail **배열** · 항목 키 정확히 3")
    fun `상한 초과 limit 은 422 배열이다`() {
        val range = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)

        val above = range.aboveMax ?: error("계약이 $LIST_LIMIT_KEY 에 상한을 두지 않았다 — 이 케이스를 세울 수 없다")

        val response = list(newOwner(), limit = above)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DL-5 음수 시작점 → 422 · detail **배열** · 항목 키 정확히 3")
    fun `음수 offset 은 422 배열이다`() {
        val range = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)

        val response = list(newOwner(), offset = range.belowMin)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DL-5 검증 실패 항목이 어느 쿼리 파라미터인지 가리킨다 — loc 가 계약 query_range 예시와 같은 모양이다")
    fun `검증 항목이 파라미터를 지목한다`() {
        val range = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)

        val parameter = ContractSpec.queryParameters(DOCUMENTS_PATH, GET).single { it.name == LIST_LIMIT_PARAM }

        val items = detailOf(list(newOwner(), limit = range.belowMin)) as List<*>

        val locations = items.map { (it as Map<*, *>)[LOC_KEY] }
        assertThat(locations)
            .withFailMessage(
                "검증 항목의 loc 가 [%s, %s] 를 담지 않는다 — 어느 파라미터가 틀렸는지 클라이언트가 알 수 없다. 실제: %s",
                QUERY_LOCATION,
                parameter.name,
                locations,
            ).contains(listOf(QUERY_LOCATION, parameter.name))
    }

    @Test
    @DisplayName("DL-6 limit 이 **정확히** 하한 / **정확히** 상한 · offset 이 **정확히** 하한이면 통과한다")
    fun `경계 값은 통과한다`() {
        val limit = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val offset = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)
        val success = ContractSpec.successStatus(DOCUMENTS_PATH, GET)
        val owner = newOwner()

        assertThat(list(owner, limit = limit.min).status).isEqualTo(success)
        assertThat(list(owner, limit = requireNotNull(limit.max)).status).isEqualTo(success)
        assertThat(list(owner, offset = offset.min).status).isEqualTo(success)
    }

    @Test
    @DisplayName("DL-6 통과한 경계 값이 응답에 **그대로** 되돌아온다 — 계약이 그렇게 적었다")
    fun `요청한 페이지 값이 응답에 그대로 실린다`() {
        val limit = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val offset = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)

        val body = bodyOf(list(newOwner(), limit = limit.min, offset = offset.min))

        assertThat(body[LIMIT_PROPERTY]).isEqualTo(limit.min)
        assertThat(body[OFFSET_PROPERTY]).isEqualTo(offset.min)
    }

    @Test
    @DisplayName("DL-7 파라미터를 아예 주지 않으면 통과하고, 응답의 페이지 필드가 계약의 **기본값**과 같다")
    fun `파라미터를 생략하면 계약 기본값이 실린다`() {
        val response = list(newOwner())

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        val body = bodyOf(response)

        assertThat(body[LIMIT_PROPERTY]).isEqualTo(ContractSpec.inputLimitRange(LIST_LIMIT_KEY).default)
        assertThat(body[OFFSET_PROPERTY]).isEqualTo(ContractSpec.inputLimitRange(LIST_OFFSET_KEY).default)
    }

    private fun list(
        owner: UUID,
        limit: Int? = null,
        offset: Int? = null,
    ): MockHttpServletResponse {
        val query =
            buildList {
                if (limit != null) add("$LIST_LIMIT_PARAM=$limit")
                if (offset != null) add("$LIST_OFFSET_PARAM=$offset")
            }.joinToString("&")
        val url = if (query.isEmpty()) DOCUMENTS_PATH else "$DOCUMENTS_PATH?$query"
        return mockMvc
            .get(url) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
            }.andReturn()
            .response
    }

    /** 계정과 기본 작업 공간을 함께 만든다. */
    private fun newOwner(): UUID {
        val id = users.create("list-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
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
    ) {
        assertThat(response.status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", DOCUMENTS_PATH, status)
            .contains(status.toString())
    }

    /** `detail` 이 배열이고 항목 키 집합이 정확히 `ValidationErrorItem.required` 다. */
    private fun assertValidationArray(response: MockHttpServletResponse) {
        val items = detailOf(response)
        assertThat(items)
            .withFailMessage("detail 이 배열이 아니다 — 서비스 층에서 판정하면 문자열이 나가고, 그것은 계약 위반이다: %s", items)
            .isInstanceOf(List::class.java)
        assertThat(items).isNotInstanceOf(String::class.java)

        val declared = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items as List<*>).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet())
                .withFailMessage("검증 항목의 키 집합이 계약 %s 와 다르다 — input·ctx 가 실리면 비밀번호가 로그에 남는다", VALIDATION_ITEM_SCHEMA)
                .isEqualTo(declared)
        }
    }

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        bodyOf(response).keys.map { it.toString() }.toSet()

    private fun detailOf(response: MockHttpServletResponse): Any? = bodyOf(response)[DETAIL]

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val DOCUMENTS_PATH = "/documents"
        const val GET = "get"
        const val UNPROCESSABLE = 422

        const val LIST_SCHEMA = "DocumentListResponse"
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        /** 계약 `x-input-limits` 의 노드 이름. 값이 아니라 자리다. */
        const val LIST_LIMIT_KEY = "list_limit"
        const val LIST_OFFSET_KEY = "list_offset"

        /** 계약 `paths./documents.get.parameters` 의 이름. */
        const val LIST_LIMIT_PARAM = "limit"
        const val LIST_OFFSET_PARAM = "offset"

        const val LIMIT_PROPERTY = "limit"
        const val OFFSET_PROPERTY = "offset"
        const val DETAIL = "detail"
        const val LOC_KEY = "loc"

        /** 계약 `ValidationFailed.examples.query_range` 의 `loc` 첫 칸. */
        const val QUERY_LOCATION = "query"
    }
}

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

/**
 * `GET /documents` 의 계약 — 명세 `04_contract-keeper_documents-test-spec.md` 의 **C-M 계층**.
 *
 * 여기 있는 것: **DL-1 · DL-5 · DL-6 · DL-7**.
 * 나머지(DL-2·DL-3·DL-4·DL-8·DL-9·DL-10·DL-11)는 실 PostgreSQL 과 실제 소켓이 필요해
 * [DocumentListReachTest] 가 잰다.
 *
 * ## DL-5 가 이 배치에서 가장 무거운 케이스다
 *
 * 계약이 `limit`·`offset` 을 **진짜 스키마 제약**으로 못박았고
 * (`x-request-field-constraints.x-contrast-case`), 그래서 위반의 `detail` 이 **배열**이다.
 * 요청 본문 다섯 필드(F3)와 **정반대**이며 두 축이 서로의 대비 사례다.
 *
 * **상태 코드만 재면 이 판정이 성립하지 않는다.** 문자열 `detail` 을 내는 구현도 422 를
 * 내므로, 「스키마 층 강제가 실제로 섰는가」는 `detail` 의 **타입**을 봐야 알 수 있다.
 * 그것이 명세가 이 케이스를 지침 3·8 의 마감 판정으로 둔 이유다.
 *
 * ## 왜 `@WebMvcTest` 로 충분한가
 *
 * 여기서 재는 것은 **디스패처가 만드는 응답**뿐이다 — 200 성공 본문과, 제약 애너테이션이
 * 만든 `HandlerMethodValidationException` 을 `@RestControllerAdvice` 가 옮긴 422. 목이
 * 재현하지 못하는 자리(컨테이너가 만드는 401·헤더 인코딩)는 명세 §5-1 대로 여기 두지 않는다.
 *
 * ## 기대값을 코드에 적지 않는다
 *
 * 경계·기본값·키 집합·헤더 값을 전부 [ContractSpec] 이 계약 파일에서 읽는다. 경계 숫자를
 * 여기 적으면 계약이 값을 바꿔도 옛 경계를 재고, 그 순간 계약이 되는 것은 이 파일이다
 * (N-24 가 겨누는 축).
 */
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

    // ================================================================ DL-1 — 성공 모양

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

    // ================================================================ DL-5 — 범위 밖 (지침 3·8 마감)

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
        // 상한이 없으면 이 케이스는 성립하지 않는다 — 조용히 통과하지 않고 **끊는다**.
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
        // 파라미터 **이름**도 계약에서 읽는다. 코드에 적으면 계약이 이름을 바꿀 때 갈린다.
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

    // ================================================================ DL-6 — 경계 양쪽 통과

    @Test
    @DisplayName("DL-6 limit 이 **정확히** 하한 / **정확히** 상한 · offset 이 **정확히** 하한이면 통과한다")
    fun `경계 값은 통과한다`() {
        val limit = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val offset = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)
        val success = ContractSpec.successStatus(DOCUMENTS_PATH, GET)
        val owner = newOwner()

        // 한쪽 경계만 걸면 off-by-one 이 반대쪽에 남는다. 셋을 함께 잰다.
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

    // ================================================================ DL-7 — 기본값

    @Test
    @DisplayName("DL-7 파라미터를 아예 주지 않으면 통과하고, 응답의 페이지 필드가 계약의 **기본값**과 같다")
    fun `파라미터를 생략하면 계약 기본값이 실린다`() {
        val response = list(newOwner())

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        val body = bodyOf(response)
        // 기본값을 코드에 적으면 계약이 그것을 바꿔도 이 단언이 옛 값을 요구한다.
        assertThat(body[LIMIT_PROPERTY]).isEqualTo(ContractSpec.inputLimitRange(LIST_LIMIT_KEY).default)
        assertThat(body[OFFSET_PROPERTY]).isEqualTo(ContractSpec.inputLimitRange(LIST_OFFSET_KEY).default)
    }

    // ================================================================ 요청 조립

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

    /**
     * 계정과 **기본 작업 공간**을 함께 만든다.
     *
     * 목록 조회 자체는 작업 공간을 요구하지 않지만, 대역을 실물과 같은 상태로 두어야
     * 「작업 공간이 하나도 없는 계정」이라는 우리 불변식이 깨진 상태를 재지 않게 된다
     * (`DocumentContractTest.newOwner` 와 같은 규칙).
     */
    private fun newOwner(): UUID {
        val id = users.create("list-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    // ================================================================ 단언 도구

    private fun assertPrivateHeaders(response: MockHttpServletResponse) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            // 개수까지 본다(X-D2b) — 컨트롤러 개별 부착과 전역 필터가 겹쳐 둘이 나가면 여기서 깨진다.
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

    /**
     * `detail` 이 **배열**이고 항목 키 집합이 정확히 `ValidationErrorItem.required` 다.
     *
     * 문자열이 아님을 **명시적으로** 단언한다 — 명세 DL-5 가 그 방향을 지목했고, 그것이
     * 지침 3(스키마 층 강제)의 마감 판정이다.
     */
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

        /** 계약 `x-input-limits` 의 노드 이름. 값이 아니라 **자리**다. */
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

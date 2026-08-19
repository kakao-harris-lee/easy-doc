package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.MeasurementAxis
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * `/workspaces` 의 계약 — 명세 `03_contract-keeper_workspaces-test-spec.md` §2 의 **C-M 계층**.
 *
 * ## 기대값을 코드에 적지 않는다
 *
 * 상태 코드·헤더 값·상한·`detail` 문구·스키마 키 집합·경로 변수 이름을 **전부
 * [ContractSpec] 이 계약 파일에서 읽는다**(§4 P-16~P-21). 값을 여기 적으면 계약이 바뀌어도
 * 테스트가 옛 값으로 통과한다 — 그 순간 계약이 되는 것은 계약 파일이 아니라 이 파일이다.
 *
 * ## 계층 (§5)
 *
 * `@WebMvcTest` 다. 여기서 재는 응답은 전부 **디스패처를 통과해** 만들어진다 — 컨트롤러의
 * 성공 응답과 `@RestControllerAdvice` 가 만든 422 다. 인증 실패 401 과 X-A3(인증이 검증보다
 * 먼저)은 **필터·인터셉터 배치의 성질**이라 MockMvc 로 재면 실제 배치 오류가 통과한다 —
 * 그쪽은 [WorkspaceEndpointReachTest] 가 실제 소켓으로 잰다. 소유권 404·409 두 갈래·목록
 * 순서처럼 **자원이 실재해야** 성립하는 것도 그쪽이다.
 *
 * 저장소는 [AuthSliceBeans] 의 가짜지만 소유 조건과 유일성은 실물과 같은 축이고,
 * `WorkspaceService` 와 이름 규칙은 실물이다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class WorkspaceContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    /** 소유자 계정을 실재하게 만들 자리. 인증 경계가 사용자 존재를 확인한다(X-1). */
    @Autowired
    private lateinit var users: InMemoryUserRepository

    private val json = ObjectMapper()

    // ================================================================ 계약 안 대조 (P-16 · P-19 · P-20)

    @Test
    @DisplayName("P-16 WorkspaceListItem 의 allOf 합성 required 가 단건 required + document_count 다")
    fun `합성 스키마의 required 를 합쳐 읽는다`() {
        val composed = ContractSpec.schemaRequiredComposed(LIST_ITEM_SCHEMA)

        // 갈래 하나만 읽는 배선이면 여기서 드러난다 — 단건 required 만 나오거나 그 반대다.
        assertThat(composed).containsAll(ContractSpec.schemaRequired(SINGLE_SCHEMA))
        assertThat(composed).isNotEqualTo(ContractSpec.schemaRequired(SINGLE_SCHEMA))
    }

    @Test
    @DisplayName("P-19·P-20 같은 상한이 계약 안에 세 벌 있고 셋이 서로 같다 (name)")
    fun `계약 내부의 삼중 선언이 일치한다`() {
        val field = ContractSpec.requestFieldConstraint(NAME_FIELD)
        val schemaConstraint = ContractSpec.serviceConstraint(NAME_REQUEST_SCHEMA, NAME_PROPERTY)

        assertThat(field.limit)
            .withFailMessage("이름 상한이 x-input-limits 와 fields[].limit 에서 갈렸다")
            .isEqualTo(ContractSpec.inputLimit(NAME_LIMIT_KEY))
        assertThat(schemaConstraint["max_length"])
            .withFailMessage("이름 상한이 fields[].limit 와 스키마 x-service-constraint 에서 갈렸다")
            .isEqualTo(field.limit)

        // 어휘가 둘이라 매핑을 거쳐 본다. 매핑에 없는 값이면 파서가 실패한다.
        assertThat(MeasurementAxis.ofToken(schemaConstraint["measured_on"].toString(), NAME_FIELD))
            .withFailMessage("두 자리의 measured_on 이 서로 다른 축을 가리킨다")
            .isEqualTo(field.axis)
        // 「비어 있으면 안 된다」가 스키마 표식에도 있어야 WC-4 가 무엇을 재는지 성립한다.
        assertThat(schemaConstraint["non_empty"]).isEqualTo(true)
    }

    // ================================================================ GET /workspaces

    @Test
    @DisplayName("WL-1 목록 성공 — 계약의 성공 상태 · 사적 헤더 2종(개수까지) · 최상위 키가 정확히 required")
    fun `목록 응답이 계약과 같다`() {
        val owner = newOwner()
        createWorkspace(owner, "가")

        val response = listWorkspaces(owner)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(COLLECTION_PATH, GET))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired(LIST_SCHEMA))
    }

    @Test
    @DisplayName("WL-2 목록 항목의 키 집합이 정확히 allOf 합성 required 다 (추가 키 0)")
    fun `목록 항목의 키 집합이 계약과 같다`() {
        val owner = newOwner()
        createWorkspace(owner, "가")
        createWorkspace(owner, "나")

        val items = itemsOf(listWorkspaces(owner))

        assertThat(items).hasSizeGreaterThanOrEqualTo(2)
        // snake_case 회귀(`document_count`·`created_at`)와 키 누락·추가를 「정확히」 하나로 잡는다.
        items.forEach { item ->
            assertThat(item.keys.map { it.toString() }.toSet())
                .isEqualTo(ContractSpec.schemaRequiredComposed(LIST_ITEM_SCHEMA))
        }
    }

    // ================================================================ POST /workspaces

    @Test
    @DisplayName("WC-1 생성 성공 — 계약의 성공 상태(200 아님) · 사적 헤더 2종(개수까지) · 키가 정확히 단건 required")
    fun `생성 응답이 계약과 같다`() {
        val response = createWorkspace(newOwner(), "가")

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(COLLECTION_PATH, POST))
        assertPrivateHeaders(response)
        // `document_count` 가 없다는 것을 이 「정확히」가 겸한다.
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired(SINGLE_SCHEMA))
    }

    @Test
    @DisplayName("WC-4 공백만인 이름 → 422 · detail 이 **문자열 타입** · 값이 계약의 빈 이름 예시와 같다 (X-F10)")
    fun `공백만인 이름은 422 문자열이다`() {
        val response = createWorkspace(newOwner(), "   ")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, COLLECTION_PATH, POST)
        // 배열이면 `@Size`/`@NotBlank` 로 구현한 것이다 — 상태 코드는 같아 여기서만 갈린다.
        assertDetailIsString(response)
        assertThat(detailText(response)).isEqualTo(emptyNameDetail())
    }

    @Test
    @DisplayName("WC-5 정규화 후 상한을 넘는 이름 → 422 문자열 · 자르지 않고 거절")
    fun `상한을 넘는 이름은 거절한다`() {
        val limit = ContractSpec.requestFieldConstraint(NAME_FIELD).limit
        val response = createWorkspace(newOwner(), "가".repeat(limit + 1))

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, COLLECTION_PATH, POST)
        assertThat(detailText(response)).isEqualTo(tooLongNameDetail())
        // 두 갈래가 실제로 다른 문구여야 한다 — 한 값으로 뭉갠 배선이면 여기서 드러난다.
        assertThat(tooLongNameDetail()).isNotEqualTo(emptyNameDetail())
    }

    @Test
    @DisplayName("WC-6 정규화 후 길이가 정확히 상한인 이름 → 통과 (경계 한쪽만 걸면 off-by-one 이 남는다)")
    fun `상한과 같은 이름은 통과한다`() {
        val limit = ContractSpec.requestFieldConstraint(NAME_FIELD).limit

        val response = createWorkspace(newOwner(), "가".repeat(limit))

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(COLLECTION_PATH, POST))
    }

    @Test
    @DisplayName("WC-7 원시는 상한 초과, 제어문자를 걷어내면 상한 이하 → 통과 (X-F9 name)")
    fun `정규화 후를 재므로 제어문자가 길이에 들지 않는다`() {
        val constraint = ContractSpec.requestFieldConstraint(NAME_FIELD)
        assertThat(constraint.axis).isEqualTo(MeasurementAxis.NORMALIZED)
        // 스키마 층 maxLength 로 구현하면 원시 길이로 재므로 여기서 깨진다.
        val raw = "가".repeat(constraint.limit) + CONTROL_CHAR.repeat(CONTROL_CHARS)

        val response = createWorkspace(newOwner(), raw)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(COLLECTION_PATH, POST))
    }

    @Test
    @DisplayName("WC-8 앞뒤 공백은 저장까지 털린다 — 응답 name 이 정규화된 값이다")
    fun `정규화가 저장까지 간다`() {
        // WC-7 만으로는 판정에만 쓰고 버리는 구현이 통과한다.
        val response = createWorkspace(newOwner(), "  가나다  ")

        assertThat(bodyOf(response)[NAME_PROPERTY]).isEqualTo("가나다")
    }

    @Test
    @DisplayName("WC-9 name 필드 누락 → 422 · detail 이 **배열** · 항목 키가 정확히 ValidationErrorItem.required")
    fun `필드 누락은 422 배열이다`() {
        val response = postJson(COLLECTION_PATH, newOwner(), "{}")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, COLLECTION_PATH, POST)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("WC-10 같은 422 에서 문자열 모양과 배열 모양이 모두 나온다 (X-C3)")
    fun `422 의 두 모양이 모두 나온다`() {
        val union = ContractSpec.errorDetailUnionTypes()

        val stringShaped = createWorkspace(newOwner(), "   ")
        val arrayShaped = postJson(COLLECTION_PATH, newOwner(), "{}")

        assertThat(union).hasSize(2)
        assertThat(detailOf(stringShaped)).isInstanceOf(String::class.java)
        assertThat(detailOf(arrayShaped)).isInstanceOf(List::class.java)
    }

    @Test
    @DisplayName("WC-11 오류 응답의 Content-Type 이 JSON 이다 (X-C4 / E-3)")
    fun `오류 응답이 JSON 이다`() {
        assertJsonContentType(createWorkspace(newOwner(), "   "))
        assertJsonContentType(postJson(COLLECTION_PATH, newOwner(), "{}"))
    }

    // ================================================================ PATCH · DELETE

    @Test
    @DisplayName("WR-6 이름 규칙 네 경계가 PATCH 에서도 같다 — 같은 계약 노드에서 읽어 두 곳에 건다")
    fun `이름 규칙이 두 엔드포인트에서 같다`() {
        val owner = newOwner()
        val limit = ContractSpec.requestFieldConstraint(NAME_FIELD).limit
        val id = bodyOf(createWorkspace(owner, "처음")).required("id").toString()

        assertThat(detailText(rename(owner, id, "   "))).isEqualTo(emptyNameDetail())
        assertThat(detailText(rename(owner, id, "가".repeat(limit + 1)))).isEqualTo(tooLongNameDetail())
        assertThat(rename(owner, id, "가".repeat(limit)).status)
            .isEqualTo(ContractSpec.successStatus(ITEM_PATH, PATCH))
        assertThat(rename(owner, id, "나".repeat(limit) + CONTROL_CHAR.repeat(CONTROL_CHARS)).status)
            .isEqualTo(ContractSpec.successStatus(ITEM_PATH, PATCH))
    }

    @Test
    @DisplayName("WR-7 UUID 가 아닌 경로 변수 → 422 · detail 배열 · 항목 키 정확히 3")
    fun `경로 변수 형식 위반은 422 배열이다`() {
        // 계약이 이 자리를 `format: uuid` 로만 적는다 — 제약 애너테이션이 아니라 타입 변환이다.
        assertThat(pathParameter().format).isEqualTo(UUID_FORMAT)

        val response = rename(newOwner(), NOT_A_UUID, "가")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, ITEM_PATH, PATCH)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("WD-6 DELETE 도 UUID 가 아닌 경로 변수에 422 배열을 낸다")
    fun `삭제의 경로 변수 형식 위반도 422 배열이다`() {
        val response =
            mockMvc
                .delete(itemPath(NOT_A_UUID)) { header(HttpHeaders.AUTHORIZATION, bearer(newOwner())) }
                .andReturn()
                .response

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, ITEM_PATH, DELETE)
        assertValidationArray(response)
    }

    // ================================================================ 요청 조립

    private fun listWorkspaces(owner: UUID): MockHttpServletResponse =
        mockMvc.get(COLLECTION_PATH) { header(HttpHeaders.AUTHORIZATION, bearer(owner)) }.andReturn().response

    private fun createWorkspace(
        owner: UUID,
        name: String,
    ): MockHttpServletResponse = postJson(COLLECTION_PATH, owner, nameBody(name))

    private fun rename(
        owner: UUID,
        workspaceId: String,
        name: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch(itemPath(workspaceId)) {
                header(HttpHeaders.AUTHORIZATION, bearer(owner))
                contentType = MediaType.APPLICATION_JSON
                content = nameBody(name)
            }.andReturn()
            .response

    private fun postJson(
        path: String,
        owner: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(owner))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun nameBody(name: String): String = json.writeValueAsString(mapOf(NAME_PROPERTY to name))

    /** [kr.easydoc.api.support.StubAccessTokens] 가 읽는 형식. 서명·만료는 이 계층에서 재지 않는다. */
    private fun bearer(owner: UUID): String = "Bearer stub-token:$owner"

    /**
     * 계정을 **실제로 만들고** 그 식별자를 쓴다.
     *
     * 종전에는 `UUID.randomUUID()` 를 바로 소유자로 썼다 — 토큰이 가리키는 계정이 없어도
     * 인증 경계를 지났기 때문이다. X-1 이후 그 상태는 401 이므로(계약
     * `x-auth.failure_uniformity` 의 「계정 삭제」), 여기서도 계정이 실재해야 한다.
     * **이 변경이 필요했다는 사실 자체가 X-1 의 도달 증거다** — 슬라이스 테스트가
     * 종전 구현에서 유령 계정으로 16번 통과하고 있었다.
     */
    private fun newOwner(): UUID = users.create("slice-${UUID.randomUUID()}@example.test", STUB_HASH).id

    /**
     * **P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다.**
     *
     * 이름을 코드에 적으면 계약이 그것을 바꿔도 테스트가 옛 이름으로 URL 을 만들고,
     * 그러면 엉뚱한 매핑을 재거나 404 를 「소유권 은닉」으로 오독한다.
     */
    private fun itemPath(workspaceId: String): String = ITEM_PATH.replace("{${pathParameter().name}}", workspaceId)

    private fun pathParameter(): kr.easydoc.api.support.ContractPathParameter =
        ContractSpec.pathParameters(ITEM_PATH).single { it.location == "path" }

    // ================================================================ 단언 도구

    /** X-D1 하한선 + X-D2b 중복 부착 부재. 값과 개수를 모두 계약에서 읽어 본다. */
    private fun assertPrivateHeaders(response: MockHttpServletResponse) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeaders(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.getHeaders(header))
                .containsExactly(value)
        }
    }

    /** 상태 코드를 **응답과 계약 양쪽에** 건다 — 계약에서 선언이 지워지면 함께 빨개진다. */
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
            .withFailMessage("detail 이 문자열이 아니다 — 스키마 층(@Size/@NotBlank)으로 구현하면 배열이 나간다")
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

    private fun assertJsonContentType(response: MockHttpServletResponse) {
        assertThat(MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_JSON))
            .withFailMessage("오류 응답의 Content-Type 이 JSON 이 아니다: %s", response.contentType)
            .isTrue()
    }

    private fun emptyNameDetail(): String =
        ContractSpec.pathExampleDetail(COLLECTION_PATH, POST, UNPROCESSABLE_CONTENT, EMPTY_EXAMPLE)

    private fun tooLongNameDetail(): String =
        ContractSpec.pathExampleDetail(COLLECTION_PATH, POST, UNPROCESSABLE_CONTENT, TOO_LONG_EXAMPLE)

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        bodyOf(response).keys.map { it.toString() }.toSet()

    private fun itemsOf(response: MockHttpServletResponse): List<Map<*, *>> =
        (bodyOf(response).required(ITEMS_PROPERTY) as List<*>).map { it as Map<*, *> }

    private fun detailOf(response: MockHttpServletResponse): Any? = bodyOf(response)["detail"]

    private fun detailText(response: MockHttpServletResponse): String = detailOf(response).toString()

    private companion object {
        /** 소유자 계정을 심을 때만 쓰는 더미 해시. 이 파일은 비밀번호 검증을 재지 않는다. */
        val STUB_HASH = PasswordHash("stub-hash")

        const val COLLECTION_PATH = "/workspaces"
        const val ITEM_PATH = "/workspaces/{workspace_id}"
        const val GET = "get"
        const val POST = "post"
        const val PATCH = "patch"
        const val DELETE = "delete"

        const val UNPROCESSABLE_CONTENT = 422

        const val SINGLE_SCHEMA = "WorkspaceResponse"
        const val LIST_ITEM_SCHEMA = "WorkspaceListItem"
        const val LIST_SCHEMA = "WorkspaceListResponse"
        const val NAME_REQUEST_SCHEMA = "WorkspaceNameRequest"
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        const val NAME_FIELD = "WorkspaceNameRequest.name"
        const val NAME_PROPERTY = "name"
        const val ITEMS_PROPERTY = "items"
        const val NAME_LIMIT_KEY = "max_workspace_name_length"

        /** 계약 `paths./workspaces.post.responses.422.examples` 의 두 갈래 이름. */
        const val EMPTY_EXAMPLE = "empty"
        const val TOO_LONG_EXAMPLE = "too_long"

        const val UUID_FORMAT = "uuid"
        const val NOT_A_UUID = "not-a-uuid"

        /**
         * 정규화가 걷어내는 문자. `stripControlChars` 의 범위 안이면 무엇이든 된다.
         *
         * **이스케이프로 적는다** — 소스에 제어문자를 그대로 넣으면 편집기·diff·터미널에서
         * 보이지 않아, 다음 사람이 이 문자열을 빈 값으로 읽는다.
         */
        const val CONTROL_CHAR = "\u0001"

        /** 붙이는 개수. 상한을 넘기기만 하면 되므로 값 자체에 뜻은 없다. */
        const val CONTROL_CHARS = 5
    }
}

/** 스타 프로젝션 맵에서 필수 키를 꺼낸다. 없으면 **끊는다** — `null` 을 흘려보내면
 * 「키가 없다」가 「값이 null 이다」로 둔갑해 단언이 무엇을 재는지 흐려진다. */
private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

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

/**
 * `POST /documents` 의 계약 — 명세 `04_contract-keeper_documents-test-spec.md` 의 **C-M 계층**.
 *
 * 여기 있는 것: **DC-1 · DC-2 · DC-3 · DC-8 · DC-9 · DC-10 · DC-11 · DC-22 · DC-23**.
 *
 * ## 기대값을 코드에 적지 않는다
 *
 * 상태 코드·헤더 값·상한·`detail` 문구·스키마 키 집합을 **전부 [ContractSpec] 이 계약
 * 파일에서 읽는다**. 값을 여기 적으면 계약이 바뀌어도 테스트가 옛 값으로 통과한다 —
 * 그 순간 계약이 되는 것은 계약 파일이 아니라 이 파일이다.
 *
 * ## 계층 (명세 §5)
 *
 * `@WebMvcTest` 다. 여기서 재는 응답은 전부 **디스패처를 통과해** 만들어진다 — 202 성공
 * 응답과 `@RestControllerAdvice` 가 만든 422 다.
 *
 * **업로드(multipart)는 여기 없다.** 명세 §5-1 이 그것을 금지한다 — MockMvc 는 컨테이너가
 * 만드는 응답과 파트 파싱을 **재현하지 못하면서 통과한다**. DC-4~DC-7·DC-12~DC-15 와
 * 401 갈래(DC-20·DC-21), 소유권 404(DC-16·DC-17)는 [DocumentEndpointReachTest] 가 실제
 * 소켓과 실 PostgreSQL 로 잰다.
 *
 * 저장소는 [AuthSliceBeans] 의 대역이지만 **유스케이스는 실물**이라, 계약이 못박은 검사
 * 순서(크기 → 추출 → 길이 → 소유권 → 저장)를 이 슬라이스가 실제로 밟는다.
 */
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

    // ================================================================ 성공 (DC-1 · DC-2 · DC-3)

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

        // 형식 문자열을 코드에 적지 않는다 — **경로 템플릿과 변수 이름을 계약에서 읽어**
        // 조립한다. 계약이 경로를 옮기면 이 단언이 새 경로를 요구한다(N-28 이 재는 축).
        val parameter = ContractSpec.pathParameters(CONVERSION_ITEM_PATH).single { it.location == PATH_LOCATION }
        val conversionId = bodyOf(response).required(CONVERSION_ID_PROPERTY).toString()
        val expected = CONVERSION_ITEM_PATH.replace("{${parameter.name}}", conversionId)

        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .withFailMessage("Location 이 본문의 conversion_id 를 가리키지 않는다")
            .isEqualTo(expected)
        // 식별자가 실제로 UUID 형식인지도 본다 — 계약이 그 형식을 선언했다.
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

        // **선언이 비어 있으면 실패다.** `Location` 이 계약에서 지워지면 DC-2 는 여전히
        // `paths` 에서 템플릿을 읽어 통과할 수 있으므로, 그 손상을 먼저 잡는 자리가 여기다.
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

    // ================================================================ 본문 길이·정의역 (DC-8 ~ DC-11)

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
        // 스키마 층(`@Size`)으로 구현하면 여기서 **배열**이 나간다 — F3 이 금지한 형태다.
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
        // **기대값을 계약에서 읽는다.** 계약이 `text` 를 원시 값으로, `edited_text` 를 정규화
        // 후로 재라고 정했고(`x-request-field-constraints`, 미결 항목 x-open-asymmetry 의
        // 현행 (가)), 그 축이 뒤집히면 이 케이스의 **기대 자체가 뒤집혀야** 한다.
        //
        // 축을 코드에 적어 두면 계약이 바뀌어도 이 케이스가 옛 축을 요구한다 — 실측으로
        // 확인했다: 처음 판은 「422」를 못박아 두어 N-25(축 변경)에서 **깨지지 않았다**.
        val constraint = ContractSpec.requestFieldConstraint(TEXT_FIELD)
        val body = "가".repeat(constraint.limit) + CONTROL_CHAR.repeat(CONTROL_CHARS)

        val response = createFromText(newOwner(), textBody(body))

        if (constraint.measuresRaw) {
            assertDeclaredStatus(response, UNPROCESSABLE, DOCUMENTS_PATH, POST)
            assertThat(detailText(response)).isEqualTo(example(TOO_LONG_EXAMPLE))
        } else {
            // 정규화 후로 재라고 계약이 정하면 이 본문은 상한 이하이므로 **통과해야** 한다.
            assertThat(response.status)
                .withFailMessage("계약이 정규화 후 측정을 지정했는데 원시 길이로 거절했다")
                .isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        }
    }

    // ================================================================ 검증 실패 모양 (DC-22 · DC-23)

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
        // React `createDocumentFromText` 가 제목이 없을 때 **`title: null` 을 언제나 싣는다.**
        // 전역 기본값이 `Nulls.FAIL` 이라 여는 애너테이션이 없으면 이 요청이 통째로 422 가
        // 되고, 그 사고는 계약(anyOf[string, null])에도 어긋난다.
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

    // ================================================================ 요청 조립

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

    /**
     * 계정과 **기본 작업 공간**을 함께 만든다.
     *
     * 작업 공간이 하나도 없으면 업로드가 `StorageException`(500) 이 된다 — 그것은 우리
     * 불변식이 깨진 상태를 가리키는 갈래이고, 가입이 언제나 하나를 만들기 때문에 실제로는
     * 나오지 않는다. 여기서 함께 만들지 않으면 계약 케이스가 전부 그 500 을 재게 된다.
     */
    private fun newOwner(): UUID {
        val id = users.create("doc-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    // ================================================================ 단언 도구

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

        /**
         * 정규화(`stripControlChars`)하면 사라지지만 **원시 길이에는 세어지는** 문자.
         *
         * 이스케이프로 적는다 — 소스에 원시 제어 바이트를 싣지 않는다(쓰기 도구가 실제로
         * 그것을 실어 이 파일을 한 번 깨뜨렸다). `WorkspaceContractTest` 와 같은 값이다.
         */
        const val CONTROL_CHAR = "\u0001"
        const val CONTROL_CHARS = 5
    }
}

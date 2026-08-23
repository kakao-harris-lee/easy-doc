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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `PUT /conversions/{conversion_id}` 의 계약 — 명세 CU 표의 C-M 계층(목으로 재현되는 것만). */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ConversionReviewContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName("CU-9 필수 필드를 뺀 본문 → 422 · `detail` **배열** · 항목 키 정확히 셋 (X-C2)")
    fun `필수 필드 누락은 배열 detail 이다`() {
        val required = ContractSpec.schemaRequired(REVIEW_REQUEST_SCHEMA)
        assertThat(required)
            .withFailMessage("계약 %s 의 required 가 비었다 — 이 케이스가 성립하지 않는다", REVIEW_REQUEST_SCHEMA)
            .isNotEmpty()

        val response = put(newOwner(), UUID.randomUUID().toString(), EMPTY_JSON_OBJECT)

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        val detail = detailOf(response)
        assertThat(detail)
            .withFailMessage("필수 필드 누락이 배열 detail 이 아니다 — 검증 실패 갈래가 도메인 갈래와 뭉개졌다: %s", detail)
            .isInstanceOf(List::class.java)

        val declaredKeys = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        val items = detail as List<*>
        assertThat(items).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet())
                .withFailMessage("검증 실패 항목의 키 집합이 계약 %s 와 다르다: %s", VALIDATION_ITEM_SCHEMA, item.keys)
                .isEqualTo(declaredKeys)
        }
    }

    @Test
    @DisplayName("CU-9 인접 — 소유권보다 **본문 판정이 먼저다**: 남의 식별자로도 누락은 같은 422 배열이다")
    fun `누락 판정은 소유권보다 앞선다`() {
        val absent = put(newOwner(), UUID.randomUUID().toString(), EMPTY_JSON_OBJECT)
        val others = put(newOwner(), UUID.randomUUID().toString(), EMPTY_JSON_OBJECT)

        assertThat(absent.status).isEqualTo(UNPROCESSABLE)
        // 두 식별자 모두 「없는 것」이라 응답이 같아야 한다 — 이 팔이 존재를 누설하지 않는 근거다.
        assertThat(others.status).isEqualTo(absent.status)
        assertThat(detailOf(others)).isEqualTo(detailOf(absent))
    }

    @Test
    @DisplayName("경로 변수가 UUID 가 아니면 422 · `detail` **배열** — 자원 판정에 닿기 전이다")
    fun `경로 변수 형식 오류는 배열 detail 이다`() {
        val response = put(newOwner(), NOT_A_UUID, reviewBody("정상 수정본입니다"))

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        assertThat(detailOf(response))
            .withFailMessage("경로 변수 형식 오류가 배열 detail 이 아니다")
            .isInstanceOf(List::class.java)
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
            .withFailMessage("제출한 경로 변수 값이 응답에 실렸다")
            .doesNotContain(NOT_A_UUID)
    }

    @Test
    @DisplayName("계약이 이 오퍼레이션의 응답 스키마를 `GET` 과 **같은 것**으로 선언한다 — 두 노드를 계약에서 읽어 짝짓는다")
    fun `응답 스키마가 조회와 같다`() {
        val put = ContractSpec.successResponseSchemaRef(CONVERSION_ITEM_PATH, PUT)
        val get = ContractSpec.successResponseSchemaRef(CONVERSION_ITEM_PATH, GET)

        assertThat(put)
            .withFailMessage("PUT 의 200 스키마가 GET 과 다르다 — 계약 description 이 「같은 스키마」라고 적었다: %s ↔ %s", put, get)
            .isEqualTo(get)
    }

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, PUT).name}}",
            conversionId,
        )

    private fun put(
        owner: UUID,
        conversionId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .put(itemPath(conversionId)) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun reviewBody(text: String): String = json.writeValueAsString(mapOf(EDITED_TEXT_PROPERTY to text))

    private fun detailOf(response: MockHttpServletResponse): Any? {
        val body = response.getContentAsString(StandardCharsets.UTF_8)
        if (body.isEmpty()) return null
        return json.readValue(body, Map::class.java)[DETAIL]
    }

    private fun newOwner(): UUID {
        val id = users.create("review-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        const val GET = "get"
        const val PUT = "put"

        const val REVIEW_REQUEST_SCHEMA = "ConversionReviewRequest"
        const val EDITED_TEXT_PROPERTY = "edited_text"
        const val DETAIL = "detail"

        const val UNPROCESSABLE = 422

        const val EMPTY_JSON_OBJECT = "{}"
        const val NOT_A_UUID = "not-a-uuid-9999"

        /** 검증 실패 항목의 키 집합을 읽을 좌표. 이름이지 값이 아니다. */
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"
    }
}

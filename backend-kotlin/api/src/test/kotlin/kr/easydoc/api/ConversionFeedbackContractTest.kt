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

/** `PUT /conversions/{conversion_id}/feedback` 의 계약 — 목으로 재현되는 층만 본다. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ConversionFeedbackContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName("필수 필드를 뺀 본문 → 422 · `detail` **배열** · 항목 키가 계약 `ValidationErrorItem` 과 같다")
    fun `필수 필드 누락은 배열 detail 이다`() {
        val required = ContractSpec.schemaRequired(FEEDBACK_REQUEST_SCHEMA)
        assertThat(required)
            .withFailMessage("계약 %s 의 required 가 비었다 — 이 케이스가 성립하지 않는다", FEEDBACK_REQUEST_SCHEMA)
            .isNotEmpty()

        val response = put(newOwner(), UUID.randomUUID().toString(), EMPTY_JSON_OBJECT)

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        val detail = detailOf(response)
        assertThat(detail)
            .withFailMessage("필수 필드 누락이 배열 detail 이 아니다 — 스키마 층 갈래가 서비스 갈래와 뭉개졌다: %s", detail)
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
    @DisplayName("누락 판정이 **소유권보다 앞선다** — 남의 식별자로도 같은 422 배열이다")
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
        val response = put(newOwner(), NOT_A_UUID, validBody())

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        assertThat(detailOf(response))
            .withFailMessage("경로 변수 형식 오류가 배열 detail 이 아니다")
            .isInstanceOf(List::class.java)
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
            .withFailMessage("제출한 경로 변수 값이 응답에 실렸다")
            .doesNotContain(NOT_A_UUID)
    }

    @Test
    @DisplayName("요청·응답 스키마의 **이름**이 계약이 가리키는 것과 같다 — 이름을 코드가 정하지 않는다")
    fun `요청과 응답 스키마 이름이 계약과 같다`() {
        assertThat(ContractSpec.requestBodySchemaName(FEEDBACK_PATH, PUT))
            .withFailMessage("계약이 이 오퍼레이션의 요청 본문으로 다른 스키마를 가리킨다")
            .isEqualTo(FEEDBACK_REQUEST_SCHEMA)
        assertThat(ContractSpec.successResponseSchemaRef(FEEDBACK_PATH, PUT).substringAfterLast('/'))
            .withFailMessage("계약이 이 오퍼레이션의 200 본문으로 다른 스키마를 가리킨다")
            .isEqualTo(FEEDBACK_RESPONSE_SCHEMA)
    }

    @Test
    @DisplayName("계약의 required 집합만 담은 본문은 **스키마 층을 지난다** — 선택 필드가 필수로 굳지 않았다")
    fun `required 집합만 담은 본문은 스키마 층을 지난다`() {
        val required = ContractSpec.schemaRequired(FEEDBACK_REQUEST_SCHEMA)
        assertThat(required)
            .withFailMessage("계약 %s 의 required 가 이 케이스가 아는 세 필드와 다르다: %s", FEEDBACK_REQUEST_SCHEMA, required)
            .isEqualTo(setOf(PUBLISH_INTENT_PROPERTY, QUALITY_SCORE_PROPERTY, MINUTES_SPENT_PROPERTY))

        // 없는 변환이라 자원 판정에서 404 가 나간다 — 이 케이스가 재는 것은 **거기까지 갔다**는 것이다.
        val response = put(newOwner(), UUID.randomUUID().toString(), validBody())

        assertThat(response.status)
            .withFailMessage(
                "required 만 담은 본문이 422 다 — 선택 필드(`comment`)가 필수로 굳었거나 " +
                    "값 판정이 스키마 층으로 올라갔다: %s",
                response.getContentAsString(StandardCharsets.UTF_8),
            ).isNotEqualTo(UNPROCESSABLE)
        assertThat(response.status).isEqualTo(NOT_FOUND)
    }

    @Test
    @DisplayName("응답 스키마의 required 가 **여섯 필드 전부**다 — Kotlin 이 null 필드를 생략하면 React 분기가 달라진다")
    fun `응답 스키마의 required 가 속성 전부다`() {
        assertThat(ContractSpec.schemaRequired(FEEDBACK_RESPONSE_SCHEMA))
            .withFailMessage("응답 스키마의 required 가 선언된 속성 집합과 다르다 — 키가 빠질 수 있다는 뜻이다")
            .isEqualTo(ContractSpec.schemaPropertyNames(FEEDBACK_RESPONSE_SCHEMA))
    }

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun feedbackPath(conversionId: String): String =
        FEEDBACK_PATH.replace(
            "{${ContractSpec.pathVariable(FEEDBACK_PATH, PUT).name}}",
            conversionId,
        )

    private fun put(
        owner: UUID,
        conversionId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .put(feedbackPath(conversionId)) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    /** 배포 의향 값은 **계약에서 읽는다.** 척도 둘의 범위 정본은 `core/pilot` 이다. */
    private fun validBody(): String =
        json.writeValueAsString(
            mapOf(
                PUBLISH_INTENT_PROPERTY to ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA).first(),
                QUALITY_SCORE_PROPERTY to VALID_QUALITY_SCORE,
                MINUTES_SPENT_PROPERTY to VALID_MINUTES_SPENT,
            ),
        )

    private fun detailOf(response: MockHttpServletResponse): Any? {
        val body = response.getContentAsString(StandardCharsets.UTF_8)
        if (body.isEmpty()) return null
        return json.readValue(body, Map::class.java)[DETAIL]
    }

    private fun newOwner(): UUID {
        val id = users.create("feedback-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val FEEDBACK_PATH = "/conversions/{conversion_id}/feedback"
        const val PUT = "put"

        const val FEEDBACK_REQUEST_SCHEMA = "ConversionFeedbackRequest"
        const val FEEDBACK_RESPONSE_SCHEMA = "ConversionFeedbackResponse"
        const val PUBLISH_INTENT_SCHEMA = "PublishIntent"

        /** 검증 실패 항목의 키 집합을 읽을 좌표. 이름이지 값이 아니다. */
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        const val PUBLISH_INTENT_PROPERTY = "publish_intent"
        const val QUALITY_SCORE_PROPERTY = "quality_score"
        const val MINUTES_SPENT_PROPERTY = "minutes_spent"
        const val DETAIL = "detail"

        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422

        const val EMPTY_JSON_OBJECT = "{}"
        const val NOT_A_UUID = "not-a-uuid-9999"

        const val VALID_QUALITY_SCORE = 4
        const val VALID_MINUTES_SPENT = 12
    }
}

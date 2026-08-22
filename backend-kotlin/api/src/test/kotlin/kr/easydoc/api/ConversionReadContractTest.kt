package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryConversionRepository
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.api.support.StubMaskedItemReader
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `GET /conversions/{conversion_id}` 의 계약 — 명세 CR 표의 C-M 계층. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ConversionReadContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired
    private lateinit var conversions: InMemoryConversionRepository

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("CR-1 200 · 사적 헤더 2종의 **값과 부착 개수** · 최상위 키가 정확히 ConversionResponse.required")
    fun `완료 변환 조회가 계약과 같다`() {
        val owner = newOwner()
        val conversionId = completedConversion(owner)

        val response = read(owner, conversionId)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET))
        assertPrivateHeaders(response)

        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired(CONVERSION_SCHEMA))
    }

    @Test
    @DisplayName("CR-1 계약이 200 에 선언한 헤더가 전부 실려 나간다 — 선언이 비면 여기가 먼저 깨진다")
    fun `성공 응답의 선언 헤더가 전부 있다`() {
        val success = ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET)
        val declared = ContractSpec.responseHeaderNames(CONVERSION_ITEM_PATH, GET, success)

        assertThat(declared)
            .withFailMessage("계약이 변환 조회 성공 응답에 헤더를 하나도 선언하지 않았다 — 대조할 대상이 없다")
            .isNotEmpty()

        val owner = newOwner()
        val response = read(owner, completedConversion(owner))
        declared.forEach { header ->
            assertThat(response.getHeader(header))
                .withFailMessage("계약이 선언한 헤더 %s 가 응답에 없다", header)
                .isNotNull()
        }
    }

    @Test
    @DisplayName("CR-1 마스킹 항목의 키 집합이 정확히 MaskedItemResponse.required 이고 범주가 계약 enum 안이다 (P-32)")
    fun `마스킹 항목의 모양이 계약과 같다`() {
        val owner = newOwner()
        val conversionId = completedConversion(owner)

        val items = bodyOf(read(owner, conversionId))[MASKED_ITEMS_PROPERTY] as List<*>

        assertThat(items).describedAs("대역이 항목을 심었는데 응답이 비었다").isNotEmpty()
        val declaredKeys = ContractSpec.schemaRequired(MASKED_ITEM_SCHEMA)

        val declaredCategories = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY)
        val placeholderPattern = Regex(ContractSpec.schemaPropertyPattern(MASKED_ITEM_SCHEMA, PLACEHOLDER_PROPERTY))

        items.forEach { raw ->
            val item = raw as Map<*, *>
            assertThat(item.keys.map { it.toString() }.toSet())
                .withFailMessage("마스킹 항목의 키 집합이 계약 %s 와 다르다: %s", MASKED_ITEM_SCHEMA, item.keys)
                .isEqualTo(declaredKeys)
            assertThat(item[CATEGORY_PROPERTY].toString())
                .withFailMessage("범주가 계약 enum %s 밖이다 — 영문 코드가 새면 화면 문구가 갈린다", declaredCategories)
                .isIn(declaredCategories)
            assertThat(item[PLACEHOLDER_PROPERTY].toString())
                .withFailMessage("자리표시자가 계약 pattern 과 맞지 않는다 — 형태가 달라지면 원문 복원이 어긋난다")
                .matches(placeholderPattern.toPattern())
        }
    }

    @Test
    @DisplayName("CR-1 계약 범주 enum 이 `MaskCategory` **전부**를 덮는다 — 원소가 빠지면 여기가 먼저 깨진다 (N-26)")
    fun `계약 범주 집합이 구현 범주 전부를 덮는다`() {
        val declared = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY)

        assertThat(declared)
            .withFailMessage(
                "계약 %s.%s 의 enum 이 구현 범주 전부를 덮지 않는다 — 계약 %s / 구현 %s",
                MASKED_ITEM_SCHEMA,
                CATEGORY_PROPERTY,
                declared,
                MaskCategory.entries.map { it.label },
            ).containsExactlyInAnyOrderElementsOf(MaskCategory.entries.map { it.label })
    }

    @Test
    @DisplayName("CR-9 UUID 가 아닌 경로 변수 → 422 · detail **배열** · 항목 키 정확히 `ValidationErrorItem.required` (X-C2)")
    fun `UUID 가 아닌 경로 변수는 422 배열이다`() {
        val response = read(newOwner(), NOT_A_UUID)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    private fun read(
        owner: UUID,
        conversionId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(itemPath(conversionId)) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
            }.andReturn()
            .response

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, GET).name}}",
            conversionId,
        )

    /** 문서를 접수해 대기 중 변환을 만들고, 완료 상태로 표시한다. */
    private fun completedConversion(owner: UUID): String {
        val body = json.writeValueAsString(mapOf(TEXT_PROPERTY to "안내문 본문"))
        val created =
            mockMvc
                .post(DOCUMENTS_PATH) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn()
                .response
        check(created.status == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${created.status} ${created.getContentAsString(StandardCharsets.UTF_8)}"
        }
        val conversionId = UUID.fromString(bodyOf(created)[CONVERSION_ID_PROPERTY].toString())

        val category =
            MaskCategory.entries.first {
                it.label in
                    ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY)
            }
        val placeholder = "[[${category.label}1]]"
        val items = listOf(MaskedItemView(category, placeholder, Secret("실제값")))

        conversions.complete(
            conversionId = conversionId,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = seal("쉬운 글 초안입니다.", conversionId, EncryptedField.CONVERSION_EASY_TEXT),
                    maskedItems =
                        seal(
                            StubMaskedItemReader.encodeForStub(items).value,
                            conversionId,
                            EncryptedField.CONVERSION_MASKED_ITEMS,
                        ),
                    editedText = null,
                ),
            missingPlaceholders = emptyList(),
            model = "stub-model",
            providerName = "stub-provider",
            inputTokens = 12,
            outputTokens = 34,
        )
        return conversionId.toString()
    }

    private fun seal(
        plain: String,
        record: UUID,
        field: EncryptedField,
    ) = cipher.encrypt(PlainBody(plain), record, field)

    /** 계정과 기본 작업 공간을 함께 만든다. 사유는 `DocumentContractTest.newOwner`. */
    private fun newOwner(): UUID {
        val id = users.create("conversion-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    /** 값·부착 개수만 잰다(X-D2b). 하한선(X-D1)은 `PrivateHeaderFloorCensusTest` 가 진다. */
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
        assertThat(response.status)
            .withFailMessage(
                "GET %s 가 %d 이 아니다: %s",
                CONVERSION_ITEM_PATH,
                status,
                response.getContentAsString(StandardCharsets.UTF_8),
            ).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(CONVERSION_ITEM_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", CONVERSION_ITEM_PATH, status)
            .contains(status.toString())
    }

    /** `detail` 이 배열이고 항목 키 집합이 정확히 `ValidationErrorItem.required` 다. */
    private fun assertValidationArray(response: MockHttpServletResponse) {
        val items = bodyOf(response)[DETAIL]
        assertThat(items)
            .withFailMessage("detail 이 배열이 아니다 — 스키마 층 거절은 배열이어야 한다: %s", items)
            .isInstanceOf(List::class.java)

        val declared = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items as List<*>).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet())
                .withFailMessage("검증 항목의 키 집합이 계약 %s 와 다르다 — 제출값이 실리면 응답과 로그에 남는다", VALIDATION_ITEM_SCHEMA)
                .isEqualTo(declared)
        }
    }

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        bodyOf(response).keys.map { it.toString() }.toSet()

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val DOCUMENTS_PATH = "/documents"
        const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        const val GET = "get"
        const val POST = "post"
        const val UNPROCESSABLE = 422

        const val CONVERSION_SCHEMA = "ConversionResponse"
        const val MASKED_ITEM_SCHEMA = "MaskedItemResponse"
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        const val TEXT_PROPERTY = "text"
        const val CONVERSION_ID_PROPERTY = "conversion_id"
        const val MASKED_ITEMS_PROPERTY = "masked_items"
        const val CATEGORY_PROPERTY = "category"
        const val PLACEHOLDER_PROPERTY = "placeholder"
        const val DETAIL = "detail"

        const val NOT_A_UUID = "not-a-uuid"
    }
}

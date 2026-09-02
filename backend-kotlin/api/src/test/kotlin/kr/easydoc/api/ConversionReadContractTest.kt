package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.document.ConversionResponse
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
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.noOriginalPreservation
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.Instant
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
    @DisplayName("피드백을 낸 변환은 `feedback_submitted_at` 이 서고 `reviewed_at` 은 **그대로 null** 이다")
    fun `피드백 제출 시각이 검수 시각과 따로 나간다`() {
        val owner = newOwner()
        val conversionId = completedConversion(owner)
        conversions.recordFeedback(UUID.fromString(conversionId), FEEDBACK_SUBMITTED_AT)

        val body = bodyOf(read(owner, conversionId))

        assertThat(body[FEEDBACK_SUBMITTED_AT_PROPERTY])
            .withFailMessage("의견을 냈는데 제출 시각이 응답에 없다 — 새로고침한 검수 화면이 그 사실을 잃는다")
            .isEqualTo(FEEDBACK_SUBMITTED_AT.toString())
        assertThat(body[REVIEWED_AT_PROPERTY])
            .withFailMessage("피드백 제출이 `reviewed_at` 까지 찍었다 — 수정률 지표가 기대는 구분이 무너진다")
            .isNull()
    }

    @Test
    @DisplayName("의견을 낸 적이 없으면 `feedback_submitted_at` 은 **키는 있고 값이 null** 이다")
    fun `피드백이 없으면 제출 시각이 null 로 나간다`() {
        val owner = newOwner()

        val body = bodyOf(read(owner, completedConversion(owner)))

        assertThat(body.keys.map { it.toString() })
            .withFailMessage("키가 생략됐다 — React 가 undefined 를 받아 분기가 갈린다")
            .contains(FEEDBACK_SUBMITTED_AT_PROPERTY)
        assertThat(body[FEEDBACK_SUBMITTED_AT_PROPERTY]).isNull()
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

    /** 분모를 계약에서 읽는다 — `required` 에서 나가는 넷을 뺀 **열**이 「결과 필드」다. */
    @Test
    @DisplayName("완료 전 변환의 결과 필드 **열 전부**에 대해 응답이 조립되지 않는다 — 바이트를 만드는 자리가 막는다")
    fun `완료 전 결과를 담은 응답은 조립되지 않는다`() {
        val draft = "매퍼 가드가 막아야 하는 초안"
        val hidden = "900101-1234567"
        val base = beforeDoneView()
        val item = MaskedItemView(MaskCategory.RRN, PLACEHOLDER, Secret(hidden))
        val carrying =
            mapOf(
                "easy_text" to base.copy(easyText = PlainBody(draft)),
                "edited_text" to base.copy(editedText = PlainBody(draft)),
                "masked_items" to base.copy(maskedItems = listOf(item)),
                "reviewed_at" to base.copy(reviewedAt = Instant.EPOCH),
                "feedback_submitted_at" to base.copy(feedbackSubmittedAt = Instant.EPOCH),
                "missing_placeholders" to base.copy(missingPlaceholders = listOf(PLACEHOLDER)),
                "model" to base.copy(model = "probe-model"),
                "provider_name" to base.copy(providerName = "probe-provider"),
                "input_tokens" to base.copy(inputTokens = 1),
                "output_tokens" to base.copy(outputTokens = 2),
            )

        assertThat(carrying.keys)
            .withFailMessage("케이스가 계약의 「결과 필드」 집합과 다르다 — 계약이 바뀌었으면 여기도 넓혀라")
            .isEqualTo(ContractSpec.schemaRequired(CONVERSION_SCHEMA) - BEFORE_DONE_FIELDS)

        val assembled =
            carrying.filterValues { view ->
                runCatching { ConversionResponse.of(view) }
                    .exceptionOrNull()
                    ?.let { it !is IllegalArgumentException || it.message.orEmpty().contains(draft) } ?: true
            }
        assertThat(assembled.keys)
            .withFailMessage("완료 전인데 응답이 조립된(또는 가드 문구가 본문을 담은) 결과 필드: %s", assembled.keys)
            .isEmpty()
        assertThat(carrying.values.mapNotNull { runCatching { ConversionResponse.of(it) }.exceptionOrNull()?.message })
            .allSatisfy { assertThat(it).doesNotContain(hidden) }
        assertThat(ConversionResponse.of(base).status).isEqualTo(base.status.wireName)
    }

    /**
     * 계약이 「비어 있어야」 한다고 적은 조합.
     *
     * **형식 셋은 여기서도 값을 든다** — 결과 필드가 아니라 문서 메타이므로 완료 전에도
     * 나가고, 노출 가드가 그것을 결과로 세면 안 된다(계약 `ConversionResponse` 설명).
     * 원본이 있는 DOCX 를 고른 것은 `format_preservation` 이 `null` 인 갈래까지 함께
     * 지나게 하려는 것이다.
     */
    private fun beforeDoneView(): ConversionView =
        ConversionView(
            id = UUID.randomUUID(),
            documentId = UUID.randomUUID(),
            status = ConversionStatus.entries.first { !it.exposesResult },
            sourceFormat = SourceFormat.DOCX,
            exportFormat = ExportFormat.ofSource(SourceFormat.DOCX),
            exportFormatChoices = ExportFormat.choicesFor(SourceFormat.DOCX),
            formatPreservation = noOriginalPreservation(),
            easyText = null,
            editedText = null,
            reviewedAt = null,
            feedbackSubmittedAt = null,
            maskedItems = emptyList(),
            missingPlaceholders = emptyList(),
            model = null,
            providerName = null,
            inputTokens = null,
            outputTokens = null,
            failureCode = "ProviderUnavailable",
        )

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

        /**
         * 계약 `get.description` 이 완료 전에 나간다고 적은 **여덟** — 앞의 둘은 자원
         * 식별자이고, 뒤의 넷은 문서 메타에서 오는 **형식 셋**이라 완료 여부와 무관하다.
         */
        val BEFORE_DONE_FIELDS =
            setOf(
                "id",
                "document_id",
                "status",
                "failure_code",
                "source_format",
                "export_format",
                "export_format_choices",
                "format_preservation",
            )

        const val PLACEHOLDER = "[[주민등록번호1]]"

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
        const val REVIEWED_AT_PROPERTY = "reviewed_at"
        const val FEEDBACK_SUBMITTED_AT_PROPERTY = "feedback_submitted_at"

        /** 대역이 심는 피드백 제출 시각. 값 자체는 아무래도 좋고 **왕복하는가**만 잰다. */
        val FEEDBACK_SUBMITTED_AT: Instant = Instant.EPOCH.plusSeconds(120)
        const val CATEGORY_PROPERTY = "category"
        const val PLACEHOLDER_PROPERTY = "placeholder"
        const val DETAIL = "detail"

        const val NOT_A_UUID = "not-a-uuid"
    }
}

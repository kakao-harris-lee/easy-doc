package kr.easydoc.api

import kr.easydoc.api.document.DocumentCreatedResponse
import kr.easydoc.api.document.DocumentListItemResponse
import kr.easydoc.api.document.DocumentListResponse
import kr.easydoc.api.document.DocumentSourceResponse
import kr.easydoc.api.document.DocumentTextRequest
import kr.easydoc.api.document.LIST_LIMIT_DEFAULT
import kr.easydoc.api.document.LIST_LIMIT_MAX
import kr.easydoc.api.document.LIST_LIMIT_MIN
import kr.easydoc.api.document.LIST_OFFSET_DEFAULT
import kr.easydoc.api.document.LIST_OFFSET_MIN
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MeasurementAxis
import kr.easydoc.application.document.MISSING_FILE_PART_MESSAGE
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** 문서 오퍼레이션이 기대는 계약 노드를 계약 파일에서 읽는다 — Spring 없이 돈다. */
class DocumentContractNodeTest {
    @Test
    @DisplayName("P-24 업로드 바이트 상한이 계약과 코드에서 같다 (DC-12·DC-13 의 경계 출처)")
    fun `업로드 상한이 계약에서 온다`() {
        assertThat(MAX_UPLOAD_BYTES).isEqualTo(ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY).toLong())
    }

    @Test
    @DisplayName("P-27 전용 안내 문구와 예산 값이 계약에 있고 비어 있지 않다")
    fun `입력 상한 산문 노드가 실재한다`() {
        assertThat(ContractSpec.text(INPUT_LIMITS, LEGACY_DOC_POLICY_KEY)).isNotBlank()
        assertThat(ContractSpec.text(INPUT_LIMITS, REJECTED_PDF_KEY)).isNotBlank()
        assertThat(ContractSpec.inputLimit(ZIP_BUDGET_KEY)).isPositive()
        assertThat(ContractSpec.inputLimit(MAX_EXTRACTED_CHARS_KEY)).isPositive()
    }

    @Test
    @DisplayName("P-25 limit·offset 의 하한·상한·기본값이 계약에 있고, **코드 상수와 같다**")
    fun `페이지 경계가 계약에서 온다`() {
        val limit = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val offset = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)

        assertThat(LIST_LIMIT_MIN).isEqualTo(limit.min.toLong())
        assertThat(LIST_LIMIT_MAX).isEqualTo(requireNotNull(limit.max) { "계약이 list_limit 에 상한을 두지 않았다" }.toLong())
        assertThat(LIST_LIMIT_DEFAULT.toInt()).isEqualTo(limit.default)
        assertThat(LIST_OFFSET_MIN).isEqualTo(offset.min.toLong())
        assertThat(LIST_OFFSET_DEFAULT.toInt()).isEqualTo(offset.default)

        assertThat(offset.max)
            .withFailMessage("계약이 list_offset 에 상한을 뒀다 — 컨트롤러에 @Max 를 걸어야 한다")
            .isNull()
    }

    @Test
    @DisplayName("P-25 계약 **안**의 이중 선언이 일치한다 — x-input-limits 와 오퍼레이션 인라인 parameters")
    fun `계약 내부의 페이지 경계 이중 선언이 일치한다`() {
        val declared = ContractSpec.queryParameters(DOCUMENTS_PATH, GET).associateBy { it.name }

        listOf(LIST_LIMIT_KEY to LIMIT_PARAM, LIST_OFFSET_KEY to OFFSET_PARAM).forEach { (node, parameter) ->
            val range = ContractSpec.inputLimitRange(node)
            val schema = declared[parameter] ?: error("계약 GET $DOCUMENTS_PATH 에 쿼리 파라미터 $parameter 가 없다")
            assertThat(schema.intKeyword(MINIMUM_KEYWORD))
                .withFailMessage("%s 의 하한이 x-input-limits(%d) 와 파라미터 스키마에서 갈렸다", parameter, range.min)
                .isEqualTo(range.min)
            assertThat(schema.intKeyword(MAXIMUM_KEYWORD))
                .withFailMessage("%s 의 상한이 두 자리에서 갈렸다", parameter)
                .isEqualTo(range.max)
            assertThat(schema.intKeyword(DEFAULT_KEYWORD))
                .withFailMessage("%s 의 기본값이 두 자리에서 갈렸다", parameter)
                .isEqualTo(range.default)

            assertThat(schema.required).isFalse()
        }
    }

    @Test
    @DisplayName("P-33 DocumentCreatedResponse 의 required 와 DTO 의 JSON 키가 정확히 같다")
    fun `응답 DTO 의 키가 계약 required 와 같다`() {
        val declared = ContractSpec.schemaRequired(CREATED_SCHEMA)

        assertThat(jsonPropertyNames(DocumentCreatedResponse::class)).isEqualTo(declared)
    }

    @Test
    @DisplayName("P-33 목록 두 스키마의 required 와 DTO 의 JSON 키가 정확히 같다 (DL-1·DL-2 의 오라클)")
    fun `목록 DTO 의 키가 계약 required 와 같다`() {
        assertThat(jsonPropertyNames(DocumentListResponse::class)).isEqualTo(ContractSpec.schemaRequired(LIST_SCHEMA))
        assertThat(jsonPropertyNames(DocumentListItemResponse::class))
            .isEqualTo(ContractSpec.schemaRequired(LIST_ITEM_SCHEMA))
    }

    @Test
    @DisplayName("P-33 원문 조회 스키마의 required 와 DTO 의 JSON 키가 정확히 같다")
    fun `원문 DTO 의 키가 계약 required 와 같다`() {
        assertThat(jsonPropertyNames(DocumentSourceResponse::class))
            .isEqualTo(ContractSpec.schemaRequired(SOURCE_SCHEMA))
    }

    @Test
    @DisplayName("원문은 **목록에 실리지 않는다** — 본문 필드가 DocumentListItem 에 없다")
    fun `목록 스키마에 원문이 없다`() {
        assertThat(ContractSpec.schemaPropertyNames(LIST_ITEM_SCHEMA))
            .withFailMessage(
                "목록 한 줄에 %s 가 생겼다 — 목록은 한 번에 여러 문서를 돌려주는 자리라 " +
                    "원문을 얹으면 응답 크기가 문서 수만큼 곱해진다",
                SOURCE_TEXT_PROPERTY,
            ).doesNotContain(SOURCE_TEXT_PROPERTY)
    }

    @Test
    @DisplayName("P-34 text 의 상한·측정 축·문구가 계약 세 자리에서 서로 같다")
    fun `본문 길이 제약이 계약 안에서 일치한다`() {
        val field = ContractSpec.requestFieldConstraint(TEXT_FIELD)
        val schemaConstraint = ContractSpec.serviceConstraint(TEXT_REQUEST_SCHEMA, TEXT_PROPERTY)

        assertThat(field.limit)
            .withFailMessage("본문 상한이 x-input-limits 와 fields[].limit 에서 갈렸다")
            .isEqualTo(ContractSpec.inputLimit(MAX_CONVERTIBLE_CHARS_KEY))
        assertThat(schemaConstraint["max_length"])
            .withFailMessage("본문 상한이 fields[].limit 와 스키마 x-service-constraint 에서 갈렸다")
            .isEqualTo(field.limit)
        assertThat(MeasurementAxis.ofToken(schemaConstraint["measured_on"].toString(), TEXT_FIELD))
            .withFailMessage("두 자리의 measured_on 이 서로 다른 축을 가리킨다")
            .isEqualTo(field.axis)
    }

    @Test
    @DisplayName("P-34 text 는 **원시** 값으로 잰다 — edited_text 와 축이 다르다(x-open-asymmetry 현행 (가))")
    fun `본문은 원시 길이로 잰다`() {
        assertThat(ContractSpec.requestFieldConstraint(TEXT_FIELD).measuresRaw).isTrue()
        assertThat(ContractSpec.requestFieldConstraint(EDITED_TEXT_FIELD).measuresNormalized).isTrue()
        assertThat(MAX_CONVERTIBLE_CHARS).isEqualTo(ContractSpec.inputLimit(MAX_CONVERTIBLE_CHARS_KEY))
    }

    @Test
    @DisplayName("P-36 요청 본문의 미디어 타입 두 갈래가 계약에서 온다 — 컨트롤러 consumes 와 같다")
    fun `두 입력 갈래가 계약과 같다`() {
        val declared = ContractSpec.requestBodyMediaTypes(DOCUMENTS_PATH, POST)

        assertThat(declared).containsExactlyInAnyOrder(JSON_MEDIA_TYPE, MULTIPART_MEDIA_TYPE)
    }

    @Test
    @DisplayName("P-36 붙여넣기 요청 DTO 의 JSON 키가 계약 스키마 속성과 정확히 같다")
    fun `붙여넣기 요청 DTO 가 계약 속성과 같다`() {
        assertThat(creatorPropertyNames(DocumentTextRequest::class))
            .isEqualTo(ContractSpec.schemaPropertyNames(TEXT_REQUEST_SCHEMA))
        assertThat(ContractSpec.schemaRequired(TEXT_REQUEST_SCHEMA)).containsExactly(TEXT_PROPERTY)
    }

    @Test
    @DisplayName("P-36 파일 요청의 파트 이름이 계약에서 온다 — 코드에 복제하지 않는다")
    fun `파일 요청 파트 이름이 계약과 같다`() {
        val properties = ContractSpec.schemaPropertyNames(FILE_REQUEST_SCHEMA)

        assertThat(properties).containsExactlyInAnyOrder(FILE_PROPERTY, TITLE_PROPERTY, WORKSPACE_ID_PROPERTY)
        assertThat(ContractSpec.schemaRequired(FILE_REQUEST_SCHEMA)).containsExactly(FILE_PROPERTY)

        assertThat(ContractSpec.text(COMPONENTS, SCHEMAS, FILE_REQUEST_SCHEMA, PROPERTIES, FILE_PROPERTY, DESCRIPTION))
            .withFailMessage("계약이 적은 파일 파트 거절 문구와 구현 상수가 갈렸다")
            .contains(MISSING_FILE_PART_MESSAGE)
    }

    @Test
    @DisplayName("P-38 저장 정의역 조항의 문구가 POST /documents 422 예시와 같다 — 오라클이 둘이 되지 않는다")
    fun `저장 정의역 문구가 두 자리에서 같다`() {
        val domain = ContractSpec.storedTextDomain()

        assertThat(domain.detail)
            .withFailMessage("x-stored-text-domain.detail 과 422 예시 undecodable_text 가 갈렸다")
            .isEqualTo(ContractSpec.pathExampleDetail(DOCUMENTS_PATH, POST, UNPROCESSABLE, UNDECODABLE_EXAMPLE))
    }

    @Test
    @DisplayName("P-38 조항의 문구·모양·상태가 구현 상수와 같고, 측정 대기 팔이 목록으로 드러난다")
    fun `저장 정의역 조항이 구현과 묶여 있다`() {
        val domain = ContractSpec.storedTextDomain()

        assertThat(domain.detail)
            .withFailMessage("계약 문구와 PlainBody 의 고정 문구가 갈렸다 — 나가는 바이트가 계약과 다르다")
            .isEqualTo(PlainBody.UNPAIRED_SURROGATE_MESSAGE)
        assertThat(domain.status).isEqualTo(UNPROCESSABLE)
        assertThat(domain.detailShape).isEqualTo(STRING_SHAPE)

        assertThat(domain.measuredArms().map { it.field })
            .withFailMessage("측정으로 선언된 팔이 없다 — 이 조항은 오늘 아무 데도 서지 않는다")
            .isNotEmpty()
        println("P-38 측정 대기 팔(마감 목록): ${domain.pendingArms().map { it.field }.ifEmpty { listOf("없음") }}")
    }

    @Test
    @DisplayName("P-39 폐기한 상태 코드가 어느 오퍼레이션의 responses 에도 없다 (전역·목록 비어 있지 않음 포함)")
    fun `폐기한 상태 코드가 되살아나지 않는다`() {
        val retired = ContractSpec.retiredResponseStatuses()
        assertThat(retired).isNotEmpty()

        val declared = ContractSpec.declaredResponseStatuses()
        assertThat(declared)
            .withFailMessage("계약에서 응답 선언을 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        val revived = declared.filter { (_, _, status) -> status in retired }
        assertThat(revived)
            .withFailMessage(
                "폐기한 상태 코드가 되살아났다: %s — 되살리려면 x-retired-responses.if_it_should_return 이 요구하는 네 가지를 한 단위로 갖춰라",
                revived.map { (path, method, status) -> "${method.uppercase()} $path → $status" },
            ).isEmpty()
    }

    @Test
    @DisplayName("P-39 폐기 목록이 실제로 무언가를 겨눈다 — 있지도 않은 코드만 열거하면 대조가 공허하다")
    fun `폐기 목록이 겨누는 자리가 있다`() {
        val retired = ContractSpec.retiredResponseStatuses()

        assertThat(retired).allMatch { it.length == STATUS_CODE_LENGTH && it.all(Char::isDigit) }
        assertThat(retired).doesNotHaveDuplicates()
    }

    /** 응답 DTO 가 실제로 내보내는 JSON 키. `@get:JsonProperty` 를 읽는다. */
    private fun jsonPropertyNames(type: kotlin.reflect.KClass<*>): Set<String> =
        type.memberProperties
            .mapNotNull { property ->
                property.getter.annotations
                    .filterIsInstance<com.fasterxml.jackson.annotation.JsonProperty>()
                    .firstOrNull()
                    ?.value
            }.toSet()

    /** 요청 DTO 가 실제로 받아들이는 JSON 키. `@param:JsonProperty` 를 읽는다. */
    private fun creatorPropertyNames(type: kotlin.reflect.KClass<*>): Set<String> {
        val constructor =
            type.primaryConstructor ?: error("${type.qualifiedName} 에 주 생성자가 없다 — 바인딩 대상을 읽을 수 없다")
        return constructor.parameters
            .map { parameter ->
                parameter.annotations
                    .filterIsInstance<com.fasterxml.jackson.annotation.JsonProperty>()
                    .firstOrNull()
                    ?.value
                    ?: error("${type.qualifiedName}.${parameter.name} 에 @JsonProperty 가 없다 — 계약 이름과 묶이지 않았다")
            }.toSet()
    }

    private companion object {
        const val DOCUMENTS_PATH = "/documents"
        const val POST = "post"
        const val GET = "get"
        const val UNPROCESSABLE = 422
        const val STATUS_CODE_LENGTH = 3

        const val INPUT_LIMITS = "x-input-limits"
        const val COMPONENTS = "components"
        const val SCHEMAS = "schemas"
        const val PROPERTIES = "properties"
        const val DESCRIPTION = "description"

        const val MAX_UPLOAD_BYTES_KEY = "max_upload_bytes"
        const val MAX_CONVERTIBLE_CHARS_KEY = "max_convertible_chars"
        const val MAX_EXTRACTED_CHARS_KEY = "max_extracted_chars"
        const val ZIP_BUDGET_KEY = "zip_uncompressed_budget_bytes"
        const val LEGACY_DOC_POLICY_KEY = "legacy_doc_policy"
        const val REJECTED_PDF_KEY = "rejected_pdf"

        const val CREATED_SCHEMA = "DocumentCreatedResponse"
        const val LIST_SCHEMA = "DocumentListResponse"
        const val LIST_ITEM_SCHEMA = "DocumentListItem"
        const val SOURCE_SCHEMA = "DocumentSourceResponse"
        const val SOURCE_TEXT_PROPERTY = "source_text"

        /** 계약 `x-input-limits` 의 노드 이름. 값이 아니라 자리다. */
        const val LIST_LIMIT_KEY = "list_limit"
        const val LIST_OFFSET_KEY = "list_offset"

        /** 계약 `paths./documents.get.parameters` 의 이름. */
        const val LIMIT_PARAM = "limit"
        const val OFFSET_PARAM = "offset"

        /** JSON Schema 키워드 이름. 계약이 파라미터 스키마에 쓰는 것들이다. */
        const val MINIMUM_KEYWORD = "minimum"
        const val MAXIMUM_KEYWORD = "maximum"
        const val DEFAULT_KEYWORD = "default"
        const val TEXT_REQUEST_SCHEMA = "DocumentTextRequest"
        const val FILE_REQUEST_SCHEMA = "DocumentFileRequest"

        const val TEXT_FIELD = "DocumentTextRequest.text"
        const val EDITED_TEXT_FIELD = "ConversionReviewRequest.edited_text"
        const val TEXT_PROPERTY = "text"
        const val TITLE_PROPERTY = "title"
        const val FILE_PROPERTY = "file"
        const val WORKSPACE_ID_PROPERTY = "workspace_id"

        const val JSON_MEDIA_TYPE = "application/json"
        const val MULTIPART_MEDIA_TYPE = "multipart/form-data"

        const val UNDECODABLE_EXAMPLE = "undecodable_text"
        const val STRING_SHAPE = "string"
    }
}

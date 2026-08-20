package kr.easydoc.api

import kr.easydoc.api.document.DocumentCreatedResponse
import kr.easydoc.api.document.DocumentListItemResponse
import kr.easydoc.api.document.DocumentListResponse
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

/**
 * **문서 오퍼레이션이 기대는 계약 노드를 계약 파일에서 읽는다** — Spring 없이 돈다.
 *
 * 명세 §4 의 파서 노드 중 이 파일 몫: **P-24 · P-25 · P-27 · P-33 · P-34 · P-36 · P-38 · P-39**.
 * (P-26 은 `UploadFormatContractTest`, P-40 은 `TitlePolicyContractTest` 가 이미 진다.)
 *
 * **P-25 는 `GET /documents` 커밋이 더했다.** 이름이 `POST` 로만 좁혀져 있었는데 페이지
 * 파라미터 노드가 들어오면서 대상이 오퍼레이션 둘이 됐다 — 파일을 가르지 않은 이유는 여기
 * 있는 것이 전부 「계약 안 대조 + 계약↔코드 상수 대조」라는 **한 종류**이기 때문이다.
 *
 * ## 왜 별도 클래스인가
 *
 * 여기 든 것은 전부 **계약 안 대조**(같은 사실이 계약 안 여러 자리에 적혀 있는지)와
 * **계약 ↔ 코드 상수 대조**다. Spring 컨텍스트도 DB 도 필요 없으므로 슬라이스에 얹으면
 * 조립 실패가 이 대조까지 함께 빨갛게 만든다 — 원인이 섞인다.
 *
 * YAML 파서를 새로 만들지 않는다 — `UploadFormatContractTest`·`TitlePolicyContractTest` 와
 * 같은 [ContractSpec] 을 쓴다(배선을 셋으로 만들지 마라, 계약 레인 지시 K-8).
 */
class DocumentContractNodeTest {
    // ================================================================ P-24 · P-27 — 입력 상한

    @Test
    @DisplayName("P-24 업로드 바이트 상한이 계약과 코드에서 같다 (DC-12·DC-13 의 경계 출처)")
    fun `업로드 상한이 계약에서 온다`() {
        // 상한이 코드에 복제돼 있으면 계약이 값을 바꿔도 옛 경계를 잰다 — N-23 이 재는 축이다.
        assertThat(MAX_UPLOAD_BYTES).isEqualTo(ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY).toLong())
    }

    @Test
    @DisplayName("P-27 전용 안내 문구와 예산 값이 계약에 있고 비어 있지 않다")
    fun `입력 상한 산문 노드가 실재한다`() {
        // 값 자체는 추출기 쪽(`ExtractionMessages`·`ExtractionLimits`)이 지고, 이 커밋이
        // 재는 것은 **그 노드가 계약에 살아 있는가**다. 노드가 사라지면 다음 커밋이
        // 문구를 코드에서만 읽게 되고 그 순간 계약이 되는 것은 코드다.
        assertThat(ContractSpec.text(INPUT_LIMITS, LEGACY_DOC_POLICY_KEY)).isNotBlank()
        assertThat(ContractSpec.text(INPUT_LIMITS, REJECTED_PDF_KEY)).isNotBlank()
        assertThat(ContractSpec.inputLimit(ZIP_BUDGET_KEY)).isPositive()
        assertThat(ContractSpec.inputLimit(MAX_EXTRACTED_CHARS_KEY)).isPositive()
    }

    // ================================================================ P-25 — 페이지 파라미터 경계

    @Test
    @DisplayName("P-25 limit·offset 의 하한·상한·기본값이 계약에 있고, **코드 상수와 같다**")
    fun `페이지 경계가 계약에서 온다`() {
        val limit = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val offset = ContractSpec.inputLimitRange(LIST_OFFSET_KEY)

        // Bean Validation 애너테이션 인자는 컴파일 시점 상수만 받아 계약을 읽을 자리가 없다
        // (`ListPageLimits.kt`). 그래서 **복제를 대조로 지킨다** — MAX_UPLOAD_BYTES(P-24) 와
        // 같은 처방이고, 값이 갈리면 N-24 가 이 자리에서 먼저 빨개진다.
        assertThat(LIST_LIMIT_MIN).isEqualTo(limit.min.toLong())
        assertThat(LIST_LIMIT_MAX).isEqualTo(requireNotNull(limit.max) { "계약이 list_limit 에 상한을 두지 않았다" }.toLong())
        assertThat(LIST_LIMIT_DEFAULT.toInt()).isEqualTo(limit.default)
        assertThat(LIST_OFFSET_MIN).isEqualTo(offset.min.toLong())
        assertThat(LIST_OFFSET_DEFAULT.toInt()).isEqualTo(offset.default)

        // **상한이 없는 것과 상한이 사라진 것을 구분한다.** offset 에는 계약이 상한을 두지
        // 않았고, 그 사실이 이 단언으로 남는다 — 나중에 상한이 생기면 여기서 드러난다.
        assertThat(offset.max)
            .withFailMessage("계약이 list_offset 에 상한을 뒀다 — 컨트롤러에 @Max 를 걸어야 한다")
            .isNull()
    }

    @Test
    @DisplayName("P-25 계약 **안**의 이중 선언이 일치한다 — x-input-limits 와 오퍼레이션 인라인 parameters")
    fun `계약 내부의 페이지 경계 이중 선언이 일치한다`() {
        val declared = ContractSpec.queryParameters(DOCUMENTS_PATH, GET).associateBy { it.name }

        // 같은 경계가 계약 안에 두 벌 있다(`x-input-limits` 와 파라미터 `schema`). 한쪽만
        // 고치는 편집이 이 저장소에서 가장 흔한 드리프트이므로 합치지 않고 **대조로** 지킨다
        // (P-7 과 같은 형태). N-24 는 `x-input-limits` 만 바꾸는 변이라 여기서도 깨진다.
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
            // 계약이 둘 다 선택 파라미터로 두었다 — 필수가 되면 DL-7(생략)이 성립하지 않는다.
            assertThat(schema.required).isFalse()
        }
    }

    // ================================================================ P-33 — 응답 키 집합

    @Test
    @DisplayName("P-33 DocumentCreatedResponse 의 required 와 DTO 의 JSON 키가 정확히 같다")
    fun `응답 DTO 의 키가 계약 required 와 같다`() {
        val declared = ContractSpec.schemaRequired(CREATED_SCHEMA)

        // Jackson 네이밍 사고(`documentId` 가 그대로 나가는 것)가 이 대조에서만 잡힌다.
        // 실제 응답 바이트로도 재지만(DC-1), 그쪽은 엔드포인트가 살아 있어야 돌고
        // 이 대조는 DTO 가 생기는 즉시 돈다.
        assertThat(jsonPropertyNames(DocumentCreatedResponse::class)).isEqualTo(declared)
    }

    @Test
    @DisplayName("P-33 목록 두 스키마의 required 와 DTO 의 JSON 키가 정확히 같다 (DL-1·DL-2 의 오라클)")
    fun `목록 DTO 의 키가 계약 required 와 같다`() {
        // DL-1·DL-2 는 응답 바이트로 재지만 컨텍스트·DB 가 서야 돈다. 이 대조는 DTO 가
        // 생기는 즉시 돌고, snake_case 누락(`hasMore` 가 그대로 나가는 것)을 먼저 잡는다.
        assertThat(jsonPropertyNames(DocumentListResponse::class)).isEqualTo(ContractSpec.schemaRequired(LIST_SCHEMA))
        assertThat(jsonPropertyNames(DocumentListItemResponse::class))
            .isEqualTo(ContractSpec.schemaRequired(LIST_ITEM_SCHEMA))
    }

    // ================================================================ P-34 — 본문 길이 축

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
        // DC-11 의 기대값이 여기서 온다. 두 필드의 축이 같아지면 DC-11 과 CU-6 중
        // 하나가 반드시 뒤집힌다 — N-25 가 재는 자리이고, 그 대비가 계약의 미결 항목이다.
        assertThat(ContractSpec.requestFieldConstraint(TEXT_FIELD).measuresRaw).isTrue()
        assertThat(ContractSpec.requestFieldConstraint(EDITED_TEXT_FIELD).measuresNormalized).isTrue()
        assertThat(MAX_CONVERTIBLE_CHARS).isEqualTo(ContractSpec.inputLimit(MAX_CONVERTIBLE_CHARS_KEY))
    }

    // ================================================================ P-36 — 두 입력 갈래

    @Test
    @DisplayName("P-36 요청 본문의 미디어 타입 두 갈래가 계약에서 온다 — 컨트롤러 consumes 와 같다")
    fun `두 입력 갈래가 계약과 같다`() {
        val declared = ContractSpec.requestBodyMediaTypes(DOCUMENTS_PATH, POST)

        // 계약이 갈래를 하나 빼거나 이름을 바꾸면 여기서 드러난다. 컨트롤러가 실제로 그
        // 두 갈래를 받는지는 DC-4·DC-5 가 소켓에서 잰다.
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

        // 컨트롤러가 꺼내는 세 파트 이름의 출처. 자동 생성물에 없는 유일한 요청 본문이라
        // 계약 파일이 유일한 기록이고, 이름이 갈리면 파일 모드가 통째로 422 가 된다.
        assertThat(properties).containsExactlyInAnyOrder(FILE_PROPERTY, TITLE_PROPERTY, WORKSPACE_ID_PROPERTY)
        assertThat(ContractSpec.schemaRequired(FILE_REQUEST_SCHEMA)).containsExactly(FILE_PROPERTY)
        // 「파트가 없거나 파일이 아니면 422」 문구는 계약이 이 속성 설명 안에 적었다.
        // 값을 앵커 없이 산문에서 뽑을 수는 없으므로 **구현 상수가 그 산문 안에 있는지**로 묶는다.
        assertThat(ContractSpec.text(COMPONENTS, SCHEMAS, FILE_REQUEST_SCHEMA, PROPERTIES, FILE_PROPERTY, DESCRIPTION))
            .withFailMessage("계약이 적은 파일 파트 거절 문구와 구현 상수가 갈렸다")
            .contains(MISSING_FILE_PART_MESSAGE)
    }

    // ================================================================ P-38 — 저장 정의역 (K-9)

    @Test
    @DisplayName("P-38 저장 정의역 조항의 문구가 POST /documents 422 예시와 같다 — 오라클이 둘이 되지 않는다")
    fun `저장 정의역 문구가 두 자리에서 같다`() {
        val domain = ContractSpec.storedTextDomain()

        // 두 자리가 갈리면 DC-24 가 어느 쪽을 오라클로 삼든 다른 쪽과 어긋난다.
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

        // 오늘 재는 팔과 아직 못 재는 팔을 **둘 다** 드러낸다. 표식을 안 읽으면
        // "마감이 남았다"는 사실이 테스트에서 사라진다(명세 P-38).
        assertThat(domain.measuredArms().map { it.field })
            .withFailMessage("측정으로 선언된 팔이 없다 — 이 조항은 오늘 아무 데도 서지 않는다")
            .isNotEmpty()
        println("P-38 측정 대기 팔(마감 목록): ${domain.pendingArms().map { it.field }.ifEmpty { listOf("없음") }}")
    }

    // ================================================================ P-39 — 폐기 응답 (K-8)

    @Test
    @DisplayName("P-39 폐기한 상태 코드가 어느 오퍼레이션의 responses 에도 없다 (전역·목록 비어 있지 않음 포함)")
    fun `폐기한 상태 코드가 되살아나지 않는다`() {
        // ⑴ **목록이 비어 있지 않다.** 비면 아래 「전건이 없다」가 공허하게 참이 된다
        //    (`CLAUDE.md` 규칙 4 ⑶ — 빈 선언에서 통과하면 안 된다). 접근자가 그 자리에서 끊는다.
        val retired = ContractSpec.retiredResponseStatuses()
        assertThat(retired).isNotEmpty()

        // ⑵ **분모가 비어 있지 않다.** 오퍼레이션을 하나도 못 찾으면 아무것도 훑지 않은 것이다.
        val declared = ContractSpec.declaredResponseStatuses()
        assertThat(declared)
            .withFailMessage("계약에서 응답 선언을 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        // ⑶ 전건이 `paths` 어디에도 없다.
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

        // 「전건이 없다」는 폐기 목록이 `999` 같은 허수여도 참이다. 그래서 각 항목이
        // **실제 HTTP 상태 코드 모양**인지와, 그 코드를 되살리는 손상이 위 케이스에서
        // 실제로 걸릴 수 있는 형태인지를 함께 본다.
        assertThat(retired).allMatch { it.length == STATUS_CODE_LENGTH && it.all(Char::isDigit) }
        assertThat(retired).doesNotHaveDuplicates()
    }

    // ================================================================ 도구

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

        /** 계약 `x-input-limits` 의 노드 이름. 값이 아니라 **자리**다. */
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

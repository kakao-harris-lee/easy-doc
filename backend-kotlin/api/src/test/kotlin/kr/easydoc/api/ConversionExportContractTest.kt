package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractExportSpec
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryConversionRepository
import kr.easydoc.api.support.InMemoryDocumentRepository
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.api.support.StubMaskedItemReader
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFormat
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** `GET /conversions/{conversion_id}/export` 의 계약 — 형식·헤더·거절 갈래. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ConversionExportContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired
    private lateinit var documents: InMemoryDocumentRepository

    @Autowired
    private lateinit var conversions: InMemoryConversionRepository

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("계약 ExportFormat.enum 이 구현 확장자·미디어 타입과 같다")
    fun `형식 enum 이 구현과 같다`() {
        val declared = ContractSpec.schemaEnum(FORMAT_SCHEMA)

        assertThat(ExportFormat.entries.map { it.extension })
            .containsExactlyInAnyOrderElementsOf(declared)
        assertThat(ExportFormat.entries.map { it.mediaType }.toSet())
            .isEqualTo(ContractExportSpec.successContentTypes())
    }

    @Test
    @DisplayName("계약 enum 의 각 형식이 **그 형식을 내보내는 원본에서** 200 · 선언 헤더 · filename* · 미디어 타입을 낸다")
    fun `형식마다 성공 응답이 계약과 같다`() {
        val success = ContractSpec.successStatus(EXPORT_PATH, GET)
        val declaredHeaders = ContractSpec.responseHeaderNames(EXPORT_PATH, GET, success)
        val formats = ContractSpec.schemaEnum(FORMAT_SCHEMA)

        assertThat(formats).describedAs("계약 형식 enum 이 비었다").isNotEmpty()
        assertThat(declaredHeaders).describedAs("내보내기 200 헤더가 비었다").isNotEmpty()
        // 형식마다 그것을 내는 원본이 있어야 대조가 성립한다 — 없으면 그 형식이 검사되지 않는다.
        assertThat(formats.map { ExportFormat.ofWireName(it) })
            .describedAs("계약 형식 중 어느 원본도 내지 못하는 값이 있다 — 유도표와 값 집합이 갈렸다")
            .allSatisfy { assertThat(sourceProducing(checkNotNull(it))).isNotNull() }

        formats.forEach { format ->
            val exportFormat = ExportFormat.ofWireName(format) ?: error("계약 enum $format 이 구현에 없다")
            val owner = newOwner()
            val conversionId = completedConversion(owner, checkNotNull(sourceProducing(exportFormat)))

            val response = export(owner, conversionId, format)

            assertThat(response.status)
                .withFailMessage("%s 내보내기가 %d 이 아니다: %s", format, success, response.getContentAsString())
                .isEqualTo(success)
            declaredHeaders.forEach { header ->
                assertThat(response.getHeader(header))
                    .withFailMessage("%s 응답에 계약 헤더 %s 가 없다", format, header)
                    .isNotNull()
            }
            assertPrivateHeaders(response)
            assertFilenameStar(response)
            assertMediaType(response, exportFormat.mediaType)
            if (format == ExportFormat.TXT.extension) {
                assertThat(response.contentAsByteArray.take(BOM.size))
                    .withFailMessage("txt 가 BOM 으로 시작한다 — charset=utf-8 과 겹치면 한글이 이중으로 깨진다")
                    .isNotEqualTo(BOM)
            }
        }
    }

    @Test
    @DisplayName("format 을 **생략하면** 서버가 원본에서 정한다 — 누락은 더는 422 가 아니다")
    fun `형식 누락은 기본 경로다`() {
        val declared = ContractSpec.exportEnforcement()
        assertThat(declared.required)
            .describedAs("계약이 `format` 을 아직 필수로 선언한다 — 생략 경로가 성립하지 않는다")
            .isFalse()

        ExportFormat.entries.forEach { expected ->
            val owner = newOwner()
            val conversionId = completedConversion(owner, checkNotNull(sourceProducing(expected)))

            val response = export(owner, conversionId, format = null)

            assertThat(response.status)
                .withFailMessage(
                    "`format` 없는 요청이 %d 가 아니다: %s",
                    ContractSpec.successStatus(EXPORT_PATH, GET),
                    response.getContentAsString(),
                ).isEqualTo(ContractSpec.successStatus(EXPORT_PATH, GET))
            assertMediaType(response, expected.mediaType)
            assertThat(decodedFilename(assertFilenameStar(response)))
                .withFailMessage("생략 경로가 유도값 %s 이 아닌 확장자를 냈다", expected.extension)
                .endsWith(".${expected.extension}")
        }
    }

    @Test
    @DisplayName("원본이 정한 형식과 **다른** 값은 409 · 계약 예시 format_mismatch — 422 가 아니다")
    fun `원본과 다른 형식은 409 다`() {
        val declared = ContractSpec.exportEnforcement()
        assertThat(declared.onMismatch).isEqualTo(CONFLICT)

        ExportFormat.entries.forEach { derived ->
            val owner = newOwner()
            val conversionId = completedConversion(owner, checkNotNull(sourceProducing(derived)))

            ExportFormat.entries.filterNot { it == derived }.forEach { outsider ->
                val response = export(owner, conversionId, outsider.extension)

                assertDeclaredStatus(response, CONFLICT)
                assertThat(bodyOf(response)[DETAIL])
                    .withFailMessage("원본이 %s 인데 %s 요청이 통과했거나 문구가 다르다", derived.extension, outsider.extension)
                    .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, MISMATCH_EXAMPLE))
            }
        }
    }

    @Test
    @DisplayName("내보낼 형식이 없는 원본은 **어떤 값도·생략도** 409 · 계약 예시 no_exportable_format")
    fun `내보낼 수단이 없으면 409 다`() {
        val declared = ContractSpec.exportEnforcement()
        assertThat(declared.onNullMapping).isEqualTo(CONFLICT)
        val unexportable =
            SourceFormat.entries.filter { ExportFormat.ofSource(it) == null }
        assertThat(unexportable).describedAs("상이 `null` 인 원본이 없다 — 이 대조가 공허해진다").isNotEmpty()

        unexportable.forEach { source ->
            val owner = newOwner()
            val conversionId = completedConversion(owner, source)

            (ExportFormat.entries.map { it.extension } + null).forEach { requested ->
                val response = export(owner, conversionId, requested)

                assertDeclaredStatus(response, CONFLICT)
                assertThat(bodyOf(response)[DETAIL])
                    .withFailMessage("원본 %s · 요청 %s 의 처분이 계약과 다르다", source.wireName, requested)
                    .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, UNAVAILABLE_EXAMPLE))
            }
        }
    }

    @Test
    @DisplayName("**남의 변환**에서는 형식이 달라도 404 이고, 없는 변환과 **구분되지 않는다**")
    fun `소유 은닉이 형식 판정보다 먼저다`() {
        val theirs = completedConversion(newOwner(), SourceFormat.DOCX)
        val mine = newOwner()
        val mismatched = ExportFormat.TXT.extension

        val others = export(mine, theirs, mismatched)
        val absent = export(mine, UUID.randomUUID().toString(), mismatched)

        assertDeclaredStatus(others, NOT_FOUND)
        assertDeclaredStatus(absent, NOT_FOUND)
        assertThat(others.getContentAsString(StandardCharsets.UTF_8))
            .withFailMessage("409 가 나가거나 문구가 갈리면 404 로 가려 둔 사실이 형식 축으로 샌다")
            .isEqualTo(absent.getContentAsString(StandardCharsets.UTF_8))
    }

    @Test
    @DisplayName("빈 값(`?format=`)은 **생략이 아니라 계약 밖 값**이다 → 422")
    fun `빈 형식은 422 다`() {
        val owner = newOwner()
        val conversionId = completedConversion(owner)

        val response = export(owner, conversionId, format = "")

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response, QUERY_LOCATION, formatQueryName())
    }

    @Test
    @DisplayName("계약 enum 밖 형식 → 422 · detail 배열이 query/format 을 지목한다")
    fun `미지원 형식은 422 배열이다`() {
        val declared = ContractSpec.schemaEnum(FORMAT_SCHEMA).toSet()
        val outsider = listOf("pdf", "hwp", "DOCX", "__not_in_enum__").first { it !in declared }

        val response = export(newOwner(), UUID.randomUUID().toString(), outsider)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response, QUERY_LOCATION, formatQueryName())
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(outsider)
    }

    @Test
    @DisplayName("완료 전 변환 → 409 · detail 이 계약 예시 not_done 과 같다")
    fun `완료 전이면 409 다`() {
        val owner = newOwner()
        val conversionId = acceptDocument(owner).conversionId

        val response = export(owner, conversionId, format = null)

        assertDeclaredStatus(response, CONFLICT)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, NOT_DONE_EXAMPLE))
    }

    @Test
    @DisplayName("검수 없는 초안은 자리표시자를 복원하지 않는다 — 계약 복원 보류")
    fun `검수 전에는 원문을 넣지 않는다`() {
        val owner = newOwner()
        val conversionId = acceptDocument(owner).conversionId
        val item = maskedItem()
        complete(
            conversionId,
            easyText = "등록번호는 ${item.placeholder} 입니다.",
            masked = listOf(item),
        )

        val body =
            String(
                export(owner, conversionId, ExportFormat.TXT.extension).contentAsByteArray,
                StandardCharsets.UTF_8,
            )

        assertThat(body).contains(item.placeholder)
        assertThat(body).doesNotContain(ORIGINAL)
    }

    @Test
    @DisplayName("검수 없는 초안에서 자리표시자가 빠지면 409 · 계약 예시 missing_placeholders")
    fun `유실된 초안은 409 다`() {
        val owner = newOwner()
        val conversionId = acceptDocument(owner).conversionId
        complete(
            conversionId,
            easyText = "주민번호는 생략합니다",
            masked = listOf(maskedItem()),
        )

        val response = export(owner, conversionId, format = null)

        assertDeclaredStatus(response, CONFLICT)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, MISSING_EXAMPLE))
    }

    @Test
    @DisplayName("CE-5 제목의 C0·DEL·C1 이 filename* 디코딩 결과에 없다")
    fun `제어문자가 파일명에 없다`() {
        assertFilenameSanitized(forbiddenChars(CONTROL_RANGE))
    }

    @Test
    @DisplayName("CE-6 제목의 경로 구분자·윈도우 예약 문자가 filename* 디코딩 결과에 없다")
    fun `예약 문자가 파일명에 없다`() {
        assertFilenameSanitized(forbiddenChars(RESERVED_RANGE))
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다 — 형식 검사보다 인증이 먼저다")
    fun `토큰이 없으면 401 이다`() {
        val response =
            mockMvc
                .get(itemPath(UUID.randomUUID().toString())) {
                    param(formatQueryName(), firstFormat())
                }.andReturn()
                .response

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
    }

    private fun assertFilenameSanitized(forbidden: String) {
        val owner = newOwner()
        val accepted = acceptDocument(owner)
        documents.rewriteTitle(accepted.documentId, "가${forbidden}나")
        complete(accepted.conversionId, easyText = "본문")

        val decoded = decodedFilename(assertFilenameStar(export(owner, accepted.conversionId, format = null)))
        val pattern = ContractExportSpec.filenameForbidden()

        assertThat(pattern.containsMatchIn(decoded))
            .withFailMessage("filename* 디코딩 결과에 계약 forbidden 문자가 있다: %s", decoded)
            .isFalse()
        assertThat(decoded).contains("가").contains("나")
    }

    private fun forbiddenChars(codePoints: IntRange): String {
        val pattern = ContractExportSpec.filenameForbidden()
        val chars =
            codePoints.mapNotNull { code ->
                Character.toString(code).takeIf { pattern.containsMatchIn(it) }
            }
        assertThat(chars)
            .withFailMessage("계약 forbidden 이 %s 에서 한 글자도 안 잡힌다 — 오라클이 비었다", codePoints)
            .isNotEmpty()
        return chars.joinToString("")
    }

    private fun export(
        owner: UUID,
        conversionId: String,
        format: String?,
    ): MockHttpServletResponse =
        mockMvc
            .get(itemPath(conversionId)) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                if (format != null) param(formatQueryName(), format)
            }.andReturn()
            .response

    private fun itemPath(conversionId: String): String =
        EXPORT_PATH.replace("{${ContractSpec.pathVariable(EXPORT_PATH, GET).name}}", conversionId)

    private fun formatQueryName(): String = ContractSpec.queryParameters(EXPORT_PATH, GET).single().name

    private fun firstFormat(): String = ContractSpec.schemaEnum(FORMAT_SCHEMA).first()

    /**
     * 이 내보내기 형식을 내는 원본. 없으면 `null`.
     *
     * **유도표를 뒤집어 읽는다** — 어느 원본이 `docx` 를 내는지 손으로 적으면 표가 바뀐 날
     * 이 파일만 옛 대응을 알고 통과한다.
     */
    private fun sourceProducing(format: ExportFormat): SourceFormat? =
        SourceFormat.entries.firstOrNull { ExportFormat.ofSource(it) == format }

    private fun completedConversion(
        owner: UUID,
        source: SourceFormat = SourceFormat.TEXT,
    ): String {
        val conversionId = acceptDocument(owner, source).conversionId
        complete(conversionId, easyText = "쉬운 글 초안입니다.")
        return conversionId
    }

    /**
     * 그 원본 형식의 문서를 접수한다. 붙여넣기는 JSON, 파일은 multipart 다 —
     * **제품 경로 그대로** 지난다(형식은 `StubDocumentTextExtractor` 가 파일 이름에서 읽는다).
     */
    private fun acceptDocument(
        owner: UUID,
        source: SourceFormat = SourceFormat.TEXT,
    ): Accepted {
        val created =
            if (source == SourceFormat.TEXT) {
                mockMvc
                    .post(DOCUMENTS_PATH) {
                        header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                        contentType = MediaType.APPLICATION_JSON
                        content = json.writeValueAsString(mapOf(TEXT_PROPERTY to SAMPLE_TEXT))
                    }.andReturn()
                    .response
            } else {
                mockMvc
                    .multipart(DOCUMENTS_PATH) {
                        header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                        file(
                            MockMultipartFile(
                                FILE_PART,
                                "안내문.${source.wireName}",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                SAMPLE_TEXT.toByteArray(StandardCharsets.UTF_8),
                            ),
                        )
                    }.andReturn()
                    .response
            }
        check(created.status == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${created.status} ${created.getContentAsString(StandardCharsets.UTF_8)}"
        }
        val body = bodyOf(created)
        return Accepted(
            documentId = UUID.fromString(body.getValue(DOCUMENT_ID_PROPERTY).toString()),
            conversionId = body.getValue(CONVERSION_ID_PROPERTY).toString(),
        )
    }

    private fun complete(
        conversionId: String,
        easyText: String,
        masked: List<MaskedItemView> = emptyList(),
    ) {
        val id = UUID.fromString(conversionId)
        conversions.complete(
            conversionId = id,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = seal(easyText, id, EncryptedField.CONVERSION_EASY_TEXT),
                    maskedItems =
                        masked.takeIf { it.isNotEmpty() }?.let { items ->
                            seal(
                                StubMaskedItemReader.encodeForStub(items).value,
                                id,
                                EncryptedField.CONVERSION_MASKED_ITEMS,
                            )
                        },
                    editedText = null,
                ),
            missingPlaceholders = emptyList(),
            model = SAMPLE_MODEL,
            providerName = SAMPLE_PROVIDER,
            inputTokens = SAMPLE_TOKENS,
            outputTokens = SAMPLE_TOKENS,
        )
    }

    private fun seal(
        plain: String,
        record: UUID,
        field: EncryptedField,
    ) = cipher.encrypt(PlainBody(plain), record, field)

    private fun newOwner(): UUID {
        val id = users.create("export-${UUID.randomUUID()}@example.test", STUB_HASH).id
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
        assertThat(response.status)
            .withFailMessage("GET %s 가 %d 이 아니다: %s", EXPORT_PATH, status, response.getContentAsString())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(EXPORT_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", EXPORT_PATH, status)
            .contains(status.toString())
    }

    private fun assertValidationArray(
        response: MockHttpServletResponse,
        location: String,
        name: String,
    ) {
        val items = bodyOf(response)[DETAIL]
        assertThat(items)
            .withFailMessage("detail 이 배열이 아니다 — 스키마 층 거절은 배열이어야 한다: %s", items)
            .isInstanceOf(List::class.java)
        val declared = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items as List<*>).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet()).isEqualTo(declared)
        }
        assertThat(items.map { it as Map<*, *> }.map { it[LOC_KEY] })
            .withFailMessage("거절 항목이 %s/%s 를 지목하지 않는다: %s", location, name, items)
            .contains(listOf(location, name))
    }

    private fun assertFilenameStar(response: MockHttpServletResponse): String {
        val disposition =
            response.getHeader(HttpHeaders.CONTENT_DISPOSITION)
                ?: error("Content-Disposition 이 없다")
        assertThat(FILENAME_STAR.containsMatchIn(disposition))
            .withFailMessage("filename* 이 없다 — React parseFilename 이 한글 이름을 못 읽는다: %s", disposition)
            .isTrue()
        return disposition
    }

    private fun decodedFilename(disposition: String): String {
        val encoded =
            FILENAME_STAR.find(disposition)?.groupValues?.get(1)
                ?: error("filename* 이 없다: $disposition")
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8)
    }

    private fun assertMediaType(
        response: MockHttpServletResponse,
        expected: String,
    ) {
        val actual = MediaType.parseMediaType(checkNotNull(response.contentType))
        val want = MediaType.parseMediaType(expected)
        assertThat(actual.type).isEqualTo(want.type)
        assertThat(actual.subtype).isEqualTo(want.subtype)
        if (want.charset != null) {
            assertThat(actual.charset).isEqualTo(want.charset)
        }
    }

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    private data class Accepted(
        val documentId: UUID,
        val conversionId: String,
    )

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val DOCUMENTS_PATH = "/documents"
        const val EXPORT_PATH = "/conversions/{conversion_id}/export"
        const val GET = "get"
        const val POST = "post"

        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422

        /** 업로드 파트 이름. 계약 `POST /documents` 의 multipart 본문이 정한다. */
        const val FILE_PART = "file"

        const val FORMAT_SCHEMA = "ExportFormat"
        const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        const val TEXT_PROPERTY = "text"
        const val DOCUMENT_ID_PROPERTY = "document_id"
        const val CONVERSION_ID_PROPERTY = "conversion_id"
        const val DETAIL = "detail"
        const val LOC_KEY = "loc"
        const val QUERY_LOCATION = "query"

        const val NOT_DONE_EXAMPLE = "not_done"
        const val MISSING_EXAMPLE = "missing_placeholders"
        const val MISMATCH_EXAMPLE = "format_mismatch"
        const val UNAVAILABLE_EXAMPLE = "no_exportable_format"

        const val SAMPLE_TEXT = "내보내기 계약 검사용 안내문 본문"
        const val SAMPLE_MODEL = "stub-model"
        const val SAMPLE_PROVIDER = "stub-provider"
        const val SAMPLE_TOKENS = 1

        val BOM = listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val FILENAME_STAR = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)

        val CONTROL_RANGE = 0x00..0x9F
        val RESERVED_RANGE = 0x20..0x7E

        const val ORIGINAL: String = "900101-1234567"

        fun maskedItem(): MaskedItemView = MaskedItemView(MaskCategory.RRN, "[[주민등록번호1]]", Secret(ORIGINAL))
    }
}

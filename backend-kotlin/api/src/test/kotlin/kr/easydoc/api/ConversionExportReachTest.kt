package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.FALLBACK_TITLE
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.document.MaskedItemCodec
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.zip.ZipInputStream

/** `GET /conversions/{conversion_id}/export` 실측 — 소유 은닉·복원·409. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$CONVERSION_EXPORT_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionExportReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()
    private val extractors = DocumentExtractors()

    @Test
    @DisplayName("검수본의 자리표시자가 원문으로 복원되어 txt·docx·hwpx 본문에 실린다 — **각 형식의 원본에서**")
    fun `검수본은 형식마다 복원한다`() {
        ContractSpec.schemaEnum(FORMAT_SCHEMA).forEach { format ->
            val token = newAccount()
            val conversionId = documentProducing(token, format)
            markDone(
                conversionId,
                DoneResult(
                    easyText = "버려질 초안 $PLACEHOLDER",
                    editedText = "검수본 $PLACEHOLDER 입니다.",
                    maskedItems = listOf(hiddenItem()),
                    reviewed = true,
                ),
            )

            val response = exportBytes(token, conversionId, format)
            assertDeclaredStatus(response.statusCode(), ContractSpec.successStatus(EXPORT_PATH, GET))
            val body = exportedText(response.body(), format)
            assertThat(body)
                .withFailMessage("%s 본문에 원문이 없다 — 복원이 빠졌다", format)
                .contains(ORIGINAL)
            assertThat(body)
                .withFailMessage("%s 본문에 자리표시자가 남았다", format)
                .doesNotContain(PLACEHOLDER)
        }
    }

    @Test
    @DisplayName("`format` 을 **생략하면** 원본이 정한 형식이 나간다 — 직접 URL 로 열어도 동작한다")
    fun `형식을 생략하면 서버가 정한다`() {
        ContractSpec.schemaEnum(FORMAT_SCHEMA).forEach { format ->
            val token = newAccount()
            val conversionId = documentProducing(token, format)
            markDone(conversionId, DoneResult(easyText = "쉬운 글 초안입니다."))

            val response = exportBytes(token, conversionId, format = null)

            assertDeclaredStatus(response.statusCode(), ContractSpec.successStatus(EXPORT_PATH, GET))
            assertThat(response.headers().firstValue(CONTENT_DISPOSITION))
                .withFailMessage("생략 경로가 %s 확장자를 내지 않았다: %s", format, response.headers())
                .hasValueSatisfying { assertThat(it).contains(".$format") }
            assertThat(exportedText(response.body(), format)).contains("쉬운 글 초안입니다.")
        }
    }

    @Test
    @DisplayName("원본과 **다른** 형식은 409 · 계약 예시 format_mismatch — 실제 업로드를 지난다")
    fun `원본과 다른 형식은 409 다`() {
        val formats = ContractSpec.schemaEnum(FORMAT_SCHEMA)

        formats.forEach { derived ->
            val token = newAccount()
            val conversionId = documentProducing(token, derived)
            markDone(conversionId, DoneResult(easyText = "쉬운 글 초안입니다."))

            formats.filterNot { it == derived }.forEach { outsider ->
                val response = exportText(token, conversionId, outsider)

                assertDeclaredStatus(response.statusCode(), CONFLICT)
                assertThat(jsonBody(response)[DETAIL])
                    .withFailMessage("원본이 %s 인데 %s 요청의 처분이 계약과 다르다", derived, outsider)
                    .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, MISMATCH_EXAMPLE))
            }
        }
    }

    @Test
    @DisplayName("PDF 원본은 **어떤 값도·생략도** 409 · 계약 예시 no_exportable_format")
    fun `PDF 원본은 내보낼 수 없다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.pdf", UploadFixtures.samplePdf())
        markDone(conversionId, DoneResult(easyText = "쉬운 글 초안입니다."))

        (ContractSpec.schemaEnum(FORMAT_SCHEMA) + null).forEach { requested ->
            val response = exportText(token, conversionId, requested)

            assertDeclaredStatus(response.statusCode(), CONFLICT)
            assertThat(jsonBody(response)[DETAIL])
                .withFailMessage("PDF 원본 · 요청 %s 의 처분이 계약과 다르다", requested)
                .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, UNAVAILABLE_EXAMPLE))
        }
    }

    @Test
    @DisplayName("검수 전 초안은 자리표시자를 복원하지 않는다 — 위조 주입을 막는다")
    fun `검수 전에는 원문을 넣지 않는다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        markDone(
            conversionId,
            DoneResult(
                easyText = "등록번호는 $PLACEHOLDER 입니다.",
                maskedItems = listOf(hiddenItem()),
            ),
        )

        val body = exportedText(exportBytes(token, conversionId, TXT).body(), TXT)
        assertThat(body).contains(PLACEHOLDER)
        assertThat(body).doesNotContain(ORIGINAL)
    }

    @Test
    @DisplayName("완료 전·실패 변환은 409 이고 계약 예시 not_done 과 같다 — 404 가 아니다")
    fun `완료 전이면 409 다`() {
        val token = newAccount()
        val pending = createDocument(token).second
        val failed = createDocument(token).second
        forceStatus(failed, FAILED_STATUS)

        listOf(pending, failed).forEach { conversionId ->
            val response = exportText(token, conversionId, format = null)
            assertDeclaredStatus(response.statusCode(), CONFLICT)
            assertThat(jsonBody(response)[DETAIL])
                .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, NOT_DONE_EXAMPLE))
        }
    }

    @Test
    @DisplayName("검수 없는 초안에서 자리표시자가 빠지면 409 이다")
    fun `유실된 초안은 409 다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        markDone(
            conversionId,
            DoneResult(
                easyText = "주민번호는 생략합니다",
                maskedItems = listOf(hiddenItem()),
            ),
        )

        val response = exportText(token, conversionId, format = null)
        assertDeclaredStatus(response.statusCode(), CONFLICT)
        assertThat(jsonBody(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, MISSING_EXAMPLE))
    }

    @Test
    @DisplayName("없는 식별자와 타인 식별자의 상태·본문 바이트·헤더 이름이 같다 — **원본과 다른 형식으로도**")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        // 붙여넣기 문서라 `txt` 만 그 원본이 정한 형식이다 — 나머지 둘은 **불일치**이고,
        // 그래도 409 가 아니라 404 여야 한다. 그것이 불일치를 409 에 둔 둘째 근거의 실측이다.
        val theirs = createDocument(newAccount()).second

        ContractSpec.schemaEnum(FORMAT_SCHEMA).forEach { format ->
            val absent = exportBytes(mine, UUID.randomUUID(), format)
            val others = exportBytes(mine, theirs, format)

            assertThat(absent.statusCode())
                .withFailMessage("%s 없는 변환이 404 가 아니다: %d", format, absent.statusCode())
                .isEqualTo(NOT_FOUND)
            assertThat(others.statusCode())
                .withFailMessage("%s 타인 변환이 404 가 아니다: %d", format, others.statusCode())
                .isEqualTo(NOT_FOUND)
            OwnershipConcealment.assertIndistinguishable(
                "GET $EXPORT_PATH?format=$format",
                absent,
                others,
            )
        }
    }

    /**
     * `sample_rich.docx` 의 본문 단위 일곱과 머리글·바닥글 둘. **오라클이 아니라 이 fixture 의
     * 사실**이고, 갈리면 `DocxOriginalReflectorTest` 가 먼저 빨개진다.
     */
    private val richBodyLines = List(RICH_BODY_UNITS) { "쉬운 문단 ${it + 1}." }

    @Test
    @DisplayName("**업로드한 원본 구조가 내려받은 파일에 그대로 남는다** — 실 HTTP 왕복으로 잰다")
    fun `원본 구조가 내려받은 파일에 남는다`() {
        val original = UploadFixtures.bytes(RICH_DOCX)
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", original)
        markDone(conversionId, DoneResult(easyText = richBodyLines.joinToString("\n")))

        val response = exportBytes(token, conversionId, format = null)

        assertDeclaredStatus(response.statusCode(), ContractSpec.successStatus(EXPORT_PATH, GET))
        val before = zipEntriesOf(original)
        val after = zipEntriesOf(response.body())
        assertThat(after.keys)
            .describedAs("새 문서를 만들면 원본에만 있던 항목이 통째로 사라진다 — 그것이 §6.5 가 금지한 일이다")
            .containsAll(before.keys)
        assertThat(partOf(after, THUMBNAIL_PART))
            .describedAs("본문과 무관한 파트는 바이트까지 그대로다")
            .isEqualTo(partOf(before, THUMBNAIL_PART))
        assertThat(partOf(after, HEADER_PART).decodeToString())
            .describedAs("머리글은 검수본을 쓰지 않고 원본 문구를 그대로 둔다")
            .contains(HEADER_TEXT)
        val document = partOf(after, DOCUMENT_PART).decodeToString()
        assertThat(occurrencesOf(document, TABLE_TAG))
            .describedAs("표 둘(중첩 포함)이 그대로다 — 셀 안의 문단에 글자만 갈아 끼운다")
            .isEqualTo(RICH_TABLE_COUNT)
        assertThat(document)
            .describedAs("텍스트 상자(AlternateContent)와 변경 추적 삭제도 손대지 않는다")
            .contains(ALTERNATE_CONTENT_TAG)
            .contains(DELETED_TEXT_TAG)
        assertThat(exportedText(response.body(), SourceFormat.DOCX.wireName))
            .describedAs("본문은 검수본이고 머리글·바닥글만 원본 문구다")
            .isEqualTo((richBodyLines + listOf(HEADER_TEXT, FOOTER_TEXT)).joinToString("\n"))
        assertThat(decodedFilename(response))
            .describedAs(
                "원본과 **같은 형식**으로 나가므로 표식이 없으면 폴더에서 둘을 가릴 수 없다 (§6.5). " +
                    "줄기가 올린 파일 이름이 아니라 %s 인 것은 계약 `x-title-policy` 가 파일 이름 유도를 " +
                    "폐기했기 때문이다 — 업로드는 제목을 주지 않는다",
                FALLBACK_TITLE,
            ).isEqualTo("$FALLBACK_TITLE$EASY_READ_SUFFIX.docx")
    }

    @Test
    @DisplayName("**저장된 원본을 열 수 없으면 500 · 계약 예시 storage_original** — 텍스트로 조용히 대체하지 않는다")
    fun `열 수 없는 원본은 500 이다`() {
        val token = newAccount()
        val conversionId = uploadDocument(token, "안내문.docx", UploadFixtures.bytes(RICH_DOCX))
        markDone(conversionId, DoneResult(easyText = richBodyLines.joinToString("\n")))
        breakStoredOriginal(conversionId)

        val response = exportText(token, conversionId, format = null)

        assertDeclaredStatus(response.statusCode(), INTERNAL_ERROR)
        assertThat(jsonBody(response)[DETAIL])
            .describedAs("이 갈래의 문구는 계약 `InternalError` 의 %s 예시가 정본이다", STORAGE_ORIGINAL_EXAMPLE)
            .isEqualTo(ContractSpec.responseExampleDetail(INTERNAL_ERROR_COMPONENT, STORAGE_ORIGINAL_EXAMPLE))
        assertThat(response.headers().firstValue(CONTENT_DISPOSITION))
            .describedAs("파일 헤더가 붙으면 무언가를 내려보냈다는 뜻이다 — 그 무언가가 텍스트 대체본이다")
            .isEmpty()
    }

    /**
     * 저장된 원본을 **열리지 않는 바이트로** 갈아 끼운다. 암호문 자체는 멀쩡하게 만든다
     * (같은 결속·같은 세대) — 복호화가 실패하는 경로가 아니라 **연 바이트가 문서가 아닌**
     * 경로를 재려는 것이다.
     */
    private fun breakStoredOriginal(conversionId: UUID) {
        val documentId =
            UUID.fromString(
                database
                    .queryFirstColumn("SELECT document_id FROM conversions WHERE id = '$conversionId'")
                    .single(),
            )
        database.execute(
            BREAK_ORIGINAL_SQL.format(
                sealed("zip 이 아니다", documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES),
                cipher.writeScheme,
                cipher.writeKeyVersion,
                documentId,
            ),
        )
    }

    /** `Content-Disposition` 의 `filename*` 을 퍼센트 디코딩한 값 — 사용자 파일 시스템에 닿는 이름이다. */
    private fun decodedFilename(response: HttpResponse<ByteArray>): String {
        val disposition =
            response.headers().firstValue(CONTENT_DISPOSITION).orElseThrow {
                error("$CONTENT_DISPOSITION 가 없다")
            }
        val encoded =
            FILENAME_STAR.find(disposition)?.groupValues?.get(1)
                ?: error("filename* 이 없다: $disposition")
        return URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8)
    }

    private fun zipEntriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    /**
     * 아카이브 항목 하나의 바이트.
     *
     * `Map.getValue` 를 쓰지 않는 것은 이 클래스가 JSON 본문을 읽으려고 `Map<*, *>.getValue`
     * 를 따로 두고 있어, 그쪽이 먼저 잡혀 값이 `Any` 로 내려오기 때문이다.
     */
    private fun partOf(
        entries: Map<String, ByteArray>,
        name: String,
    ): ByteArray = requireNotNull(entries[name]) { "아카이브에 $name 이 없다" }

    private fun occurrencesOf(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    fun `토큰이 없으면 401 이다`() {
        val response = exportText(token = null, conversionId = UUID.randomUUID(), format = firstFormat())

        assertDeclaredStatus(response.statusCode(), UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
        assertThat(jsonBody(response)[DETAIL]).isInstanceOf(String::class.java)
    }

    private fun markDone(
        conversionId: UUID,
        result: DoneResult,
    ) {
        val easy = sealed(result.easyText, conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val edited = sealed(result.editedText, conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
        val table = result.maskedItems.takeIf { it.isNotEmpty() }?.let { codec.encode(it).value }
        val masked = sealed(table, conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
        val labels = json.writeValueAsString(emptyList<String>())

        database.execute(
            MARK_DONE_SQL.format(
                easy,
                edited,
                masked,
                cipher.writeScheme,
                cipher.writeKeyVersion,
                labels,
                if (result.reviewed) "now()" else "NULL",
                conversionId,
            ),
        )
    }

    private fun forceStatus(
        conversionId: UUID,
        status: String,
    ) {
        database.execute("UPDATE conversions SET status = '$status' WHERE id = '$conversionId'")
    }

    private fun sealed(
        plain: String?,
        record: UUID,
        field: EncryptedField,
    ): String {
        if (plain == null) return "NULL"
        val bytes = cipher.encrypt(PlainBody(plain), record, field).bytes
        return "decode('${bytes.joinToString("") { "%02x".format(it) }}', 'hex')"
    }

    private fun newAccount(): String {
        val email = "conversionexport${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        return jsonBody(send(post(null, credentials, "/auth/login"))).getValue("access_token").toString()
    }

    private fun createDocument(token: String): Pair<UUID, UUID> {
        val response =
            send(post(token, json.writeValueAsString(mapOf("text" to SAMPLE_TEXT)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val body = jsonBody(response)
        return UUID.fromString(body.getValue("document_id").toString()) to
            UUID.fromString(body.getValue("conversion_id").toString())
    }

    /**
     * 그 내보내기 형식을 내는 원본으로 문서 하나를 만든다 — **실제 업로드를 지난다.**
     *
     * 어느 원본이 어느 형식을 내는지는 계약 유도표를 뒤집어 읽는다. 손으로 적으면 표가
     * 바뀐 날 이 파일만 옛 대응을 알고 통과한다.
     */
    private fun documentProducing(
        token: String,
        format: String,
    ): UUID {
        val source =
            ContractSpec
                .exportFormatDerivation()
                .entries
                .firstOrNull { it.value == format }
                ?.key
                ?: error("계약 유도표에 $format 을 내는 원본이 없다")
        val uploads =
            mapOf(
                SourceFormat.DOCX.wireName to UploadFixtures::sampleDocx,
                SourceFormat.HWPX.wireName to UploadFixtures::sampleHwpx,
                SourceFormat.PDF.wireName to UploadFixtures::samplePdf,
            )
        return if (source == SourceFormat.TEXT.wireName) {
            createDocument(token).second
        } else {
            uploadDocument(token, "안내문.$source", (uploads[source] ?: error("$source 픽스처가 없다"))())
        }
    }

    /** 파일을 올려 그 문서의 변환 식별자를 준다. **행은 제품이 쓴다.** */
    private fun uploadDocument(
        token: String,
        filename: String,
        content: ByteArray,
    ): UUID {
        val body = MultipartBody().file(FILE_PART, filename, content)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                .header(CONTENT_TYPE, body.contentType())
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
        val response = send(request)
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "업로드가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        return UUID.fromString(jsonBody(response).getValue("conversion_id").toString())
    }

    private fun exportBytes(
        token: String?,
        conversionId: UUID,
        format: String?,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            getRequest(token, exportPath(conversionId, format)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun exportText(
        token: String?,
        conversionId: UUID,
        format: String?,
    ): HttpResponse<String> = send(getRequest(token, exportPath(conversionId, format)))

    /** [format] 이 `null` 이면 쿼리를 아예 붙이지 않는다 — **생략이 기본 경로다.** */
    private fun exportPath(
        conversionId: UUID,
        format: String?,
    ): String {
        val path =
            EXPORT_PATH.replace("{${ContractSpec.pathVariable(EXPORT_PATH, GET).name}}", conversionId.toString())
        return if (format == null) path else "$path?${formatQueryName()}=$format"
    }

    private fun formatQueryName(): String = ContractSpec.queryParameters(EXPORT_PATH, GET).single().name

    private fun firstFormat(): String = ContractSpec.schemaEnum(FORMAT_SCHEMA).first()

    private fun exportedText(
        bytes: ByteArray,
        format: String,
    ): String =
        if (format == TXT) {
            String(bytes, Charsets.UTF_8)
        } else {
            extractors.extract("export.$format", bytes).text
        }

    private fun getRequest(
        token: String?,
        path: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun post(
        token: String?,
        body: String,
        path: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $token") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun assertDeclaredStatus(
        actual: Int,
        status: Int,
    ) {
        assertThat(actual).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(EXPORT_PATH, GET)).contains(status.toString())
    }

    private fun jsonBody(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    private data class DoneResult(
        val easyText: String? = "쉬운 글 초안입니다.",
        val editedText: String? = null,
        val maskedItems: List<MaskedItem> = emptyList(),
        val reviewed: Boolean = false,
    )

    companion object {
        private val codec = MaskedItemCodec()

        private const val DOCUMENTS_PATH = "/documents"
        private const val EXPORT_PATH = "/conversions/{conversion_id}/export"
        private const val GET = "get"
        private const val POST = "post"

        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val INTERNAL_ERROR = 500

        private const val FORMAT_SCHEMA = "ExportFormat"
        private const val DETAIL = "detail"
        private const val FAILED_STATUS = "failed"
        private const val TXT = "txt"

        private const val NOT_DONE_EXAMPLE = "not_done"
        private const val MISSING_EXAMPLE = "missing_placeholders"
        private const val MISMATCH_EXAMPLE = "format_mismatch"
        private const val UNAVAILABLE_EXAMPLE = "no_exportable_format"

        /** 500 문구를 읽을 좌표. 이름이지 값이 아니다. */
        private const val INTERNAL_ERROR_COMPONENT = "InternalError"
        private const val STORAGE_ORIGINAL_EXAMPLE = "storage_original"

        /**
         * 원본 구조 보존을 재는 fixture. 본문 단위 일곱 · 표 둘(중첩) · 텍스트 상자 ·
         * 변경 추적 삭제 · 머리글 · 바닥글 · 썸네일 파트를 한 파일에 담고 있다.
         */
        private const val RICH_DOCX = "sample_rich.docx"
        private const val RICH_BODY_UNITS = 7
        private const val RICH_TABLE_COUNT = 2

        private const val DOCUMENT_PART = "word/document.xml"
        private const val HEADER_PART = "word/header1.xml"
        private const val THUMBNAIL_PART = "docProps/thumbnail.jpeg"
        private const val TABLE_TAG = "<w:tbl>"
        private const val ALTERNATE_CONTENT_TAG = "mc:AlternateContent"
        private const val DELETED_TEXT_TAG = "w:delText"
        private const val HEADER_TEXT = "머리글 문구"
        private const val FOOTER_TEXT = "바닥글 문구"

        /** `DESIGN.md` §6.5 가 원본 파일과 구분하라고 정한 표식. 구현은 `core/easyread/Export.kt`. */
        private const val EASY_READ_SUFFIX = "-쉬운글"

        private val FILENAME_STAR = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)

        /**
         * 저장된 원본을 열리지 않는 바이트로 갈아 끼우는 UPDATE. **봉투 두 값을 같은 문장에서
         * 함께 SET 한다** — `EnvelopeColumnWriteGuardTest` 의 규약이고, 그 사유는 행당 키 세대가
         * 하나라 암호문만 바꾸면 그 행이 영원히 열리지 않기 때문이다(AAD 에 세대가 실린다).
         */
        val BREAK_ORIGINAL_SQL =
            """
            UPDATE document_originals
            SET file_bytes_encrypted = %s,
                encryption_scheme = '%s',
                key_version = %s
            WHERE document_id = '%s'
            """.trimIndent()

        /** 업로드 파트 이름. 계약 `POST /documents` 의 multipart 본문이 정한다. */
        private const val FILE_PART = "file"
        private const val CONTENT_DISPOSITION = "Content-Disposition"

        private const val PLACEHOLDER = "[[주민등록번호1]]"
        private const val ORIGINAL = "900101-1234567"
        private const val SAMPLE_TEXT = "내보내기 실측용 안내문 본문"
        private const val VALID_PASSWORD = "correct horse battery"

        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        val MARK_DONE_SQL =
            """
            UPDATE conversions
            SET status = 'done',
                easy_text_encrypted = %s,
                edited_text_encrypted = %s,
                masked_items_encrypted = %s,
                encryption_scheme = '%s',
                key_version = %s,
                missing_placeholders = '%s'::jsonb,
                reviewed_at = %s,
                model = 'export-reach-model',
                provider_name = 'export-reach-provider',
                input_tokens = 11,
                output_tokens = 22
            WHERE id = '%s'
            """.trimIndent()

        private var counter = 0

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_export") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        fun hiddenItem(): MaskedItem = MaskedItem(MaskCategory.RRN, PLACEHOLDER, Secret(ORIGINAL))
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val CONVERSION_EXPORT_TEST_SECRET: String = "conversion-export-test-signing-key-0123456789"

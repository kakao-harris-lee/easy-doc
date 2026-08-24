package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

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
    @DisplayName("검수본의 자리표시자가 원문으로 복원되어 txt·docx·hwpx 본문에 실린다")
    fun `검수본은 형식마다 복원한다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        markDone(
            conversionId,
            DoneResult(
                easyText = "버려질 초안 $PLACEHOLDER",
                editedText = "검수본 $PLACEHOLDER 입니다.",
                maskedItems = listOf(hiddenItem()),
                reviewed = true,
            ),
        )

        ContractSpec.schemaEnum(FORMAT_SCHEMA).forEach { format ->
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
            val response = exportText(token, conversionId, firstFormat())
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

        val response = exportText(token, conversionId, firstFormat())
        assertDeclaredStatus(response.statusCode(), CONFLICT)
        assertThat(jsonBody(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(EXPORT_PATH, GET, CONFLICT, MISSING_EXAMPLE))
    }

    @Test
    @DisplayName("없는 식별자와 타인 식별자의 상태·본문 바이트·헤더 이름이 같다")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirs = createDocument(newAccount()).second
        val format = firstFormat()

        val absent = exportBytes(mine, UUID.randomUUID(), format)
        val others = exportBytes(mine, theirs, format)

        assertThat(absent.statusCode()).isEqualTo(NOT_FOUND)
        OwnershipConcealment.assertIndistinguishable("GET $EXPORT_PATH", absent, others)
    }

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

    private fun exportBytes(
        token: String?,
        conversionId: UUID,
        format: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            getRequest(token, exportPath(conversionId, format)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun exportText(
        token: String?,
        conversionId: UUID,
        format: String,
    ): HttpResponse<String> = send(getRequest(token, exportPath(conversionId, format)))

    private fun exportPath(
        conversionId: UUID,
        format: String,
    ): String {
        val path =
            EXPORT_PATH.replace("{${ContractSpec.pathVariable(EXPORT_PATH, GET).name}}", conversionId.toString())
        return "$path?${formatQueryName()}=$format"
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

        private const val FORMAT_SCHEMA = "ExportFormat"
        private const val DETAIL = "detail"
        private const val FAILED_STATUS = "failed"
        private const val TXT = "txt"

        private const val NOT_DONE_EXAMPLE = "not_done"
        private const val MISSING_EXAMPLE = "missing_placeholders"

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

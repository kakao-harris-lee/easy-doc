package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.servlet.autoconfigure.MultipartProperties
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

/** `POST /documents` 의 실측 계약 — 명세 §5 의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass")
class DocumentEndpointReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var multipart: MultipartProperties

    private val json = ObjectMapper()

    @Test
    @DisplayName("컨테이너 multipart 상한이 계약 업로드 상한 **이상**이다 — 경계 판정을 서비스가 지게 하는 전제")
    fun `컨테이너 상한이 계약 상한보다 넉넉하다`() {
        val contractLimit = ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY).toLong()

        assertThat(multipart.maxFileSize.toBytes())
            .withFailMessage("max-file-size 가 계약 상한보다 작다 — 경계 판정이 컨테이너로 넘어간다")
            .isGreaterThan(contractLimit)
        assertThat(multipart.maxRequestSize.toBytes())
            .withFailMessage("max-request-size 가 max-file-size 이하다 — 파트 헤더 오버헤드만큼 경계가 어긋난다")
            .isGreaterThan(multipart.maxFileSize.toBytes())
    }

    @Test
    @DisplayName("DC-4 파일 업로드 성공 — JSON 경로와 **같은** 성공 상태·같은 키 집합 (X-G1)")
    fun `파일 업로드가 붙여넣기와 같은 응답을 낸다`() {
        val token = newAccount()

        val response = upload(token, MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.sampleDocx()))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(CREATED_SCHEMA))
        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("DC-5 Content-Type 의 대소문자를 뒤섞어도 성공한다 — 가리면 JSON 경로로 새어 422 가 된다 (X-G1)")
    fun `미디어 타입 비교가 대소문자를 가리지 않는다`() {
        val token = newAccount()
        val body = MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.sampleDocx())

        val response = send(post(token, body.contentTypeWith(MIXED_CASE_MULTIPART), body.build()))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
    }

    @Test
    @DisplayName("DC-6 파트 이름이 틀리거나 파일이 아닌 값이면 422 · detail **문자열** (X-G2)")
    fun `파일 파트가 없으면 422 문자열이다`() {
        val token = newAccount()

        val wrongName = upload(token, MultipartBody().file("upload", "안내문.docx", UploadFixtures.sampleDocx()))
        val notAFile = upload(token, MultipartBody().value(FILE_PART, "본문 문자열"))

        listOf(wrongName, notAFile).forEach { response ->
            assertDeclaredStatus(response, UNPROCESSABLE)

            assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
        }
    }

    @Test
    @DisplayName("DC-7 작업 공간 파트가 빈 문자열이면 미지정과 같고, 형식 오류면 422 이며 **제출값이 detail 에 없다** (X-G3)")
    fun `작업 공간 파트의 두 갈래`() {
        val token = newAccount()

        val empty =
            upload(
                token,
                MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.sampleDocx()).value(WORKSPACE_ID_PART, ""),
            )
        assertThat(empty.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))

        val malformed =
            upload(
                token,
                MultipartBody()
                    .file(FILE_PART, "안내문.docx", UploadFixtures.sampleDocx())
                    .value(WORKSPACE_ID_PART, SUBMITTED_BAD_WORKSPACE),
            )
        assertDeclaredStatus(malformed, UNPROCESSABLE)
        assertThat(bodyOf(malformed)[DETAIL]).isInstanceOf(String::class.java)

        assertThat(malformed.body())
            .withFailMessage("제출한 작업 공간 식별자가 응답에 그대로 실렸다")
            .doesNotContain(SUBMITTED_BAD_WORKSPACE)
    }

    @Test
    @DisplayName("DC-12 업로드 바이트가 상한 초과 → **413**(422 아님) · detail 문자열 · 값이 계약 예시와 같다 · 사적 헤더 2종 (X-F3)")
    fun `상한을 넘는 파일은 413 이다`() {
        val token = newAccount()
        val oversized = ByteArray(ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY) + 1) { OVERSIZED_FILLER }

        val response = upload(token, MultipartBody().file(FILE_PART, "안내문.docx", oversized))

        assertDeclaredStatus(response, PAYLOAD_TOO_LARGE)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.responseExampleDetail(PAYLOAD_TOO_LARGE_COMPONENT, TOO_LARGE_EXAMPLE))

        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("DC-13 업로드 바이트가 **정확히** 상한이면 통과한다 (413 아님) (X-F3)")
    fun `정확히 상한인 파일은 통과한다`() {
        val token = newAccount()
        val exact = UploadFixtures.docxOfExactSize(ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY))

        assertThat(exact.size).isEqualTo(ContractSpec.inputLimit(MAX_UPLOAD_BYTES_KEY))

        val response = upload(token, MultipartBody().file(FILE_PART, "안내문.docx", exact))

        assertThat(response.statusCode())
            .withFailMessage("정확히 상한인 파일이 %d 로 거절됐다 — 경계 한쪽만 걸면 off-by-one 이 남는다", response.statusCode())
            .isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
    }

    @Test
    @DisplayName("DC-14 계약이 든 지원 형식 **각각**을, 대소문자를 섞은 확장자로 보내도 전부 통과한다 (X-F4)")
    fun `지원 형식 전부가 대소문자와 무관하게 통과한다`() {
        val declared = ContractSpec.strings(INPUT_LIMITS, SUPPORTED_FORMATS_KEY)

        assertThat(declared).isNotEmpty()
        declared.forEach { format ->
            val token = newAccount()
            val response =
                upload(token, MultipartBody().file(FILE_PART, "안내문.${mixedCase(format)}", sampleFor(format)))

            assertThat(response.statusCode())
                .withFailMessage("계약이 지원한다고 선언한 형식 %s 가 %d 로 거절됐다", format, response.statusCode())
                .isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        }
    }

    @Test
    @DisplayName("DC-15 집합 밖 확장자·구버전 컨테이너·압축 폭탄·외부 엔터티 선언이 전부 422 문자열이다 (X-F5~F8)")
    fun `거절해야 하는 파일들이 422 다`() {
        val token = newAccount()
        val budget = ContractSpec.inputLimit(ZIP_BUDGET_KEY)

        val cases =
            mapOf(
                "집합 밖 확장자" to MultipartBody().file(FILE_PART, "안내문.txt", "본문".toByteArray(Charsets.UTF_8)),
                "구버전 워드 컨테이너" to MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.legacyWordContainer()),
                "압축 해제량 초과" to MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.zipOverBudget(budget + 1)),
                "외부 엔터티 선언" to MultipartBody().file(FILE_PART, "안내문.hwpx", UploadFixtures.hwpxWithDoctype()),
            )

        cases.forEach { (label, body) ->
            val response = upload(token, body)
            assertThat(response.statusCode())
                .withFailMessage("%s 가 %d 로 나갔다 — 계약은 422 다", label, response.statusCode())
                .isEqualTo(UNPROCESSABLE)
            assertThat(bodyOf(response)[DETAIL])
                .withFailMessage("%s 의 detail 이 문자열이 아니다", label)
                .isInstanceOf(String::class.java)
        }
    }

    @Test
    @DisplayName("DC-15 구버전 워드 컨테이너는 **전용 안내 문구**로 거절한다 — 안내가 같으면 없는 암호를 찾아 헤맨다")
    fun `구버전 컨테이너에 전용 문구가 나간다`() {
        val token = newAccount()

        val response = upload(token, MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.legacyWordContainer()))

        assertThat(ContractSpec.text(INPUT_LIMITS, LEGACY_DOC_POLICY_KEY))
            .withFailMessage("계약의 구버전 doc 조항과 나간 문구가 갈렸다")
            .contains(bodyOf(response)[DETAIL].toString())
    }

    @Test
    @DisplayName("DC-16 남의 작업 공간 식별자 → **404**(403 아님) · detail 이 계약 404 예시와 같다 (X-B1)")
    fun `남의 작업 공간은 404 다`() {
        val mine = newAccount()
        val othersWorkspace = defaultWorkspaceId(newAccount())

        val response = createFromText(mine, textBody("본문", workspaceId = othersWorkspace))

        assertDeclaredStatus(response, NOT_FOUND)

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(DOCUMENTS_PATH, POST, NOT_FOUND, WORKSPACE_NOT_FOUND_EXAMPLE))
    }

    /** 성질 P1 — 응답 구별 불가. 판정은 [OwnershipConcealment] 한 벌이 진다. */
    @Test
    @DisplayName("DC-17 없는 작업 공간과 남의 작업 공간의 상태·본문 **원시 바이트**·헤더 이름 집합이 **완전히 같다** (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val absent = createFromTextBytes(mine, textBody("본문", workspaceId = UUID.randomUUID().toString()))
        val others = createFromTextBytes(mine, textBody("본문", workspaceId = defaultWorkspaceId(newAccount())))

        OwnershipConcealment.assertIndistinguishable("POST $DOCUMENTS_PATH", absent, others)
    }

    @Test
    @DisplayName("DC-20 토큰 없이 업로드 → 401 · WWW-Authenticate · 본문 키가 정확히 ErrorResponse.required (X-A1 · X-C8)")
    fun `토큰 없는 업로드는 401 이다`() {
        val response = createFromText(token = null, body = textBody("본문"))

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .contains(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(ERROR_SCHEMA))
    }

    @Test
    @DisplayName("DC-21 위조 토큰 + 빈 본문 → **401**(422 가 아니다) — 인증이 입력 검증보다 먼저다 (X-A3)")
    fun `인증이 입력 검증보다 먼저다`() {
        val response = send(post(FORGED_TOKEN, JSON_MEDIA_TYPE, "{}".toByteArray(Charsets.UTF_8)))

        assertDeclaredStatus(response, UNAUTHORIZED)
    }

    @Test
    @DisplayName("DC-24 짝 없는 서로게이트 본문(JSON 이스케이프) → 422 · detail 문자열 · 계약 값과 같음 · **저장되지 않음** (X-K1)")
    fun `저장할 수 없는 문자가 든 본문은 거절되고 남지 않는다`() {
        val token = newAccount()
        val domain = ContractSpec.storedTextDomain()
        val before = documentCount(token)

        val response = send(post(token, JSON_MEDIA_TYPE, surrogateBodyBytes()))

        assertThat(response.statusCode()).isEqualTo(domain.status)
        assertThat(ContractSpec.observedDetailType(bodyOf(response)[DETAIL])).isEqualTo(domain.detailShape)
        assertThat(bodyOf(response)[DETAIL]).isEqualTo(domain.detail)

        assertThat(documentCount(token))
            .withFailMessage("거절된 업로드가 저장됐다 — 422 를 내기 전에 커밋된 경로가 있다")
            .isEqualTo(before)

        assertThat(listedDocumentIds(token))
            .withFailMessage("거절된 업로드가 목록에 보인다 — DB 행은 없는데 응답이 만들어졌다")
            .isEmpty()
    }

    @Test
    @DisplayName("DC-25 제목에 든 짝 없는 서로게이트는 **정제**한다 — 접수되고(422 아님) 저장된 제목에 그 문자가 없다 (X-K1)")
    fun `제목의 서로게이트는 걷어내고 접수한다`() {
        val token = newAccount()

        val response = send(post(token, JSON_MEDIA_TYPE, surrogateTitleBodyBytes()))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        val stored = storedTitles(token)
        assertThat(stored).hasSize(1)
        assertThat(stored.single()).doesNotContain(LONE_SURROGATE)
        assertThat(stored.single()).isEqualTo(TITLE_PREFIX + TITLE_SUFFIX)
    }

    @Test
    @DisplayName("DC-25 정제 후 남는 것이 없으면 계약의 고정 문구가 제목이 된다")
    fun `정제 후 빈 제목은 고정 문구다`() {
        val token = newAccount()

        val response = send(post(token, JSON_MEDIA_TYPE, onlySurrogateTitleBodyBytes()))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, POST))
        assertThat(storedTitles(token).single()).isEqualTo(ContractSpec.text(TITLE_POLICY, FALLBACK_TITLE_KEY))
    }

    private fun newAccount(): String {
        val email = "document${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/signup"))
        val login = send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/login"))
        return bodyOf(login).required("access_token").toString()
    }

    private fun upload(
        token: String,
        body: MultipartBody,
    ): HttpResponse<String> = send(post(token, body.contentType(), body.build()))

    private fun createFromText(
        token: String?,
        body: String,
    ): HttpResponse<String> = send(post(token, JSON_MEDIA_TYPE, body.toByteArray(Charsets.UTF_8)))

    /** 같은 요청을 바이트로 받는다 — P1 만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun createFromTextBytes(
        token: String?,
        body: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            post(token, JSON_MEDIA_TYPE, body.toByteArray(Charsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun textBody(
        text: String,
        workspaceId: String? = null,
    ): String =
        json.writeValueAsString(
            buildMap {
                put("text", text)
                if (workspaceId != null) put("workspace_id", workspaceId)
            },
        )

    /** 짝 없는 서로게이트를 JSON `\u` 이스케이프로 실은 본문 바이트. */
    private fun surrogateBodyBytes(): ByteArray = """{"text":"안내$SURROGATE_ESCAPE 문"}""".toByteArray(Charsets.UTF_8)

    private fun surrogateTitleBodyBytes(): ByteArray =
        """{"text":"정상 본문","title":"$TITLE_PREFIX$SURROGATE_ESCAPE$TITLE_SUFFIX"}""".toByteArray(Charsets.UTF_8)

    private fun onlySurrogateTitleBodyBytes(): ByteArray =
        """{"text":"정상 본문","title":"$SURROGATE_ESCAPE"}""".toByteArray(Charsets.UTF_8)

    private fun post(
        token: String?,
        contentType: String,
        body: ByteArray,
        path: String = DOCUMENTS_PATH,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** 계약이 든 형식 이름에 해당하는 정상 fixture. 모르는 형식이면 끊는다. */
    private fun sampleFor(format: String): ByteArray =
        when (format) {
            SourceFormat.DOCX.wireName -> UploadFixtures.sampleDocx()
            SourceFormat.PDF.wireName -> UploadFixtures.samplePdf()
            SourceFormat.HWPX.wireName -> UploadFixtures.sampleHwpx()
            else -> error("계약이 지원 형식 $format 를 선언했는데 그 형식의 정상 fixture 가 없다 — 이 케이스는 재지 못한다")
        }

    /** 첫 글자만 대문자로 — 확장자 비교가 대소문자를 가리는지 보는 값이다. */
    private fun mixedCase(format: String): String = format.replaceFirstChar(Char::uppercaseChar)

    /** `GET /documents` 가 내놓는 문서 식별자들. 「저장되지 않았다」의 사용자 축이다. */
    private fun listedDocumentIds(token: String): List<String> {
        val response =
            send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                    .header("Authorization", "Bearer $token")
                    .GET(),
            )
        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        return (bodyOf(response)["items"] as List<*>).map { (it as Map<*, *>)["id"].toString() }
    }

    /** 그 사용자 소유 문서 수. 「저장되지 않았다」를 목록 API 없이 재는 자리다. */
    private fun documentCount(token: String): Int =
        database.queryInt("SELECT count(*) FROM documents WHERE user_id = '${subjectOf(token)}'")

    private fun storedTitles(token: String): List<String> =
        database.queryFirstColumn(
            "SELECT title FROM documents WHERE user_id = '${subjectOf(token)}' ORDER BY created_at",
        )

    private fun defaultWorkspaceId(token: String): String =
        database
            .queryFirstColumn(
                "SELECT id FROM workspaces WHERE user_id = '${subjectOf(token)}' ORDER BY created_at LIMIT 1",
            ).single()

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, POST))
            .withFailMessage("계약이 POST %s 에 %d 를 선언하지 않는다", DOCUMENTS_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val POST = "post"
        private const val GET = "get"
        private const val DETAIL = "detail"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val PAYLOAD_TOO_LARGE = 413
        private const val UNPROCESSABLE = 422

        private const val CREATED_SCHEMA = "DocumentCreatedResponse"
        private const val ERROR_SCHEMA = "ErrorResponse"
        private const val PAYLOAD_TOO_LARGE_COMPONENT = "PayloadTooLarge"
        private const val TOO_LARGE_EXAMPLE = "too_large"
        private const val WORKSPACE_NOT_FOUND_EXAMPLE = "workspace_not_found"

        private const val INPUT_LIMITS = "x-input-limits"
        private const val TITLE_POLICY = "x-title-policy"
        private const val FALLBACK_TITLE_KEY = "fallback_title"
        private const val MAX_UPLOAD_BYTES_KEY = "max_upload_bytes"
        private const val ZIP_BUDGET_KEY = "zip_uncompressed_budget_bytes"
        private const val SUPPORTED_FORMATS_KEY = "supported_upload_formats"
        private const val LEGACY_DOC_POLICY_KEY = "legacy_doc_policy"

        private const val FILE_PART = "file"
        private const val WORKSPACE_ID_PART = "workspace_id"

        private const val JSON_MEDIA_TYPE = "application/json"

        /** DC-5 — 계약이 「대소문자를 가리지 않는다」고 적은 그 형태. */
        private const val MIXED_CASE_MULTIPART = "Multipart/Form-Data"

        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        private const val FORGED_TOKEN = "forged.token.value"
        private const val VALID_PASSWORD = "correct horse battery"

        /** 제출값 비반향을 재는 값. UUID 가 아니면 무엇이든 된다. */
        private const val SUBMITTED_BAD_WORKSPACE = "not-a-uuid-1234"

        /** 상한 초과 파일을 채우는 바이트. 내용은 판정에 쓰이지 않는다(크기 검사가 먼저다). */
        private const val OVERSIZED_FILLER: Byte = 0x41

        /** JSON 본문에 문자로 적는 이스케이프. 소스에 실제 서로게이트를 싣지 않는다. */
        private const val SURROGATE_ESCAPE = "\\ud800"
        private const val LONE_SURROGATE = "\uD800"
        private const val TITLE_PREFIX = "안내"
        private const val TITLE_SUFFIX = "문"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val DOCUMENT_REACH_TEST_SECRET: String = "document-test-signing-key-0123456789-abc"

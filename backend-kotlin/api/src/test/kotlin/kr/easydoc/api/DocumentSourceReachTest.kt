package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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

/**
 * `GET /documents/{document_id}/source` 의 실측 계약.
 *
 * 재는 축은 셋이다 — ⑴ 소유자에게 **저장된 원문이 그대로** 돌아오는가(암호문이 실제로
 * 열리는가), ⑵ 남의 것·없는 것이 **구분되지 않는 404** 인가, ⑶ 개인정보를 싣는 응답의
 * 사적 헤더가 붙는가.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_SOURCE_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSourceReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("DS-1 내 문서의 원문이 **올린 그대로** 돌아온다 — 형식·문자 수도 함께")
    fun `내 문서의 원문을 되읽는다`() {
        val token = newAccount()
        val documentId = createDocument(token, SOURCE_WITH_RRN)

        val response = readSource(token, documentId)

        assertDeclaredStatus(response, ContractSpec.successStatus(SOURCE_PATH, GET))
        val body = bodyOf(response)
        assertThat(body[DOCUMENT_ID_PROPERTY]).isEqualTo(documentId)
        assertThat(body[SOURCE_FORMAT_PROPERTY]).isEqualTo(TEXT_FORMAT)
        assertThat(body[CHAR_COUNT_PROPERTY]).isEqualTo(SOURCE_WITH_RRN.length)
        assertThat(body[SOURCE_TEXT_PROPERTY])
            .describedAs("저장은 암호문이다 — 값이 그대로 돌아왔다는 것은 복호화가 실제로 돌았다는 뜻이다")
            .isEqualTo(SOURCE_WITH_RRN)
    }

    @Test
    @DisplayName("DS-1 응답 필드 집합이 계약 `DocumentSourceResponse` 의 required 와 **정확히** 같다")
    fun `응답 필드가 계약과 같다`() {
        val token = newAccount()

        val body = bodyOf(readSource(token, createDocument(token, SOURCE_WITH_RRN)))

        assertThat(body.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired(SOURCE_SCHEMA))
    }

    @Test
    @DisplayName("DS-1 **마스킹 전** 값이 그대로 실린다 — 그래서 이 응답이 하한선 열거에 있다")
    fun `마스킹 전 값이 그대로 실린다`() {
        val token = newAccount()

        val body = bodyOf(readSource(token, createDocument(token, SOURCE_WITH_RRN)))

        assertThat(body[SOURCE_TEXT_PROPERTY].toString())
            .describedAs("자리표시자로 바뀌어 나오면 검수 화면이 원문과 초안을 비교할 수 없다")
            .contains(RRN)
        assertThat(ContractSpec.privateResponseHeaderTargets())
            .describedAs("개인정보가 실리는데 하한선 열거에 없으면 그 응답은 목록 밖으로 샌다")
            .contains("GET $SOURCE_PATH")
    }

    @Test
    @DisplayName("DS-1 응답에 사적 헤더 2종이 붙는다")
    fun `사적 헤더가 붙는다`() {
        val token = newAccount()

        val response = readSource(token, createDocument(token, SOURCE_WITH_RRN))

        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    @Test
    @DisplayName("DS-2 타인 소유 문서 → **404 이고 403 이 아니다** · detail 이 계약 404 예시와 같다")
    fun `타인 문서는 404 이고 403 이 아니다`() {
        val theirDocument = createDocument(newAccount(), SOURCE_WITH_RRN)

        val response = readSource(newAccount(), theirDocument)

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(SOURCE_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("DS-2 404 본문에 **남의 원문 조각이 없다** — 거절이 값을 흘리지 않는다")
    fun `404 가 남의 원문을 흘리지 않는다`() {
        val theirDocument = createDocument(newAccount(), SOURCE_WITH_RRN)

        val response = readSource(newAccount(), theirDocument)

        assertThat(response.body()).doesNotContain(RRN)
    }

    @Test
    @DisplayName("DS-3 없는 식별자와 타인 식별자의 **상태·본문 바이트·헤더 이름 집합이 완전히 같다**")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirDocument = createDocument(newAccount(), SOURCE_WITH_RRN)

        val absent = readSourceBytes(mine, UUID.randomUUID().toString())
        val others = readSourceBytes(mine, theirDocument)

        OwnershipConcealment.assertIndistinguishable("GET $SOURCE_PATH", absent, others)
    }

    @Test
    @DisplayName("DS-4 파기한 문서는 404 다 — 보존 만료 뒤와 같은 갈래다")
    fun `지운 문서는 404 다`() {
        val token = newAccount()
        val documentId = createDocument(token, SOURCE_WITH_RRN)
        check(delete(token, documentId).statusCode() == ContractSpec.successStatus(ITEM_PATH, DELETE)) {
            "파기가 실패했다"
        }

        val response = readSource(token, documentId)

        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(SOURCE_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("DS-5 UUID 가 아닌 경로 변수 → 422 · detail **배열**")
    fun `UUID 가 아닌 경로 변수는 422 배열이다`() {
        val response = readSource(newAccount(), NOT_A_UUID)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DS-6 Authorization 이 없으면 401 이고 `WWW-Authenticate` 가 붙는다")
    fun `토큰이 없으면 401 이다`() {
        val response = readSource(token = null, documentId = UUID.randomUUID().toString())

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
    }

    @Test
    @DisplayName("DS-6 위조 토큰 + UUID 가 아닌 경로 변수 → **401**(422 가 아니다) — 인증이 변환보다 먼저다")
    fun `인증이 경로 변수 변환보다 먼저다`() {
        val response = readSource(FORGED_TOKEN, NOT_A_UUID)

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("DS-7 조회가 **행을 잠그지 않는다** — 저장된 암호문이 조회 뒤에도 그대로다")
    fun `조회가 저장을 건드리지 않는다`() {
        val token = newAccount()
        val documentId = createDocument(token, SOURCE_WITH_RRN)
        val before = sourceCiphertext(documentId)

        readSource(token, documentId)

        assertThat(sourceCiphertext(documentId))
            .describedAs("조회는 읽기다 — 봉투나 암호문이 달라지면 회전 말고 다른 무언가가 쓰고 있다")
            .isEqualTo(before)
    }

    private fun newAccount(): String {
        val email = "documentsource${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        return bodyOf(send(post(null, credentials, "/auth/login"))).required("access_token").toString()
    }

    /** 붙여넣기 모드로 문서를 만들고 그 식별자를 돌려준다. */
    private fun createDocument(
        token: String,
        text: String,
    ): String {
        val response = send(post(token, json.writeValueAsString(mapOf("text" to text)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        return bodyOf(response).required(DOCUMENT_ID_PROPERTY).toString()
    }

    private fun readSource(
        token: String?,
        documentId: String,
    ): HttpResponse<String> = send(sourceRequest(token, documentId))

    /** 같은 요청을 바이트로 받는다 — 소유권 은닉만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun readSourceBytes(
        token: String?,
        documentId: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            sourceRequest(token, documentId).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** 두 팔이 같은 요청 조립을 쓰게 한다 — 조립이 갈리면 두 팔의 차이가 요청 차이가 된다. */
    private fun sourceRequest(
        token: String?,
        documentId: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port${sourcePath(documentId)}")).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun delete(
        token: String,
        documentId: String,
    ): HttpResponse<String> {
        val path = ITEM_PATH.replace("{${ContractSpec.pathVariable(ITEM_PATH, DELETE).name}}", documentId)
        return send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $token")
                .DELETE(),
        )
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
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun sourcePath(documentId: String): String =
        SOURCE_PATH.replace("{${ContractSpec.pathVariable(SOURCE_PATH, GET).name}}", documentId)

    /** 저장된 암호문과 그 봉투. 조회가 쓰기를 동반하지 않는지 보는 재료다. */
    private fun sourceCiphertext(documentId: String): List<String> =
        database.queryFirstColumn(
            "SELECT encode(source_text_encrypted, 'hex') || ':' || encryption_scheme || ':' || key_version " +
                "FROM documents WHERE id = '$documentId'",
        )

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("GET %s 가 %d 이 아니다: %s", SOURCE_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(SOURCE_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", SOURCE_PATH, status)
            .contains(status.toString())
    }

    /** `detail` 이 배열이고 항목 키 집합이 정확히 `ValidationErrorItem.required` 다. */
    private fun assertValidationArray(response: HttpResponse<String>) {
        val items = bodyOf(response)[DETAIL]
        assertThat(items)
            .withFailMessage("detail 이 배열이 아니다 — 스키마 층 거절은 배열이어야 한다: %s", items)
            .isInstanceOf(List::class.java)

        val declared = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items as List<*>).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet()).isEqualTo(declared)
        }
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val ITEM_PATH = "/documents/{document_id}"
        private const val SOURCE_PATH = "/documents/{document_id}/source"
        private const val SOURCE_SCHEMA = "DocumentSourceResponse"
        private const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        private const val GET = "get"
        private const val POST = "post"
        private const val DELETE = "delete"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val UNPROCESSABLE = 422

        private const val DETAIL = "detail"
        private const val DOCUMENT_ID_PROPERTY = "document_id"
        private const val SOURCE_FORMAT_PROPERTY = "source_format"
        private const val CHAR_COUNT_PROPERTY = "char_count"
        private const val SOURCE_TEXT_PROPERTY = "source_text"

        /** 붙여넣기 문서의 형식. 값의 정본은 계약 `SourceFormat` 이다. */
        private const val TEXT_FORMAT = "text"

        /** 계약이 이 경로 404 의 인라인 예시에 붙인 이름. */
        private const val NOT_FOUND_EXAMPLE = "not_found"

        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        private const val NOT_A_UUID = "not-a-uuid"
        private const val FORGED_TOKEN = "forged.token.value"
        private const val VALID_PASSWORD = "correct horse battery"

        /** 합성 주민등록번호. 마스킹 **전** 값이 그대로 나가는지를 재는 표식이다. */
        private const val RRN = "900101-1234567"
        private const val SOURCE_WITH_RRN = "민원 안내문 본문입니다. 주민등록번호 $RRN 를 포함합니다."

        private var counter = 0

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_source") }

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
const val DOCUMENT_SOURCE_TEST_SECRET: String = "document-source-test-signing-key-0123456789"

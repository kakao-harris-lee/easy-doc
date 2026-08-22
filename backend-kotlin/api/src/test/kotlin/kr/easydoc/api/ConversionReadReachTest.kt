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

/** `GET /conversions/{conversion_id}` 의 실측 계약 — 명세 CR 표의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$CONVERSION_READ_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionReadReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("CR-2 계약 `ConversionStatus.enum` 의 **각 값**을 실제로 밟고, 그 전부에서 키가 하나도 생략되지 않는다 (X-E2·X-E4)")
    fun `상태 네 값 전부에서 키 집합이 계약과 같다`() {
        val token = newAccount()

        val declaredStatuses = ContractSpec.schemaEnum(STATUS_SCHEMA)
        val declaredKeys = ContractSpec.schemaRequired(CONVERSION_SCHEMA)

        val observed =
            declaredStatuses.map { status ->
                val conversionId = createDocument(token).second
                if (status != PENDING_STATUS) forceStatus(conversionId, status)
                val body = bodyOf(read(token, conversionId))
                assertThat(body.keys.map { it.toString() }.toSet())
                    .withFailMessage("상태 %s 응답의 키 집합이 계약 %s 와 다르다: %s", status, CONVERSION_SCHEMA, body.keys)
                    .isEqualTo(declaredKeys)
                body[STATUS_PROPERTY].toString()
            }

        assertThat(observed.toSet()).isEqualTo(declaredStatuses.toSet())
    }

    @Test
    @DisplayName("CR-3 완료 전 상태에서 배열 필드 둘이 `null` 이 아니라 **빈 배열**이다 (X-E3)")
    fun `완료 전에는 빈 배열이다`() {
        val token = newAccount()
        val beforeDone = ContractSpec.schemaEnum(STATUS_SCHEMA).filterNot { it == DONE_STATUS }
        assertThat(beforeDone).describedAs("완료 전 상태가 계약에 하나도 없다 — 이 케이스가 성립하지 않는다").isNotEmpty()

        beforeDone.forEach { status ->
            val conversionId = createDocument(token).second
            if (status != PENDING_STATUS) forceStatus(conversionId, status)

            val body = bodyOf(read(token, conversionId))

            assertThat(body[MASKED_ITEMS_PROPERTY])
                .withFailMessage(
                    "상태 %s 의 %s 가 빈 배열이 아니다: %s",
                    status,
                    MASKED_ITEMS_PROPERTY,
                    body[MASKED_ITEMS_PROPERTY],
                ).isEqualTo(emptyList<Any>())
            assertThat(body[MISSING_PLACEHOLDERS_PROPERTY])
                .withFailMessage("상태 %s 의 %s 가 빈 배열이 아니다", status, MISSING_PLACEHOLDERS_PROPERTY)
                .isEqualTo(emptyList<Any>())
            assertThat(body[EASY_TEXT_PROPERTY]).describedAs("완료 전인데 초안이 실렸다").isNull()
            assertThat(body[EDITED_TEXT_PROPERTY]).describedAs("완료 전인데 검수본이 실렸다").isNull()
        }
    }

    @Test
    @DisplayName("CR-4 실패 변환의 실패 코드가 비어 있지 않고 계약 `maxLength` 안이며, **본문·모델 응답이 담기지 않는다**")
    fun `실패 코드가 본문을 담지 않는다`() {
        val token = newAccount()
        val body = "실패 재현용 안내문 본문 — 이 문장이 실패 코드에 실리면 안 된다"
        val conversionId = createDocument(token, body).second
        forceStatus(conversionId, FAILED_STATUS, failureCode = "ProviderUnavailable")

        val response = bodyOf(read(token, conversionId))

        val code = response[FAILURE_CODE_PROPERTY]?.toString()
        assertThat(code).describedAs("실패 상태인데 실패 코드가 비었다 — 사용자가 사유를 알 수 없다").isNotBlank()

        val maxLength =
            ContractSpec.number(
                "components",
                "schemas",
                CONVERSION_SCHEMA,
                "properties",
                FAILURE_CODE_PROPERTY,
                "maxLength",
            )
        assertThat(code!!.length).isLessThanOrEqualTo(maxLength)

        assertThat(code).doesNotContain("안내문").doesNotContain(body)
    }

    @Test
    @DisplayName("CR-5 마스킹 항목의 키 집합이 정확히 계약 required · 범주가 **2종 집합 안**이고 그 밖의 값 0건 · 자리표시자가 계약 pattern 과 맞다 (P-32)")
    fun `마스킹 항목이 실제 저장 형식을 거쳐 계약대로 나온다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second

        val declaredCategories = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY)
        val items =
            declaredCategories.mapIndexed { index, label ->
                val category = MaskCategory.entries.first { it.label == label }
                MaskedItem(category, "[[$label${index + 1}]]", Secret("가려진값${index + 1}"))
            }
        markDone(conversionId, maskedItems = items)

        val body = bodyOf(read(token, conversionId))
        val responseItems = body[MASKED_ITEMS_PROPERTY] as List<*>

        assertThat(responseItems).hasSameSizeAs(items)
        val declaredKeys = ContractSpec.schemaRequired(MASKED_ITEM_SCHEMA)
        val pattern = Regex(ContractSpec.schemaPropertyPattern(MASKED_ITEM_SCHEMA, PLACEHOLDER_PROPERTY)).toPattern()
        responseItems.forEach { raw ->
            val item = raw as Map<*, *>
            assertThat(item.keys.map { it.toString() }.toSet()).isEqualTo(declaredKeys)
            assertThat(item[PLACEHOLDER_PROPERTY].toString()).matches(pattern)
        }

        assertThat(responseItems.map { (it as Map<*, *>)[CATEGORY_PROPERTY].toString() }.toSet())
            .withFailMessage("범주 값이 계약 enum 과 다르다 — 저장 키가 화면 문구 자리로 샜을 수 있다")
            .isEqualTo(declaredCategories.toSet())

        assertThat(responseItems.map { (it as Map<*, *>)[ORIGINAL_PROPERTY].toString() })
            .containsExactlyInAnyOrderElementsOf(items.map { it.original.reveal() })
    }

    @Test
    @DisplayName("CR-6 유실 자리표시자의 각 원소가 계약 `items.pattern` 과 맞다")
    fun `유실 라벨이 계약 형식을 지킨다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        val labels = ContractSpec.schemaPropertyEnum(MASKED_ITEM_SCHEMA, CATEGORY_PROPERTY).map { "[[${it}1]]" }
        markDone(conversionId, missingPlaceholders = labels)

        val body = bodyOf(read(token, conversionId))

        val pattern =
            Regex(
                ContractSpec.schemaPropertyPattern(CONVERSION_SCHEMA, MISSING_PLACEHOLDERS_PROPERTY),
            ).toPattern()
        val observed = (body[MISSING_PLACEHOLDERS_PROPERTY] as List<*>).map { it.toString() }
        assertThat(observed).hasSameSizeAs(labels)
        observed.forEach { assertThat(it).matches(pattern) }
    }

    @Test
    @DisplayName("완료 변환의 초안·검수본이 **복호화되어** 그대로 나온다 — 봉인 왕복이 HTTP 표면에서 성립한다")
    fun `완료 변환의 본문이 왕복한다`() {
        val token = newAccount()
        val conversionId = createDocument(token).second
        markDone(conversionId, easyText = "쉬운 글 초안입니다.", editedText = "담당자가 다듬은 문장입니다.")

        val body = bodyOf(read(token, conversionId))

        assertThat(body[EASY_TEXT_PROPERTY]).isEqualTo("쉬운 글 초안입니다.")
        assertThat(body[EDITED_TEXT_PROPERTY]).isEqualTo("담당자가 다듬은 문장입니다.")
    }

    @Test
    @DisplayName("CR-7 타인 소유 변환 → **404 이고 403 이 아니다** · detail 이 계약 404 예시와 같다 (X-B1)")
    fun `타인 변환 조회는 404 이고 403 이 아니다`() {
        val theirConversion = createDocument(newAccount()).second

        val response = read(newAccount(), theirConversion.toString())

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("CR-8 없는 식별자와 타인 식별자의 **상태·본문 원시 바이트·헤더 이름 집합이 완전히 같다** (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirConversion = createDocument(newAccount()).second

        val absent = readBytes(mine, UUID.randomUUID().toString())
        val others = readBytes(mine, theirConversion.toString())

        OwnershipConcealment.assertIndistinguishable("GET $CONVERSION_ITEM_PATH", absent, others)
    }

    @Test
    @DisplayName(
        "CR-10 Authorization 이 없으면 401 · `WWW-Authenticate` · 본문 키 집합 정확히 `ErrorResponse.required` (X-A1·X-C8)",
    )
    fun `토큰이 없으면 401 이다`() {
        val response = read(token = null, conversionId = UUID.randomUUID().toString())

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .withFailMessage("401 에 WWW-Authenticate 가 없다 — 클라이언트가 재인증 방식을 알 수 없다")
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .withFailMessage("401 본문 키가 계약 %s 와 다르다 — 구현 수단이 응답으로 샌다", ERROR_SCHEMA)
            .isEqualTo(ContractSpec.schemaRequired(ERROR_SCHEMA))
    }

    /** DD-5 — 삭제 후 변환 조회가 404 다. C5 가 이 팔을 유보한 자리다. */
    @Test
    @DisplayName("DD-5 문서를 파기하면 그 변환 조회가 404 다 — **매핑 부재 404 와 본문이 다르다**")
    fun `삭제 후 변환 조회가 파기 404 를 낸다`() {
        val token = newAccount()
        val (documentId, conversionId) = createDocument(token)

        assertDeclaredStatus(
            read(token, conversionId.toString()),
            ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET),
        )

        val deleted = send(deleteRequest(token, documentId.toString()))
        check(deleted.statusCode() == ContractSpec.successStatus(DOCUMENT_ITEM_PATH, DELETE)) {
            "문서 파기가 실패했다: ${deleted.statusCode()}"
        }

        val afterDelete = read(token, conversionId.toString())
        assertDeclaredStatus(afterDelete, NOT_FOUND)

        assertThat(bodyOf(afterDelete)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))

        val unmapped = send(getRequest(token, "$CONVERSION_PATH_PREFIX$conversionId/$UNMAPPED_SEGMENT"))
        assertThat(unmapped.statusCode()).isEqualTo(NOT_FOUND)
        assertThat(unmapped.body())
            .withFailMessage(
                "파기 404 와 **매핑 부재** 404 의 본문이 같다 — 이 케이스는 「핸들러가 없어서 404」를 " +
                    "「파기됐으니 404」로 읽고 있다. 파기: %s / 매핑 부재: %s",
                afterDelete.body(),
                unmapped.body(),
            ).isNotEqualTo(afterDelete.body())
    }

    /** 결과 열을 채워 완료 상태로 만든다. 워커가 할 일을 SQL 로 대신한다. */
    private fun markDone(
        conversionId: UUID,
        easyText: String? = "쉬운 글 초안입니다.",
        editedText: String? = null,
        maskedItems: List<MaskedItem> = emptyList(),
        missingPlaceholders: List<String> = emptyList(),
    ) {
        val easy = sealed(easyText, conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val edited = sealed(editedText, conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
        val table =
            maskedItems
                .takeIf { it.isNotEmpty() }
                ?.let { codec.encode(it).value }
        val masked = sealed(table, conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
        val labels = json.writeValueAsString(missingPlaceholders).replace("'", "''")

        // SQL 은 **companion 의 상수**에 두고 조각만 채운다. 두 가지를 동시에 만족시켜야 한다:
        // ⑴ `scan_privacy_invariants.py` 의 논리 줄 결합기는 호출부에 놓인 여러 줄 문자열에서
        //    상태가 열린 채 40줄 상한에 닿아 그 구간을 **미검사**로 남긴다(실측: BLOCK).
        // ⑵ `EnvelopeColumnWriteGuardTest` 는 **문자열 리터럴로 읽히는 SQL** 만 본다 —
        //    조각을 `+` 로 이어 붙이면 「암호문 열을 SET 하는 UPDATE」가 그 가드의 눈에서
        //    사라진다(실측: 그 파일이 인구조사에서 빠졌다).
        // 상수로 옮기면 둘 다 선다 — `JdbcConversionRepository.FIND_OWNED_SQL` 과 같은 형태다.
        database.execute(
            MARK_DONE_SQL.format(
                easy,
                edited,
                masked,
                cipher.writeScheme,
                cipher.writeKeyVersion,
                labels,
                conversionId,
            ),
        )
    }

    /** 상태만 바꾼다. 결과 열은 건드리지 않으므로 「완료 전」 모양이 유지된다. */
    private fun forceStatus(
        conversionId: UUID,
        status: String,
        failureCode: String? = null,
    ) {
        // 문자열 템플릿 **안에** 인용부호를 겹치지 않는다(`"'${'$'}{x.replace("'", "''")}'"` 형태).
        // 사유는 [markDone] 의 주석과 같다 — 스캐너의 어휘 분석기가 그 겹침에서 문자열 상태를
        // 열린 채로 두고, 그 구간이 미검사로 남는다(실측: 그 한 줄이 BLOCK 을 냈다).
        val escaped = failureCode?.replace(SINGLE_QUOTE, ESCAPED_QUOTE)
        val code = if (escaped == null) "NULL" else SINGLE_QUOTE + escaped + SINGLE_QUOTE
        database.execute(
            "UPDATE conversions SET status = '$status', failure_code = $code WHERE id = '$conversionId'",
        )
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

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "conversionread${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        return bodyOf(send(post(null, credentials, "/auth/login")))
            .getValue("access_token")
            .toString()
    }

    /** 문서를 접수하고 `(문서 id, 변환 id)` 를 돌려준다. **행은 제품이 쓴다.** */
    private fun createDocument(
        token: String,
        text: String = "변환 조회 대상 안내문 본문",
    ): Pair<UUID, UUID> {
        val response = send(post(token, json.writeValueAsString(mapOf("text" to text)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val body = bodyOf(response)
        return UUID.fromString(body.getValue("document_id").toString()) to
            UUID.fromString(body.getValue("conversion_id").toString())
    }

    private fun read(
        token: String?,
        conversionId: String,
    ): HttpResponse<String> = send(getRequest(token, itemPath(conversionId)))

    /** 식별자 갈래. 케이스가 `UUID` 를 들고 있을 때 `toString()` 을 흩뿌리지 않게 한다. */
    private fun read(
        token: String?,
        conversionId: UUID,
    ): HttpResponse<String> = read(token, conversionId.toString())

    /** 같은 요청을 **바이트로** 받는다 — CR-8 만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun readBytes(
        token: String?,
        conversionId: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            getRequest(token, itemPath(conversionId)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** 두 팔이 **같은 요청 조립**을 쓰게 한다 — 조립이 갈리면 두 팔의 차이가 요청 차이가 된다. */
    private fun getRequest(
        token: String?,
        path: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun deleteRequest(
        token: String,
        documentId: String,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$DOCUMENT_PATH_PREFIX$documentId"))
            .header("Authorization", "Bearer $token")
            .DELETE()

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
    private fun itemPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, GET).name}}",
            conversionId,
        )

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("GET %s 가 %d 이 아니다: %s", CONVERSION_ITEM_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(CONVERSION_ITEM_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", CONVERSION_ITEM_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    companion object {
        /** 저장 형식의 정본. 제품 클래스다 — 사유는 클래스 KDoc. */
        private val codec = MaskedItemCodec()

        private const val DOCUMENTS_PATH = "/documents"
        private const val DOCUMENT_ITEM_PATH = "/documents/{document_id}"
        private const val DOCUMENT_PATH_PREFIX = "/documents/"
        private const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        private const val CONVERSION_PATH_PREFIX = "/conversions/"
        private const val GET = "get"
        private const val POST = "post"
        private const val DELETE = "delete"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404

        private const val CONVERSION_SCHEMA = "ConversionResponse"
        private const val MASKED_ITEM_SCHEMA = "MaskedItemResponse"
        private const val STATUS_SCHEMA = "ConversionStatus"
        private const val ERROR_SCHEMA = "ErrorResponse"

        private const val STATUS_PROPERTY = "status"
        private const val EASY_TEXT_PROPERTY = "easy_text"
        private const val EDITED_TEXT_PROPERTY = "edited_text"
        private const val MASKED_ITEMS_PROPERTY = "masked_items"
        private const val MISSING_PLACEHOLDERS_PROPERTY = "missing_placeholders"
        private const val FAILURE_CODE_PROPERTY = "failure_code"
        private const val CATEGORY_PROPERTY = "category"
        private const val PLACEHOLDER_PROPERTY = "placeholder"
        private const val ORIGINAL_PROPERTY = "original"
        private const val DETAIL = "detail"

        /**
         * 계약 `ConversionStatus.enum` 의 값들. 분모로 쓰지 않는다 — 분모는 계약에서 읽고,
         * 이 상수들은 「그 값에 특별한 처분이 있는」 자리(대기는 SQL 을 쓰지 않는다 등)에만 쓴다.
         */
        private const val PENDING_STATUS = "pending"
        private const val DONE_STATUS = "done"
        private const val FAILED_STATUS = "failed"

        /** 계약이 이 경로 404 의 인라인 예시에 붙인 이름. 값이 아니라 이름이다. */
        private const val NOT_FOUND_EXAMPLE = "not_found"

        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 계약에 없는 경로 조각. DD-5 의 근거 3 이 이것으로 「매핑 부재 404」를 만든다. */
        private const val UNMAPPED_SEGMENT = "no-such-subresource"

        private const val VALID_PASSWORD = "correct horse battery"

        /**
         * 결과 열을 채우는 UPDATE. **봉투 두 값을 같은 문장에서 함께 SET 한다** —
         * `EnvelopeColumnWriteGuardTest` 가 소스 전수(테스트 포함)에서 그 규약을 강제하고,
         * 첫 판이 그것을 어겨 실제로 빨개졌다. 워커가 할 UPDATE 도 같은 모양이어야 한다.
         *
         * `%s` 자리를 [markDone] 이 채운다. 상수로 둔 사유는 그 함수의 주석.
         */
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
                model = 'test-model',
                provider_name = 'test-provider',
                input_tokens = 11,
                output_tokens = 22
            WHERE id = '%s'
            """.trimIndent()

        /** SQL 리터럴 인용부호. 상수로 두는 사유는 [forceStatus] 의 주석. */
        private const val SINGLE_QUOTE = "'"
        private const val ESCAPED_QUOTE = "''"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 상태를 SQL 로 바꾸므로 다른 테스트의 행과 섞이면 안 된다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_read") }

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
const val CONVERSION_READ_TEST_SECRET: String = "conversion-read-test-signing-key-0123456789"

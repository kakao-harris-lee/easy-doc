package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
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

/** `PUT /conversions/{conversion_id}` 실측 계약 — CU 표의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$CONVERSION_REVIEW_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionReviewReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("CU-1 완료된 내 변환에 유효한 수정본 → 200 · 사적 헤더 2종(값·**개수**) · 응답 키 집합이 `ConversionResponse.required` 와 같다")
    fun `검수 저장이 조회와 같은 응답을 낸다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)

        val response = review(token, conversionId, "담당자가 다듬은 문장입니다.")

        assertDeclaredStatus(response, ContractSpec.successStatus(CONVERSION_ITEM_PATH, PUT))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .withFailMessage("검수 저장 응답의 키 집합이 계약 %s 와 다르다", CONVERSION_SCHEMA)
            .isEqualTo(ContractSpec.schemaRequired(CONVERSION_SCHEMA))

        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("검수 저장 응답에 %s 가 없거나 개수가 다르다: %s", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    @Test
    @DisplayName("CU-2 **AI 초안이 보존된다** — 저장 전 값과 같고 수정본 필드만 바뀌며 검수 시각이 채워진다")
    fun `초안은 보존되고 수정본만 바뀐다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val before = bodyOf(read(token, conversionId))
        assertThat(before[EASY_TEXT_PROPERTY]).isEqualTo(STORED_DRAFT)
        assertThat(before[REVIEWED_AT_PROPERTY]).isNull()

        val after = bodyOf(review(token, conversionId, EDITED_BODY))

        assertThat(after[EASY_TEXT_PROPERTY])
            .withFailMessage("검수 저장이 AI 초안을 덮어썼다 — 수정률 KPI 의 기준선이 사라진다")
            .isEqualTo(before[EASY_TEXT_PROPERTY])
        assertThat(after[EDITED_TEXT_PROPERTY]).isEqualTo(EDITED_BODY)
        assertThat(after[REVIEWED_AT_PROPERTY])
            .withFailMessage("검수 시각이 채워지지 않았다 — 목록의 「검수함」 표시가 이 값을 본다")
            .isNotNull()
    }

    @Test
    @DisplayName("CU-7 저장한 뒤 다시 조회해도 값이 **정규화된 것**이다 — 판정에만 쓰고 버리는 구현이 여기서 걸린다")
    fun `저장된 값이 정규화 값이다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val raw = "앞${CONTROL_CHAR}가운데${CONTROL_CHAR}뒤"
        val normalized = "앞가운데뒤"

        assertThat(bodyOf(review(token, conversionId, raw))[EDITED_TEXT_PROPERTY]).isEqualTo(normalized)
        assertThat(bodyOf(read(token, conversionId))[EDITED_TEXT_PROPERTY])
            .withFailMessage("다시 읽은 값이 정규화 값이 아니다 — 정규화가 저장까지 가지 않았다")
            .isEqualTo(normalized)
    }

    @Test
    @DisplayName("CU-3 아직 완료되지 않은 변환 → **409**(422 도 404 도 아님) · `detail` 이 계약 409 예시와 같다")
    fun `완료 전 검수 저장은 409 다`() {
        val token = newAccount()
        val beforeDone = ContractSpec.schemaEnum(STATUS_SCHEMA).filterNot { it == DONE_STATUS }
        assertThat(beforeDone).describedAs("완료 전 상태가 계약에 하나도 없다 — 이 케이스가 성립하지 않는다").isNotEmpty()

        beforeDone.forEach { status ->
            val conversionId = createDocument(token).second
            if (status != PENDING_STATUS) forceStatus(conversionId, status)

            val response = review(token, conversionId, "완료 전에 보낸 수정본")

            assertDeclaredStatus(response, CONFLICT)
            assertThat(bodyOf(response)[DETAIL])
                .withFailMessage("상태 %s 의 409 detail 이 계약 예시와 다르다", status)
                .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, PUT, CONFLICT, NOT_DONE_EXAMPLE))
        }
    }

    @Test
    @DisplayName("CU-4 제어문자만 담긴 수정본 → 422 · `detail` **문자열** · **빈 값 갈래**와 같다")
    fun `제어문자만 담긴 수정본은 빈 값이다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)

        val response = review(token, conversionId, CONTROL_CHAR.repeat(CONTROL_ONLY_LENGTH))

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("제어문자만 담긴 수정본의 detail 이 문자열이 아니다")
            .isInstanceOf(String::class.java)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("제어문자만 담긴 수정본이 빈 값 갈래로 거절되지 않았다 — 정규화가 판정보다 뒤에 있다")
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, PUT, UNPROCESSABLE, EMPTY_EXAMPLE))
    }

    @Test
    @DisplayName("CU-5 정규화 후 길이가 상한 **초과**면 422(문자열 detail) / **정확히 상한**이면 통과 (X-F2)")
    fun `길이 경계가 양쪽으로 고정된다`() {
        val token = newAccount()
        val limit = ContractSpec.requestFieldConstraint(EDITED_TEXT_FIELD).limit

        val exact = review(token, doneConversion(token).second, FILLER_CHAR.repeat(limit))
        assertDeclaredStatus(exact, ContractSpec.successStatus(CONVERSION_ITEM_PATH, PUT))

        val over = review(token, doneConversion(token).second, FILLER_CHAR.repeat(limit + 1))
        assertDeclaredStatus(over, UNPROCESSABLE)
        assertThat(bodyOf(over)[DETAIL])
            .withFailMessage("상한 초과의 detail 이 문자열이 아니다 — 스키마 층이 판정했다는 뜻이다")
            .isInstanceOf(String::class.java)
        assertThat(bodyOf(over)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, PUT, UNPROCESSABLE, TOO_LONG_EXAMPLE))
    }

    @Test
    @DisplayName("CU-6 원시 길이는 상한 초과인데 제어문자를 걷어내면 이하 → **통과** (DC-11 의 대비 쌍 · X-F9)")
    fun `정규화 후 이하면 통과한다`() {
        val token = newAccount()
        val limit = ContractSpec.requestFieldConstraint(EDITED_TEXT_FIELD).limit
        val raw = FILLER_CHAR.repeat(limit) + CONTROL_CHAR.repeat(DIVERGENCE_NOISE)

        assertThat(raw.length)
            .withFailMessage("프로브의 원시 길이가 상한을 넘지 않는다 — 이 케이스가 아무것도 재지 않는다")
            .isGreaterThan(limit)

        val response = review(token, doneConversion(token).second, raw)

        assertDeclaredStatus(response, ContractSpec.successStatus(CONVERSION_ITEM_PATH, PUT))
        assertThat((bodyOf(response)[EDITED_TEXT_PROPERTY] as String).length)
            .withFailMessage("저장된 값의 길이가 정규화 후 길이가 아니다 — 제어문자가 남았다")
            .isEqualTo(limit)
    }

    @Test
    @DisplayName("저장 정의역 — 짝 없는 서로게이트가 든 수정본은 422 문자열이다(`x-stored-text-domain` `edited_text` 팔)")
    fun `텍스트로 저장할 수 없는 수정본은 거절된다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val body = """{"$EDITED_TEXT_PROPERTY":"안내$SURROGATE_ESCAPE 문"}"""

        val response = send(jsonRequest(itemPath(conversionId.toString()), token).PUT(bodyPublisher(body)))

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("저장 정의역 거절의 detail 이 문자열이 아니다")
            .isInstanceOf(String::class.java)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("저장 정의역 거절 문구가 계약 `x-stored-text-domain.detail` 과 다르다")
            .isEqualTo(ContractSpec.storedTextDomain().detail)
        // 거절됐으니 저장도 없어야 한다 — 「거절 문구만 맞고 행은 바뀐」 상태를 배제한다.
        assertThat(bodyOf(read(token, conversionId))[EDITED_TEXT_PROPERTY]).isNull()
    }

    @Test
    @DisplayName("CU-8 타인 소유 변환 → **부재 응답이고 거절 코드가 갈리지 않는다** · 없는 것과 **응답이 구별되지 않는다** (X-B1·X-B2)")
    fun `타인 변환 검수 저장은 404 이고 403 이 아니다`() {
        val mine = newAccount()
        val theirs = doneConversion(newAccount()).second

        val others = reviewBytes(mine, theirs, VALID_REVIEW)
        val absent = reviewBytes(mine, UUID.randomUUID(), VALID_REVIEW)

        assertThat(others.statusCode())
            .withFailMessage("타인 변환 검수 저장이 부재 응답이 아니다 — 거절을 가르면 남의 자원 존재를 확인해 준다")
            .isEqualTo(NOT_FOUND)
        assertThat(others.statusCode())
            .withFailMessage("타인 자원 거절이 부재와 다른 코드다 — 그 차이가 곧 존재 확인 수단이다")
            .isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable("PUT $CONVERSION_ITEM_PATH", absent, others)
    }

    @Test
    @DisplayName("CU-11 `Authorization` 이 없으면 401 · `WWW-Authenticate` (X-A1)")
    fun `토큰이 없으면 401 이다`() {
        val response = review(token = null, conversionId = UUID.randomUUID(), text = VALID_REVIEW)

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .withFailMessage("401 에 WWW-Authenticate 가 없다")
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
    }

    /** `x-unsupported-media-type` 의 **마지막 미측정 팔**을 닫는다. */
    @Test
    @DisplayName(
        "CU-12 유효한 토큰 + `consumes` 밖 `Content-Type` → **415**(422 아님) · `detail` 문자열 · `Accept` 가 계약에서 유도된다 (X-L2)",
    )
    fun `소비하지 않는 미디어 타입은 415 다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val consumed = ContractSpec.requestBodyMediaTypes(CONVERSION_ITEM_PATH, PUT)
        assertThat(consumed)
            .withFailMessage("계약이 이 오퍼레이션의 requestBody.content 를 비워 두었다 — 기대 Accept 를 유도할 수 없다")
            .isNotEmpty()
        assertThat(consumed).doesNotContain(FOREIGN_MEDIA_TYPE)

        val response =
            send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port${itemPath(conversionId.toString())}"))
                    .header(AUTHORIZATION, "Bearer $token")
                    .header(CONTENT_TYPE, FOREIGN_MEDIA_TYPE)
                    .PUT(bodyPublisher(reviewBody(VALID_REVIEW))),
            )

        assertDeclaredStatus(response, UNSUPPORTED_MEDIA_TYPE)
        assertThat(response.statusCode()).isNotEqualTo(UNPROCESSABLE)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("415 의 detail 이 문자열이 아니다")
            .isInstanceOf(String::class.java)
        // 값을 코드에 적지 않고 **계약의 requestBody.content 키 집합에서 유도한다.**
        assertThat(response.headers().allValues(ACCEPT).flatMap { it.split(",").map(String::trim) })
            .withFailMessage(
                "415 의 Accept 헤더가 계약의 requestBody.content 키 집합에서 유도한 값과 다르다: %s ↔ %s",
                response.headers().allValues(ACCEPT),
                consumed,
            ).containsExactlyInAnyOrderElementsOf(consumed)
    }

    @Test
    @DisplayName("초안 암호문이 **바이트 그대로**다 — 행 세대가 이미 쓰기 세대면 되쓰기도 같은 값이어야 한다")
    fun `초안 암호문이 바뀌지 않는다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val before = ciphertextOf(conversionId, EASY_TEXT_COLUMN)

        review(token, conversionId, EDITED_BODY)

        assertThat(ciphertextOf(conversionId, EASY_TEXT_COLUMN))
            .withFailMessage("검수 저장이 초안 암호문을 바꿨다 — 평문이 같아도 그 열을 건드릴 이유가 없다")
            .isEqualTo(before)
        assertThat(ciphertextOf(conversionId, EDITED_TEXT_COLUMN))
            .withFailMessage("수정본 암호문이 비어 있다 — 저장이 실제로 일어나지 않았다")
            .isNotNull()
    }

    @Test
    @DisplayName("검수 시각이 **DB 시계**로 찍힌다 — 애플리케이션 시계는 프로세스마다 어긋난다")
    fun `검수 시각이 DB 시계다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token)
        val before = now()

        review(token, conversionId, EDITED_BODY)

        val reviewedAt =
            database
                .queryFirstColumn("SELECT reviewed_at FROM conversions WHERE id = '$conversionId'")
                .first()
        val after = now()
        val stamped = java.time.OffsetDateTime.parse(reviewedAt.replace(" ", "T").replace("+00", "+00:00"))
        assertThat(stamped)
            .withFailMessage("검수 시각이 DB 시계 구간 밖이다: %s ∉ [%s, %s]", stamped, before, after)
            .isBetween(before, after)
    }

    /** DB 시계 한 점. 시각으로 비교하려고 파싱한다. */
    private fun now(): java.time.OffsetDateTime =
        java.time.OffsetDateTime.parse(
            database.queryFirstColumn("SELECT to_char(now(), 'YYYY-MM-DD\"T\"HH24:MI:SS.USOF:00')").first(),
        )

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "conversionreview${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest(SIGNUP_PATH, null).POST(bodyPublisher(credentials)))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(jsonRequest(LOGIN_PATH, null).POST(bodyPublisher(credentials))))
            .getValue("access_token")
            .toString()
    }

    /** `(문서 id, 변환 id)`. **행은 제품이 쓴다.** */
    private fun createDocument(token: String): Pair<UUID, UUID> {
        val body = json.writeValueAsString(mapOf("text" to SOURCE_BODY))
        val response = send(jsonRequest(DOCUMENTS_PATH, token).POST(bodyPublisher(body)))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val parsed = bodyOf(response)
        return UUID.fromString(parsed.getValue("document_id").toString()) to
            UUID.fromString(parsed.getValue("conversion_id").toString())
    }

    /** 완료 상태로 만든다 — 워커가 할 일을 SQL 로 대신한다. */
    private fun doneConversion(token: String): Pair<UUID, UUID> {
        val ids = createDocument(token)
        val sealed = cipher.encrypt(PlainBody(STORED_DRAFT), ids.second, EncryptedField.CONVERSION_EASY_TEXT)
        database.execute(
            MARK_DONE_SQL.format(
                sealed.bytes.joinToString("") { "%02x".format(it) },
                cipher.writeScheme,
                cipher.writeKeyVersion,
                ids.second,
            ),
        )
        return ids
    }

    /** 상태만 바꾼다. */
    private fun forceStatus(
        conversionId: UUID,
        status: String,
    ) {
        database.execute("UPDATE conversions SET status = '$status' WHERE id = '$conversionId'")
    }

    private fun ciphertextOf(
        conversionId: UUID,
        column: String,
    ): String? =
        database
            .queryFirstColumn("SELECT encode($column, 'hex') FROM conversions WHERE id = '$conversionId'")
            .firstOrNull()

    private fun review(
        token: String?,
        conversionId: UUID,
        text: String,
    ): HttpResponse<String> =
        send(jsonRequest(itemPath(conversionId.toString()), token).PUT(bodyPublisher(reviewBody(text))))

    private fun reviewBytes(
        token: String,
        conversionId: UUID,
        text: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            jsonRequest(itemPath(conversionId.toString()), token).PUT(bodyPublisher(reviewBody(text))).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun read(
        token: String,
        conversionId: UUID,
    ): HttpResponse<String> = send(jsonRequest(itemPath(conversionId.toString()), token).GET())

    private fun reviewBody(text: String): String = json.writeValueAsString(mapOf(EDITED_TEXT_PROPERTY to text))

    private fun bodyPublisher(body: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8))

    private fun jsonRequest(
        path: String,
        token: String?,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
        token?.let { builder.header(AUTHORIZATION, "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** P-21 — 경로 변수 이름을 계약에서 읽는다. */
    private fun itemPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, PUT).name}}",
            conversionId,
        )

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("PUT %s 가 %d 이 아니다: %s", CONVERSION_ITEM_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(CONVERSION_ITEM_PATH, PUT))
            .withFailMessage("계약이 PUT %s 에 %d 를 선언하지 않는다", CONVERSION_ITEM_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    companion object {
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"
        private const val DOCUMENTS_PATH = "/documents"
        private const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        private const val POST = "post"
        private const val PUT = "put"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val UNSUPPORTED_MEDIA_TYPE = 415
        private const val UNPROCESSABLE = 422

        private const val CONVERSION_SCHEMA = "ConversionResponse"
        private const val STATUS_SCHEMA = "ConversionStatus"
        private const val EDITED_TEXT_FIELD = "ConversionReviewRequest.edited_text"

        private const val EASY_TEXT_PROPERTY = "easy_text"
        private const val EDITED_TEXT_PROPERTY = "edited_text"
        private const val REVIEWED_AT_PROPERTY = "reviewed_at"
        private const val DETAIL = "detail"

        private const val EASY_TEXT_COLUMN = "easy_text_encrypted"
        private const val EDITED_TEXT_COLUMN = "edited_text_encrypted"

        /** 계약 예시 좌표 — 이름이지 값이 아니다. */
        private const val NOT_DONE_EXAMPLE = "not_done"
        private const val EMPTY_EXAMPLE = "empty"
        private const val TOO_LONG_EXAMPLE = "too_long"

        private const val DONE_STATUS = "done"
        private const val PENDING_STATUS = "pending"

        private const val AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE = "Content-Type"
        private const val ACCEPT = "Accept"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 이 오퍼레이션이 소비하지 않는 미디어 타입 — 그 사실도 계약과 대조한다. */
        private const val FOREIGN_MEDIA_TYPE = "text/plain"

        private const val SOURCE_BODY = "검수 저장 대상 안내문 본문"
        private const val STORED_DRAFT = "쉬운 글 초안입니다."
        private const val EDITED_BODY = "담당자가 다듬은 문장입니다."
        private const val VALID_REVIEW = "정상 수정본입니다."
        private const val VALID_PASSWORD = "correct horse battery"

        /** 정규화가 걷어내는 문자. */
        private const val CONTROL_CHAR = "\u0001"
        private const val CONTROL_ONLY_LENGTH = 5
        private const val DIVERGENCE_NOISE = 5

        /** 채움 문자. BMP 라 코드 포인트 수와 길이가 같다. */
        private const val FILLER_CHAR = "가"

        /** 소스에 날 서로게이트를 싣지 않으려는 이스케이프. */
        private const val SURROGATE_ESCAPE = "\\ud800"

        /**
         * 결과 열을 채워 완료 상태로 만든다. **봉투 두 값을 같은 문장에서 함께 SET 한다** —
         * `EnvelopeColumnWriteGuardTest` 의 규약이다. `%s` 자리는 [doneConversion] 이 채운다.
         */
        val MARK_DONE_SQL =
            """
            UPDATE conversions
            SET status = 'done',
                easy_text_encrypted = decode('%s', 'hex'),
                encryption_scheme = '%s',
                key_version = %s
            WHERE id = '%s'
            """.trimIndent()

        private var counter = 0

        /** 이 테스트만 쓰는 DB — 상태를 SQL 로 바꾸므로 섞이면 안 된다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_review") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. */
const val CONVERSION_REVIEW_TEST_SECRET: String = "conversion-review-test-signing-key-0123456789"

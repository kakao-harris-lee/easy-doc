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

/**
 * 보존 만료 창의 실측 — **내용을 내주는 경로가 만료된 문서를 내주지 않는다.**
 *
 * 창이 실재하는 근거: 파기는 `RetentionPurgeScheduler` 가 **하루 한 번** 도는 배치라
 * 만료 시각과 실제 파기 사이가 **최대 24시간**이다. 그 사이 이 경로들이 무엇을 내주는지가
 * 이 파일이 재는 것이고, 술어(`retention_expires_at > now()`)는 파기 배치의 `<= now()` 와
 * **정확한 여집합**이라 겹치지도 벌어지지도 않는다.
 *
 * 세 오퍼레이션에 **같은 경계 셋**을 건다 — 지난 것 · 정각 · 아직 남은 것.
 * `GET /documents/{document_id}/source` 의 같은 경계는 `DocumentSourceReachTest` 가 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$RETENTION_GUARD_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionReadGuardReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    // ======================================================= GET /conversions/{id}

    @Test
    @DisplayName("RG-1 만료된 문서의 변환 조회는 404 다 — 파기 배치를 기다리지 않는다")
    fun `만료된 변환 조회는 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)

        val response = read(fixture.token, fixture.conversionId)

        assertDeclaredStatus(response, CONVERSION_ITEM_PATH, GET, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(CONVERSION_ITEM_PATH, GET, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    /** 이 변경의 핵심이다 — 거절 본문이 가려졌던 값을 흘리면 술어가 아무것도 막지 못한 것이다. */
    @Test
    @DisplayName("RG-1 만료 404 본문에 **`masked_items` 조각이 하나도 없다** — 실제 개인정보·자리표시자·키 이름 전부")
    fun `만료 404 가 마스킹 대응표를 흘리지 않는다`() {
        val fixture = completedConversion()
        // 만료 전에는 실제로 실린다 — 그래야 아래 부재가 「원래 없었다」가 아니게 된다.
        assertThat(maskedOriginals(read(fixture.token, fixture.conversionId)))
            .describedAs("만료 전 조회에 원값이 없으면 이 케이스의 전제가 깨진다")
            .containsExactly(HIDDEN_RRN)

        expireIn(fixture.documentId, PAST)

        val refused = read(fixture.token, fixture.conversionId).body()

        assertThat(refused).doesNotContain(HIDDEN_RRN)
        assertThat(refused).doesNotContain(PLACEHOLDER)
        assertThat(refused).doesNotContain(MASKED_ITEMS_PROPERTY)
        assertThat(refused).doesNotContain(STORED_DRAFT)
    }

    @Test
    @DisplayName("RG-1 만료 **정각**은 404 다 — 파기 배치 술어(`<= now()`)와 정확한 여집합이다")
    fun `조회의 만료 정각은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, NOW)

        assertDeclaredStatus(read(fixture.token, fixture.conversionId), CONVERSION_ITEM_PATH, GET, NOT_FOUND)
        assertThat(purgeCandidates(fixture.documentId))
            .describedAs("조회가 접은 행을 파기 배치가 대상으로 보지 않으면 둘 사이에 틈이 남는다")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("RG-1 **만료 직전 문서는 200 이다** — 술어가 산 문서까지 접지 않는다")
    fun `만료 직전 변환 조회는 200 이다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, ALMOST_EXPIRED)

        val response = read(fixture.token, fixture.conversionId)

        assertDeclaredStatus(
            response,
            CONVERSION_ITEM_PATH,
            GET,
            ContractSpec.successStatus(CONVERSION_ITEM_PATH, GET),
        )
        assertThat(maskedOriginals(response)).containsExactly(HIDDEN_RRN)
    }

    @Test
    @DisplayName("RG-1 만료 404 가 **없는 변환과 구분되지 않는다** — 만료가 존재를 드러내지 않는다")
    fun `만료와 없음이 구분되지 않는다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)

        OwnershipConcealment.assertIndistinguishable(
            "GET $CONVERSION_ITEM_PATH",
            readBytes(fixture.token, UUID.randomUUID()),
            readBytes(fixture.token, fixture.conversionId),
        )
    }

    // ================================================ GET /conversions/{id}/export

    @Test
    @DisplayName("RG-2 만료된 문서의 내보내기는 404 다 — 자리표시자가 복원된 최종본이 나가지 않는다")
    fun `만료된 변환 내보내기는 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)

        val response = export(fixture.token, fixture.conversionId)

        assertDeclaredStatus(response, CONVERSION_EXPORT_PATH, GET, NOT_FOUND)
        assertThat(response.body()).doesNotContain(HIDDEN_RRN)
    }

    @Test
    @DisplayName("RG-2 내보내기의 만료 **정각**은 404 다")
    fun `내보내기의 만료 정각은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, NOW)

        assertDeclaredStatus(export(fixture.token, fixture.conversionId), CONVERSION_EXPORT_PATH, GET, NOT_FOUND)
    }

    @Test
    @DisplayName("RG-2 **만료 직전 문서는 파일이 나온다** — 조회와 같은 질의라 판정도 같다")
    fun `만료 직전 변환 내보내기는 200 이다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, ALMOST_EXPIRED)

        val response = export(fixture.token, fixture.conversionId)

        assertDeclaredStatus(
            response,
            CONVERSION_EXPORT_PATH,
            GET,
            ContractSpec.successStatus(CONVERSION_EXPORT_PATH, GET),
        )
        assertThat(response.body()).contains(STORED_EDITED)
    }

    // ======================================================= PUT /conversions/{id}

    @Test
    @DisplayName("RG-3 만료된 문서에는 검수본을 저장할 수 없다 — **404 이고, 아무것도 쓰이지 않는다**")
    fun `만료된 변환의 검수 저장은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)
        val reviewedBefore = reviewedAt(fixture.conversionId)

        val response = saveReview(fixture.token, fixture.conversionId, LATE_REVIEW)

        assertDeclaredStatus(response, CONVERSION_ITEM_PATH, PUT, NOT_FOUND)
        assertThat(editedTextBytes(fixture.conversionId))
            .describedAs("파기 대상 문서에 새 검수본이 쓰이면 다음 배치가 방금 쓴 내용을 지운다")
            .isEqualTo(fixture.editedBytes)
        assertThat(reviewedAt(fixture.conversionId))
            .describedAs("`reviewed_at` 이 움직였다면 UPDATE 가 행에 닿았다는 뜻이다")
            .isEqualTo(reviewedBefore)
    }

    @Test
    @DisplayName("RG-3 검수 저장의 만료 **정각**은 404 다")
    fun `검수 저장의 만료 정각은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, NOW)

        val response = saveReview(fixture.token, fixture.conversionId, LATE_REVIEW)

        assertDeclaredStatus(response, CONVERSION_ITEM_PATH, PUT, NOT_FOUND)
        assertThat(editedTextBytes(fixture.conversionId)).isEqualTo(fixture.editedBytes)
    }

    @Test
    @DisplayName("RG-3 **만료 직전 문서는 정상 저장된다** — 저장 응답이 방금 쓴 검수본을 싣는다")
    fun `만료 직전 변환의 검수 저장은 200 이다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, ALMOST_EXPIRED)

        val response = saveReview(fixture.token, fixture.conversionId, LATE_REVIEW)

        assertDeclaredStatus(
            response,
            CONVERSION_ITEM_PATH,
            PUT,
            ContractSpec.successStatus(CONVERSION_ITEM_PATH, PUT),
        )
        assertThat(bodyOf(response)[EDITED_TEXT_PROPERTY]).isEqualTo(LATE_REVIEW)
        assertThat(editedTextBytes(fixture.conversionId))
            .describedAs("응답만 바뀌고 행이 그대로면 저장이 되지 않은 것이다")
            .isNotEqualTo(fixture.editedBytes)
    }

    // ============================================== PUT /conversions/{id}/feedback

    @Test
    @DisplayName("RG-5 만료된 문서의 변환에는 **피드백을 낼 수 없다** — 404 이고 행이 생기지 않는다")
    fun `만료된 변환의 피드백 저장은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)

        val response = saveFeedback(fixture.token, fixture.conversionId)

        assertDeclaredStatus(response, CONVERSION_FEEDBACK_PATH, PUT, NOT_FOUND)
        assertThat(feedbackRows(fixture.conversionId))
            .describedAs(
                "이 저장은 수정률 지표를 계산하려고 **복호화된 본문**을 읽는다 — 만료 뒤에 열려 있으면 " +
                    "「30일 뒤 자동 삭제」 뒤에도 본문 복호화가 계속 열린 것이다",
            ).isZero()
    }

    @Test
    @DisplayName("RG-5 피드백 저장의 만료 **정각**도 404 다")
    fun `피드백 저장의 만료 정각은 404 다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, NOW)

        val response = saveFeedback(fixture.token, fixture.conversionId)

        assertDeclaredStatus(response, CONVERSION_FEEDBACK_PATH, PUT, NOT_FOUND)
        assertThat(feedbackRows(fixture.conversionId)).isZero()
    }

    @Test
    @DisplayName("RG-5 **만료 직전에는 정상 저장된다** — 술어가 산 문서까지 접지 않는다")
    fun `만료 직전 변환의 피드백 저장은 200 이다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, ALMOST_EXPIRED)

        val response = saveFeedback(fixture.token, fixture.conversionId)

        assertDeclaredStatus(
            response,
            CONVERSION_FEEDBACK_PATH,
            PUT,
            ContractSpec.successStatus(CONVERSION_FEEDBACK_PATH, PUT),
        )
        assertThat(feedbackRows(fixture.conversionId)).isEqualTo(1)
    }

    @Test
    @DisplayName("RG-5 **이미 낸 의견은 만료 뒤에도 남는다** — 닫히는 것은 새 제출뿐이다")
    fun `이미 낸 의견은 만료 뒤에도 남는다`() {
        val fixture = completedConversion()
        val stored = saveFeedback(fixture.token, fixture.conversionId).statusCode()
        check(stored == ContractSpec.successStatus(CONVERSION_FEEDBACK_PATH, PUT)) { "배경 피드백 저장이 실패했다" }

        expireIn(fixture.documentId, PAST)

        assertThat(feedbackRows(fixture.conversionId))
            .describedAs(
                "`conversion_feedback` 은 파기 사슬 밖이다(FK 없음) — 그 분리는 「이미 낸 의견이 남는다」는 " +
                    "뜻이고, 이 케이스가 계약 문구의 그 절반을 잰다",
            ).isEqualTo(1)
    }

    // =========================================================== 닫지 않은 자리

    @Test
    @DisplayName("RG-4 **목록은 만료된 문서를 계속 보여 준다** — 의도적으로 남긴 비대칭이다(사용자 결정)")
    fun `목록은 만료된 문서를 감추지 않는다`() {
        val fixture = completedConversion()
        expireIn(fixture.documentId, PAST)

        val items = bodyOf(listDocuments(fixture.token))[ITEMS_PROPERTY] as List<*>

        assertThat(items.map { (it as Map<*, *>)[ID_PROPERTY] })
            .describedAs(
                "목록에서까지 소리 없이 사라지면 사용자는 문서가 왜 없어졌는지 알 수 없다 — " +
                    "목록은 제목만 싣고, 파기 사실을 알아차리는 자리가 목록이다",
            ).contains(fixture.documentId.toString())
    }

    // ================================================================ 배경 세우기

    /** 완료된 변환 하나 — 마스킹 대응표까지 채워 **조회가 실제로 개인정보를 싣는** 상태다. */
    private fun completedConversion(): Fixture {
        val token = newAccount()
        val (documentId, conversionId) = createDocument(token)
        val edited = cipher.encrypt(PlainBody(STORED_EDITED), conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
        val table = codec.encode(listOf(MaskedItem(MaskCategory.RRN, PLACEHOLDER, Secret(HIDDEN_RRN))))
        database.execute(
            MARK_DONE_SQL.format(
                hex(cipher.encrypt(PlainBody(STORED_DRAFT), conversionId, EncryptedField.CONVERSION_EASY_TEXT).bytes),
                hex(edited.bytes),
                hex(cipher.encrypt(table, conversionId, EncryptedField.CONVERSION_MASKED_ITEMS).bytes),
                cipher.writeScheme,
                cipher.writeKeyVersion,
                conversionId,
            ),
        )
        return Fixture(token, documentId, conversionId, hex(edited.bytes))
    }

    /** 한 케이스가 쓰는 배경 전부. `editedBytes` 는 「쓰이지 않았다」를 재는 기준선이다. */
    private class Fixture(
        val token: String,
        val documentId: UUID,
        val conversionId: UUID,
        val editedBytes: String,
    )

    /**
     * 보존 만료 시각을 옮긴다 — 실물에서는 30일이 흐른 상태다. 30일을 기다릴 수 없으니 시계
     * 대신 **행을 민다.** 판정은 어차피 `now()` 와 그 열의 비교라 어느 쪽을 움직여도 같다.
     */
    private fun expireIn(
        documentId: UUID,
        offset: String,
    ) {
        database.execute(EXPIRE_SQL.format(offset, documentId))
    }

    /** 파기 배치가 이 행을 대상으로 보는가 — `JdbcExpiredDocumentPurge` 와 같은 술어다. */
    private fun purgeCandidates(documentId: UUID): Int = database.queryInt(PURGE_CANDIDATE_SQL.format(documentId))

    /** 그 변환에 저장된 피드백 행 수. 「행이 생기지 않았다」·「남아 있다」를 함께 잰다. */
    private fun feedbackRows(conversionId: UUID): Int = database.queryInt(FEEDBACK_ROWS_SQL.format(conversionId))

    private fun editedTextBytes(conversionId: UUID): String =
        database.queryFirstColumn(EDITED_TEXT_SQL.format(conversionId)).single()

    private fun reviewedAt(conversionId: UUID): String =
        database.queryFirstColumn(REVIEWED_AT_SQL.format(conversionId)).single()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "retentionguard${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf(EMAIL_PROPERTY to email, PASSWORD_PROPERTY to VALID_PASSWORD))
        send(post(null, credentials, SIGNUP_PATH))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(post(null, credentials, LOGIN_PATH))).required(ACCESS_TOKEN_PROPERTY).toString()
    }

    private fun createDocument(token: String): Pair<UUID, UUID> {
        val response = send(post(token, json.writeValueAsString(mapOf(TEXT_PROPERTY to SAMPLE_TEXT)), DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val body = bodyOf(response)
        return UUID.fromString(body.required(DOCUMENT_ID_PROPERTY).toString()) to
            UUID.fromString(body.required(CONVERSION_ID_PROPERTY).toString())
    }

    private fun read(
        token: String,
        conversionId: UUID,
    ): HttpResponse<String> = send(get(token, itemPath(CONVERSION_ITEM_PATH, GET, conversionId.toString())))

    /** 같은 요청을 바이트로 받는다 — 소유권 은닉만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun readBytes(
        token: String,
        conversionId: UUID,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            get(token, itemPath(CONVERSION_ITEM_PATH, GET, conversionId.toString())).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /**
     * **`format` 을 붙이지 않는다** — 서버가 원본에서 정하는 것이 기본 경로이고, 이 파일이
     * 재는 것은 형식이 아니라 만료다(계약 `x-export-format-derivation.enforcement`).
     */
    private fun export(
        token: String,
        conversionId: UUID,
    ): HttpResponse<String> = send(get(token, itemPath(CONVERSION_EXPORT_PATH, GET, conversionId.toString())))

    private fun saveReview(
        token: String,
        conversionId: UUID,
        text: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port${reviewPath(conversionId)}"))
                .header(AUTHORIZATION, "Bearer $token")
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(mapOf(EDITED_TEXT_PROPERTY to text)),
                        Charsets.UTF_8,
                    ),
                ),
        )

    private fun reviewPath(conversionId: UUID): String = itemPath(CONVERSION_ITEM_PATH, PUT, conversionId.toString())

    /** 척도 둘은 이 파일이 재지 않는 배경 값이다 — 배포 의향만 계약에서 읽는다. */
    private fun saveFeedback(
        token: String,
        conversionId: UUID,
    ): HttpResponse<String> {
        val body =
            json.writeValueAsString(
                mapOf(
                    PUBLISH_INTENT_PROPERTY to ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA).first(),
                    QUALITY_SCORE_PROPERTY to SAMPLE_QUALITY_SCORE,
                    MINUTES_SPENT_PROPERTY to SAMPLE_MINUTES_SPENT,
                ),
            )
        val path = itemPath(CONVERSION_FEEDBACK_PATH, PUT, conversionId.toString())
        return send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(AUTHORIZATION, "Bearer $token")
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                .PUT(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)),
        )
    }

    private fun listDocuments(token: String): HttpResponse<String> = send(get(token, DOCUMENTS_PATH))

    private fun get(
        token: String,
        path: String,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .header(AUTHORIZATION, "Bearer $token")
            .GET()

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
        token?.let { builder.header(AUTHORIZATION, "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(
        template: String,
        method: String,
        value: String,
    ): String = template.replace("{${ContractSpec.pathVariable(template, method).name}}", value)

    // ================================================================== 단언 보조

    /** 응답의 `masked_items[].original` 목록. 없으면 빈 목록이다. */
    private fun maskedOriginals(response: HttpResponse<String>): List<String> {
        val items = bodyOf(response)[MASKED_ITEMS_PROPERTY] as? List<*> ?: return emptyList()
        return items.map { (it as Map<*, *>)[ORIGINAL_PROPERTY].toString() }
    }

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        path: String,
        method: String,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("%s %s 가 %d 이 아니다: %s", method.uppercase(), path, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다", method.uppercase(), path, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"
        private const val DOCUMENTS_PATH = "/documents"
        private const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        private const val CONVERSION_EXPORT_PATH = "/conversions/{conversion_id}/export"
        private const val CONVERSION_FEEDBACK_PATH = "/conversions/{conversion_id}/feedback"

        private const val GET = "get"
        private const val POST = "post"
        private const val PUT = "put"

        private const val NOT_FOUND = 404

        private const val DETAIL = "detail"
        private const val ITEMS_PROPERTY = "items"
        private const val ID_PROPERTY = "id"
        private const val DOCUMENT_ID_PROPERTY = "document_id"
        private const val CONVERSION_ID_PROPERTY = "conversion_id"
        private const val MASKED_ITEMS_PROPERTY = "masked_items"
        private const val ORIGINAL_PROPERTY = "original"
        private const val EDITED_TEXT_PROPERTY = "edited_text"
        private const val EMAIL_PROPERTY = "email"
        private const val PASSWORD_PROPERTY = "password"
        private const val ACCESS_TOKEN_PROPERTY = "access_token"
        private const val TEXT_PROPERTY = "text"
        private const val PUBLISH_INTENT_PROPERTY = "publish_intent"
        private const val QUALITY_SCORE_PROPERTY = "quality_score"
        private const val MINUTES_SPENT_PROPERTY = "minutes_spent"
        private const val PUBLISH_INTENT_SCHEMA = "PublishIntent"

        /** 척도 둘의 배경 값. 범위의 정본은 `core/pilot/ConversionFeedback.kt` 다. */
        private const val SAMPLE_QUALITY_SCORE = 4
        private const val SAMPLE_MINUTES_SPENT = 12

        /** 계약이 이 경로 404 의 인라인 예시에 붙인 이름. */
        private const val NOT_FOUND_EXAMPLE = "not_found"

        private const val AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val VALID_PASSWORD = "correct horse battery"

        /**
         * 보존 만료 시각을 옮길 자리 셋. **`NOW` 가 경계 자신이다** — 조회 술어가
         * `retention_expires_at > now()` 라 정각은 지난 것으로 접히고, 그것이 파기 배치의
         * `<= now()` 와 겹치지도 벌어지지도 않는 지점이다.
         */
        private const val PAST = "now() - interval '1 second'"
        private const val NOW = "now()"

        /** 「아직 남았다」의 가장 가까운 자리. 초 단위로 잡으면 요청이 도는 사이에 지난다. */
        private const val ALMOST_EXPIRED = "now() + interval '1 minute'"

        private const val SAMPLE_TEXT = "보존 만료 경계를 재는 안내문 본문"

        /** 저장된 결과. 만료 뒤에는 이 중 어느 것도 응답에 나타나면 안 된다. */
        private const val STORED_DRAFT = "만료 창에서 새어 나오면 안 되는 초안입니다."
        private const val STORED_EDITED = "만료 창에서 새어 나오면 안 되는 검수본입니다."
        private const val LATE_REVIEW = "만료 뒤에 저장을 시도한 검수본입니다."

        /** 합성 주민등록번호. 조회가 `masked_items[].original` 로 **평문으로** 돌려주는 값이다. */
        private const val HIDDEN_RRN = "900101-1234567"
        private const val PLACEHOLDER = "[[주민등록번호1]]"

        private val codec = MaskedItemCodec()

        /**
         * 결과 열을 채우는 UPDATE. **봉투 두 값을 같은 문장에서 함께 SET 한다** —
         * `EnvelopeColumnWriteGuardTest` 의 규약이다.
         *
         * SQL 을 companion 의 상수 리터럴에 두고 `%s` 자리만 채우는 것도 같은 규약이다
         * (`ConversionReadReachTest.MARK_DONE_SQL` 과 같은 사유) — 호출부에서 조립하면
         * 스캐너와 쓰기 가드가 함께 눈을 감는다.
         */
        private val MARK_DONE_SQL =
            """
            UPDATE conversions
            SET status = 'done',
                easy_text_encrypted = decode('%s', 'hex'),
                edited_text_encrypted = decode('%s', 'hex'),
                masked_items_encrypted = decode('%s', 'hex'),
                encryption_scheme = '%s',
                key_version = %s,
                missing_placeholders = '[]'::jsonb,
                reviewed_at = now(),
                model = 'retention-guard-model',
                provider_name = 'retention-guard-provider',
                input_tokens = 11,
                output_tokens = 22
            WHERE id = '%s'
            """.trimIndent()

        /** 보존 만료 시각을 미는 UPDATE. 첫 `%s` 는 [PAST]·[NOW]·[ALMOST_EXPIRED] 중 하나다. */
        private const val EXPIRE_SQL = "UPDATE documents SET retention_expires_at = %s WHERE id = '%s'"

        private const val PURGE_CANDIDATE_SQL =
            "SELECT count(*) FROM documents WHERE id = '%s' AND retention_expires_at <= now()"

        private const val EDITED_TEXT_SQL =
            "SELECT coalesce(encode(edited_text_encrypted, 'hex'), '') FROM conversions WHERE id = '%s'"

        private const val REVIEWED_AT_SQL = "SELECT coalesce(reviewed_at::text, '') FROM conversions WHERE id = '%s'"

        private const val FEEDBACK_ROWS_SQL = "SELECT count(*) FROM conversion_feedback WHERE conversion_id = '%s'"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 상태를 SQL 로 바꾸므로 다른 테스트의 행과 섞이면 안 된다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("retention_guard") }

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
const val RETENTION_GUARD_TEST_SECRET: String = "retention-read-guard-test-signing-key-0123456789"

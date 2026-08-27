package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kr.easydoc.api.support.CanaryProbe
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.POSITIVE_CONTROL_MARKER
import kr.easydoc.api.support.RETRO_CONTROL_MARKER
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
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
import java.time.Instant
import java.util.UUID

/**
 * `PUT /conversions/{conversion_id}/feedback` 실측 계약 — 실제 HTTP·DB 경로로 잰다.
 *
 * **범위 밖 값의 갈래는 모양과 문구를 함께 잰다.** 422 이고 `detail` 이 배열이 아니라
 * 문자열인 것에 더해, 그 문자열이 계약의 422 예시(`score_out_of_range`·`minutes_out_of_range`)와
 * 같은지까지 [ContractSpec.pathExampleDetail] 로 대조한다 — 자유 의견 길이 초과 케이스가 쓰는
 * 방식과 같다. 계약이 문구의 정본이고 `core/pilot/ConversionFeedback.kt` 의 거부 문구가 그것을
 * 따른다. 목록 밖 `publish_intent` 갈래만 계약에 대응 예시가 없어 모양까지만 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$CONVERSION_FEEDBACK_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionFeedbackReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("완료된 내 변환에 유효한 피드백 → 200 · 사적 헤더 2종(값·**개수**) · 응답 키 집합이 계약 required 와 같다")
    fun `피드백 저장이 계약대로 왕복한다`() {
        val token = newAccount()
        val conversionId = doneConversion(token)

        val response = submit(token, conversionId, feedbackBody(comment = COMMENT_BODY))

        assertDeclaredStatus(response, ContractSpec.successStatus(FEEDBACK_PATH, PUT))
        val body = bodyOf(response)
        assertThat(body.keys.map { it.toString() }.toSet())
            .withFailMessage("피드백 응답의 키 집합이 계약 %s 와 다르다", FEEDBACK_RESPONSE_SCHEMA)
            .isEqualTo(ContractSpec.schemaRequired(FEEDBACK_RESPONSE_SCHEMA))

        assertThat(body[CONVERSION_ID_PROPERTY]).isEqualTo(conversionId.toString())
        assertThat(body[PUBLISH_INTENT_PROPERTY]).isEqualTo(firstIntent())
        assertThat(body[QUALITY_SCORE_PROPERTY]).isEqualTo(VALID_QUALITY_SCORE)
        assertThat(body[MINUTES_SPENT_PROPERTY]).isEqualTo(VALID_MINUTES_SPENT)
        assertThat(body[COMMENT_PROPERTY])
            .withFailMessage("자유 의견이 저장된 값 그대로 돌아오지 않았다")
            .isEqualTo(COMMENT_BODY)
        assertThat(body[SUBMITTED_AT_PROPERTY]).isNotNull()

        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("피드백 응답에 %s 가 없거나 개수가 다르다: %s", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    @Test
    @DisplayName("자유 의견은 **봉인해** 저장한다 — 평문이 열에 남지 않고 봉투 세 열이 함께 선다")
    fun `자유 의견이 봉인돼 저장된다`() {
        val token = newAccount()
        val conversionId = doneConversion(token)

        submit(token, conversionId, feedbackBody(comment = COMMENT_BODY))

        assertThat(commentCiphertextOf(conversionId))
            .withFailMessage("자유 의견 암호문이 비어 있다 — 저장이 실제로 일어나지 않았다")
            .isNotNull()
        assertThat(envelopeColumnsOf(conversionId))
            .withFailMessage("봉투 두 열이 암호문과 함께 서지 않았다 — 스키마의 짝 제약이 뜻하는 상태다")
            .doesNotContainNull()
        assertThat(database.queryFirstColumn(plaintextProbeSql(conversionId)).first())
            .withFailMessage("자유 의견 평문이 열에 그대로 남아 있다")
            .isEqualTo("f")
    }

    @Test
    @DisplayName("의견을 주지 않으면 `comment` 는 **키는 있고 값이 null** 이고 봉투 세 열이 함께 비어 있다")
    fun `의견 없는 제출은 봉투가 통째로 비어 있다`() {
        val token = newAccount()
        val conversionId = doneConversion(token)

        val body = bodyOf(submit(token, conversionId, feedbackBody(comment = null)))

        assertThat(body.keys.map { it.toString() })
            .withFailMessage("의견이 없을 때 `comment` 키가 응답에서 빠졌다 — React 가 undefined 를 받는다")
            .contains(COMMENT_PROPERTY)
        assertThat(body[COMMENT_PROPERTY]).isNull()
        assertThat(commentCiphertextOf(conversionId)).isNull()
        assertThat(envelopeColumnsOf(conversionId)).containsOnlyNulls()
    }

    @Test
    @DisplayName("**재제출은 멱등이다** — 두 번 보내도 행은 하나이고 값은 마지막 것, `submitted_at` 은 갱신된다")
    fun `재제출이 행을 늘리지 않는다`() {
        val token = newAccount()
        val conversionId = doneConversion(token)
        val intents = ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA)
        assertThat(intents).describedAs("계약의 배포 의향 값이 둘 미만이다 — 이 케이스가 성립하지 않는다").hasSizeGreaterThan(1)

        val first = bodyOf(submit(token, conversionId, feedbackBody(comment = COMMENT_BODY)))
        val second =
            bodyOf(
                submit(
                    token,
                    conversionId,
                    feedbackBody(intent = intents.last(), score = OTHER_QUALITY_SCORE, comment = null),
                ),
            )

        assertThat(database.queryInt(rowCountSql(conversionId)))
            .withFailMessage("재제출이 행을 늘렸다 — 게이트 ① 판정의 분모가 부풀고 그 오염은 집계 시점에 되돌릴 수 없다")
            .isEqualTo(1)
        assertThat(second[PUBLISH_INTENT_PROPERTY]).isEqualTo(intents.last())
        assertThat(second[QUALITY_SCORE_PROPERTY]).isEqualTo(OTHER_QUALITY_SCORE)
        assertThat(second[COMMENT_PROPERTY])
            .withFailMessage("덮어쓰기가 이전 자유 의견을 지우지 않았다 — 마지막 제출이 행의 전부여야 한다")
            .isNull()
        assertThat(commentCiphertextOf(conversionId)).isNull()
        assertThat(Instant.parse(second.getValue(SUBMITTED_AT_PROPERTY).toString()))
            .withFailMessage("덮어쓴 뒤 `submitted_at` 이 갱신되지 않았다 — 계약이 「마지막으로 저장한 시각」이라 적었다")
            .isAfter(Instant.parse(first.getValue(SUBMITTED_AT_PROPERTY).toString()))
    }

    @Test
    @DisplayName("낸 의견이 변환 조회와 문서 목록에 **제출 시각으로** 남는다 — `reviewed_at` 은 그대로 null")
    fun `피드백 제출 시각이 조회와 목록에 실린다`() {
        val token = newAccount()
        val withFeedback = doneConversion(token)
        val withoutFeedback = doneConversion(token)

        val submittedAt = bodyOf(submit(token, withFeedback, feedbackBody(comment = COMMENT_BODY)))
        val read = bodyOf(get(token, conversionPath(withFeedback.toString())))

        assertThat(read[FEEDBACK_SUBMITTED_AT_PROPERTY])
            .withFailMessage("의견을 냈는데 변환 조회가 그 사실을 모른다 — 새로고침한 검수 화면이 「검수 필요」로 되돌아간다")
            .isEqualTo(submittedAt[SUBMITTED_AT_PROPERTY])
        assertThat(read[REVIEWED_AT_PROPERTY])
            .withFailMessage("피드백 제출이 `reviewed_at` 을 대신 찍었다 — 수정률 지표가 기대는 구분이 무너진다")
            .isNull()

        val items = (bodyOf(get(token, DOCUMENTS_PATH))[ITEMS_PROPERTY] as List<*>).map { it as Map<*, *> }
        val listed = items.associate { it[CONVERSION_ID_PROPERTY] to it[FEEDBACK_SUBMITTED_AT_PROPERTY] }

        assertThat(listed)
            .withFailMessage("의견을 내지 않은 문서가 목록에서 사라졌다 — 피드백 조인이 왼쪽 조인이 아니다")
            .containsKey(withoutFeedback.toString())
        assertThat(listed[withFeedback.toString()])
            .withFailMessage("목록이 제출 시각을 싣지 않는다 — 변환 기록이 「검수 완료」를 그릴 수 없다")
            .isEqualTo(submittedAt[SUBMITTED_AT_PROPERTY])
        assertThat(listed[withoutFeedback.toString()])
            .withFailMessage("의견을 내지 않은 문서에 제출 시각이 섰다")
            .isNull()
    }

    @Test
    @DisplayName("남의 변환에 딸린 피드백 제출 사실이 내 목록·조회로 새지 않는다")
    fun `남의 피드백 제출 사실이 새지 않는다`() {
        val stranger = newAccount()
        val theirs = doneConversion(stranger)
        submit(stranger, theirs, feedbackBody(comment = COMMENT_BODY))

        val mine = newAccount()

        assertThat(get(mine, conversionPath(theirs.toString())).statusCode())
            .withFailMessage("남의 변환 조회가 404 가 아니다")
            .isEqualTo(NOT_FOUND)
        assertThat((bodyOf(get(mine, DOCUMENTS_PATH))[ITEMS_PROPERTY] as List<*>))
            .withFailMessage("내 목록에 남의 문서가 실렸다")
            .isEmpty()
    }

    @Test
    @DisplayName("타인 소유 변환 → **부재 응답**이고 없는 것과 **응답이 구별되지 않는다**")
    fun `타인 변환 피드백은 404 이고 부재와 같다`() {
        val mine = newAccount()
        val theirs = doneConversion(newAccount())

        val others = submitBytes(mine, theirs)
        val absent = submitBytes(mine, UUID.randomUUID())

        assertThat(others.statusCode())
            .withFailMessage("타인 변환 피드백이 부재 응답이 아니다 — 거절을 가르면 남의 자원 존재를 확인해 준다")
            .isEqualTo(NOT_FOUND)
        assertThat(others.statusCode()).isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable("PUT $FEEDBACK_PATH", absent, others)
        assertThat(database.queryInt(rowCountSql(theirs)))
            .withFailMessage("404 를 냈는데 행이 저장됐다")
            .isZero()
    }

    @Test
    @DisplayName("아직 완료되지 않은 변환 → **409** · `detail` 이 계약 409 예시와 같다")
    fun `완료 전 피드백은 409 다`() {
        val token = newAccount()
        val beforeDone = ContractSpec.schemaEnum(STATUS_SCHEMA).filterNot { it == DONE_STATUS }
        assertThat(beforeDone).describedAs("완료 전 상태가 계약에 하나도 없다 — 이 케이스가 성립하지 않는다").isNotEmpty()

        beforeDone.forEach { status ->
            val conversionId = createDocument(token)
            if (status != PENDING_STATUS) forceStatus(conversionId, status)

            val response = submit(token, conversionId, feedbackBody(comment = null))

            assertDeclaredStatus(response, CONFLICT)
            assertThat(bodyOf(response)[DETAIL])
                .withFailMessage("상태 %s 의 409 detail 이 계약 예시와 다르다", status)
                .isEqualTo(ContractSpec.pathExampleDetail(FEEDBACK_PATH, PUT, CONFLICT, NOT_DONE_EXAMPLE))
        }
    }

    @Test
    @DisplayName("범위 밖 척도 값 → 422 · `detail` **문자열**(배열 아님)이고 계약 422 예시 문구와 같다 · 행이 남지 않는다")
    fun `범위 밖 값은 문자열 detail 로 거절된다`() {
        val token = newAccount()

        OUT_OF_RANGE_CASES.forEach { case ->
            val conversionId = doneConversion(token)

            val response = submit(token, conversionId, case.body)

            assertDeclaredStatus(response, UNPROCESSABLE)
            assertThat(bodyOf(response)[DETAIL])
                .withFailMessage("%s 의 detail 이 문자열이 아니다 — 스키마 층이 판정했다는 뜻이다", case.label)
                .isInstanceOf(String::class.java)
            case.contractExample?.let { example ->
                assertThat(bodyOf(response)[DETAIL])
                    .withFailMessage("%s 의 detail 이 계약 422 예시 `%s` 와 다르다", case.label, example)
                    .isEqualTo(ContractSpec.pathExampleDetail(FEEDBACK_PATH, PUT, UNPROCESSABLE, example))
            }
            assertThat(database.queryInt(rowCountSql(conversionId)))
                .withFailMessage("%s 를 거절했는데 행이 저장됐다", case.label)
                .isZero()
        }
    }

    @Test
    @DisplayName("자유 의견이 상한을 **넘으면** 422 문자열(계약 예시 문구) · **정확히 상한**이면 통과")
    fun `자유 의견 길이 경계가 양쪽으로 고정된다`() {
        val token = newAccount()
        val limit = ContractSpec.inputLimit(COMMENT_LIMIT_NAME)

        val exact = submit(token, doneConversion(token), feedbackBody(comment = FILLER_CHAR.repeat(limit)))
        assertDeclaredStatus(exact, ContractSpec.successStatus(FEEDBACK_PATH, PUT))

        val over = submit(token, doneConversion(token), feedbackBody(comment = FILLER_CHAR.repeat(limit + 1)))
        assertDeclaredStatus(over, UNPROCESSABLE)
        assertThat(bodyOf(over)[DETAIL])
            .withFailMessage("상한 초과의 detail 이 계약 예시와 다르다")
            .isEqualTo(ContractSpec.pathExampleDetail(FEEDBACK_PATH, PUT, UNPROCESSABLE, TOO_LONG_EXAMPLE))
    }

    @Test
    @DisplayName("자유 의견 카나리가 **강제 TRACE** 의 어느 로그에도 남지 않는다 (양성 대조 포함)")
    fun `자유 의견이 로그에 남지 않는다`() {
        val token = newAccount()
        val conversionId = doneConversion(token)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val probe = CanaryProbe(RETRO_CONTROL_MARKER)
        probe.addCanary(COMMENT_AXIS, COMMENT_CANARY)
        probe.stopRetaining()

        val detached: List<Appender<ILoggingEvent>> = root.iteratorForAppenders().asSequence().toList()
        val restoreLevel: Level? = root.level
        try {
            detached.forEach { root.detachAppender(it) }
            root.addAppender(probe)
            root.level = Level.TRACE

            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)

            val stored = submit(token, conversionId, feedbackBody(comment = "$COMMENT_CANARY 부분이 어색합니다"))
            check(stored.statusCode() == ContractSpec.successStatus(FEEDBACK_PATH, PUT)) {
                "카나리 제출이 저장되지 않았다: ${stored.statusCode()} ${stored.body()}"
            }
            // 거절 경로도 태운다 — 예외 메시지가 입력을 되싣는 구현이 여기서 걸린다.
            submit(token, conversionId, feedbackBody(comment = COMMENT_CANARY.repeat(LONG_COMMENT_REPEATS)))
        } finally {
            root.level = restoreLevel
            root.detachAppender(probe)
            detached.forEach { root.addAppender(it) }
            probe.stop()
        }

        assertThat(probe.sawPositiveControl())
            .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
            .isTrue()
        assertThat(probe.traceEvents())
            .withFailMessage("강제 TRACE 에서 TRACE 이벤트가 0건이다 — 루트 레벨 상향이 먹지 않았다")
            .isNotZero()
        assertThat(probe.hits())
            .withFailMessage("자유 의견이 로그에 남았다:%n%s", probe.report())
            .isEmpty()
    }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "conversionfeedback${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest(SIGNUP_PATH, null).POST(bodyPublisher(credentials)))
        return bodyOf(send(jsonRequest(LOGIN_PATH, null).POST(bodyPublisher(credentials))))
            .getValue("access_token")
            .toString()
    }

    /** 변환 id. **행은 제품이 쓴다.** */
    private fun createDocument(token: String): UUID {
        val body = json.writeValueAsString(mapOf("text" to SOURCE_BODY))
        val response = send(jsonRequest(DOCUMENTS_PATH, token).POST(bodyPublisher(body)))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        return UUID.fromString(bodyOf(response).getValue("conversion_id").toString())
    }

    /** 완료 상태로 만든다 — 워커가 할 일을 SQL 로 대신한다. */
    private fun doneConversion(token: String): UUID {
        val conversionId = createDocument(token)
        val sealed = cipher.encrypt(PlainBody(STORED_DRAFT), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        database.execute(
            MARK_DONE_SQL.format(
                sealed.bytes.joinToString("") { "%02x".format(it) },
                cipher.writeScheme,
                cipher.writeKeyVersion,
                conversionId,
            ),
        )
        return conversionId
    }

    private fun forceStatus(
        conversionId: UUID,
        status: String,
    ) {
        database.execute("UPDATE conversions SET status = '$status' WHERE id = '$conversionId'")
    }

    private fun submit(
        token: String?,
        conversionId: UUID,
        body: String,
    ): HttpResponse<String> = send(jsonRequest(feedbackPath(conversionId.toString()), token).PUT(bodyPublisher(body)))

    private fun get(
        token: String,
        path: String,
    ): HttpResponse<String> = send(jsonRequest(path, token).GET())

    private fun submitBytes(
        token: String,
        conversionId: UUID,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            jsonRequest(feedbackPath(conversionId.toString()), token)
                .PUT(bodyPublisher(feedbackBody(comment = null)))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** 배포 의향 값은 **계약에서 읽는다.** 척도 둘의 범위 정본은 `core/pilot` 이다. */
    private fun feedbackBody(
        intent: String = firstIntent(),
        score: Int = VALID_QUALITY_SCORE,
        minutes: Int = VALID_MINUTES_SPENT,
        comment: String?,
    ): String =
        json.writeValueAsString(
            mapOf(
                PUBLISH_INTENT_PROPERTY to intent,
                QUALITY_SCORE_PROPERTY to score,
                MINUTES_SPENT_PROPERTY to minutes,
                COMMENT_PROPERTY to comment,
            ),
        )

    private fun firstIntent(): String = ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA).first()

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
    private fun feedbackPath(conversionId: String): String =
        FEEDBACK_PATH.replace(
            "{${ContractSpec.pathVariable(FEEDBACK_PATH, PUT).name}}",
            conversionId,
        )

    /** 변환 조회 경로. 경로 변수 이름을 계약에서 읽는 것은 [feedbackPath] 와 같다. */
    private fun conversionPath(conversionId: String): String =
        CONVERSION_ITEM_PATH.replace(
            "{${ContractSpec.pathVariable(CONVERSION_ITEM_PATH, GET).name}}",
            conversionId,
        )

    // ================================================================ 저장 상태 조회

    private fun rowCountSql(conversionId: UUID): String =
        "SELECT count(*) FROM conversion_feedback WHERE conversion_id = '$conversionId'"

    /** 저장된 행의 한 열. 행이 없을 때와 값이 NULL 일 때가 같은 `null` 이라 호출부가 가른다. */
    private fun columnOf(
        expression: String,
        conversionId: UUID,
    ): String? =
        database
            .queryFirstColumn("SELECT $expression FROM conversion_feedback WHERE conversion_id = '$conversionId'")
            .firstOrNull()

    private fun commentCiphertextOf(conversionId: UUID): String? =
        columnOf("encode(comment_encrypted, 'hex')", conversionId)

    /** 봉투 두 열. 암호문과 **함께 있거나 함께 없다**(스키마의 짝 제약). */
    private fun envelopeColumnsOf(conversionId: UUID): List<String?> =
        listOf(columnOf("encryption_scheme", conversionId), columnOf("key_version", conversionId))

    /** 평문이 열에 남았는지 — 암호문 바이트를 텍스트로 훑는다. `t` 면 유출이다. */
    private fun plaintextProbeSql(conversionId: UUID): String =
        "SELECT coalesce(position('$COMMENT_BODY' in encode(comment_encrypted, 'escape')) > 0, false) " +
            "FROM conversion_feedback WHERE conversion_id = '$conversionId'"

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("PUT %s 가 %d 이 아니다: %s", FEEDBACK_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(FEEDBACK_PATH, PUT))
            .withFailMessage("계약이 PUT %s 에 %d 를 선언하지 않는다", FEEDBACK_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    companion object {
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"
        private const val DOCUMENTS_PATH = "/documents"
        private const val FEEDBACK_PATH = "/conversions/{conversion_id}/feedback"
        private const val CONVERSION_ITEM_PATH = "/conversions/{conversion_id}"
        private const val POST = "post"
        private const val PUT = "put"
        private const val GET = "get"

        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val UNPROCESSABLE = 422

        private const val FEEDBACK_RESPONSE_SCHEMA = "ConversionFeedbackResponse"
        private const val PUBLISH_INTENT_SCHEMA = "PublishIntent"
        private const val STATUS_SCHEMA = "ConversionStatus"
        private const val COMMENT_LIMIT_NAME = "max_feedback_comment_length"

        private const val CONVERSION_ID_PROPERTY = "conversion_id"
        private const val PUBLISH_INTENT_PROPERTY = "publish_intent"
        private const val QUALITY_SCORE_PROPERTY = "quality_score"
        private const val MINUTES_SPENT_PROPERTY = "minutes_spent"
        private const val COMMENT_PROPERTY = "comment"
        private const val SUBMITTED_AT_PROPERTY = "submitted_at"

        /** 조회·목록이 싣는 이름. 피드백 응답의 [SUBMITTED_AT_PROPERTY] 와 **같은 열**이다. */
        private const val FEEDBACK_SUBMITTED_AT_PROPERTY = "feedback_submitted_at"
        private const val REVIEWED_AT_PROPERTY = "reviewed_at"
        private const val ITEMS_PROPERTY = "items"
        private const val DETAIL = "detail"

        /** 계약 예시 좌표 — 이름이지 값이 아니다. */
        private const val NOT_DONE_EXAMPLE = "not_done"
        private const val TOO_LONG_EXAMPLE = "comment_too_long"
        private const val SCORE_OUT_OF_RANGE_EXAMPLE = "score_out_of_range"
        private const val MINUTES_OUT_OF_RANGE_EXAMPLE = "minutes_out_of_range"

        private const val DONE_STATUS = "done"
        private const val PENDING_STATUS = "pending"

        private const val AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"

        private const val SOURCE_BODY = "피드백 대상 안내문 본문"
        private const val STORED_DRAFT = "쉬운 글 초안입니다."
        private const val COMMENT_BODY = "문장이 길어 두 문장으로 나누면 좋겠습니다"
        private const val VALID_PASSWORD = "correct horse battery"

        private const val VALID_QUALITY_SCORE = 4
        private const val OTHER_QUALITY_SCORE = 2
        private const val VALID_MINUTES_SPENT = 12

        /** 채움 문자. BMP 라 코드 포인트 수와 길이가 같다. */
        private const val FILLER_CHAR = "가"

        /** 로그 카나리 — 자유 의견 축. 값이 로그에 나타나면 결함이다. */
        private const val COMMENT_AXIS = "feedback-comment"
        private const val COMMENT_CANARY = "T15-FEEDBACK-COMMENT-CANARY-8QW3Z"
        private const val LONG_COMMENT_REPEATS = 40

        /**
         * 거절 한 건 — 무엇을 보내고 계약의 **어느 예시**와 대조하는가.
         *
         * [contractExample] 이 `null` 이면 계약에 대응 예시가 없다는 뜻이고, 그때는 `detail` 이
         * 문자열인지까지만 잰다. 예시가 있는데 대조하지 않으면 구현 문구가 조용히 갈린다.
         */
        private data class OutOfRangeCase(
            val label: String,
            val body: String,
            val contractExample: String?,
        )

        /**
         * 척도 밖 값 넷과 목록 밖 배포 의향 하나. 범위의 정본은
         * `core/pilot/ConversionFeedback.kt` 이고 여기는 그 **바깥**을 고른 자리다 —
         * 경계 자신은 위 성공 케이스가 통과로 고정한다.
         */
        private val OUT_OF_RANGE_CASES: List<OutOfRangeCase> by lazy {
            val intent = ContractSpec.schemaEnum(PUBLISH_INTENT_SCHEMA).first()

            fun body(
                score: Int,
                minutes: Int,
            ): String =
                """
                {"$PUBLISH_INTENT_PROPERTY":"$intent","$QUALITY_SCORE_PROPERTY":$score,
                 "$MINUTES_SPENT_PROPERTY":$minutes}
                """.trimIndent()

            listOf(
                OutOfRangeCase("만족도 하한 미만", body(0, VALID_MINUTES_SPENT), SCORE_OUT_OF_RANGE_EXAMPLE),
                OutOfRangeCase("만족도 상한 초과", body(6, VALID_MINUTES_SPENT), SCORE_OUT_OF_RANGE_EXAMPLE),
                OutOfRangeCase("소요 시간 음수", body(VALID_QUALITY_SCORE, -1), MINUTES_OUT_OF_RANGE_EXAMPLE),
                OutOfRangeCase("소요 시간 상한 초과", body(VALID_QUALITY_SCORE, 601), MINUTES_OUT_OF_RANGE_EXAMPLE),
                OutOfRangeCase(
                    "목록 밖 배포 의향",
                    """
                    {"$PUBLISH_INTENT_PROPERTY":"maybe","$QUALITY_SCORE_PROPERTY":$VALID_QUALITY_SCORE,
                     "$MINUTES_SPENT_PROPERTY":$VALID_MINUTES_SPENT}
                    """.trimIndent(),
                    // 계약에 대응 예시가 없다(`PublishIntent.UNKNOWN_INTENT_MESSAGE` 의 주석 참고).
                    contractExample = null,
                ),
            )
        }

        /**
         * 결과 열을 채워 완료 상태로 만든다. **봉투 두 값을 같은 문장에서 함께 SET 한다** —
         * `EnvelopeColumnWriteGuardTest` 의 규약이다.
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
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_feedback") }

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
const val CONVERSION_FEEDBACK_TEST_SECRET: String = "conversion-feedback-test-signing-key-0123456789"

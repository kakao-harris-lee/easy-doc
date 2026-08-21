package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kr.easydoc.api.support.CanaryProbe
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.POSITIVE_CONTROL_MARKER
import kr.easydoc.api.support.RETRO_CANARY_VALUE
import kr.easydoc.api.support.RETRO_CONTROL_MARKER
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** 문서 본문·제목·자격증명이 로그에 남지 않는다 — 원장 조건 18(표 18). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentBodyLogLeakReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("문서 본문·제목·자격증명 카나리가 **강제 TRACE** 의 어느 로그에도 남지 않는다 (양성 대조 2종 포함)")
    fun `문서 본문이 강제 TRACE 로그로도 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val probe = CanaryProbe(RETRO_CONTROL_MARKER)
        val reach = ReachLog(json)
        probe.addCanary(BODY_AXIS, BODY_CANARY)
        probe.addCanary(TITLE_AXIS, TITLE_CANARY)
        probe.addCanary(PASSWORD_AXIS, PASSWORD_CANARY)

        val detached: List<Appender<ILoggingEvent>> = root.iteratorForAppenders().asSequence().toList()
        val restoreLevel: Level? = root.level
        try {
            detached.forEach { root.detachAppender(it) }
            root.addAppender(probe)
            root.level = Level.TRACE

            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)

            LoggerFactory.getLogger(javaClass).warn(RETRO_CONTROL_MARKER)

            LoggerFactory.getLogger(javaClass).warn("late canary $RETRO_CANARY_VALUE emitted")

            val token = newAccount(reach)
            probe.addCanary(TOKEN_AXIS, token)

            probe.addControlCanary(RETRO_CONTROL_AXIS, RETRO_CANARY_VALUE)
            probe.rescanRetained()
            probe.stopRetaining()

            createFromText(reach, STEP_TEXT, token, textBody("$BODY_CANARY 안내 본문입니다", TITLE_CANARY))

            upload(reach, STEP_FILE, token, docxPart(UploadFixtures.sampleDocx()))

            createFromText(reach, STEP_OVER_LIMIT, token, overLimitBody())

            upload(reach, STEP_BROKEN_FILE, token, docxPart(BODY_CANARY.toByteArray(Charsets.UTF_8)))

            createFromText(reach, STEP_UNSAVABLE, token, """{"text":"$BODY_CANARY\ud800"}""")
        } finally {
            root.level = restoreLevel
            root.detachAppender(probe)
            detached.forEach { root.addAppender(it) }
            probe.stop()
        }

        assertNoCanaryInLogs(probe, reach)
    }

    /**
     * 판정부. 트래픽을 태우는 부분과 분리해 둔다 — 단언이 일곱 축(도달·저장·캡처·
     * 레벨 상향·보관 잘림·소급 대조·적중)이라 한 함수에 두면 「무엇을 재는 함수인가」가
     * 흐려진다.
     */
    private fun assertNoCanaryInLogs(
        probe: CanaryProbe,
        reach: ReachLog,
    ) {
        assertReachedIntendedLayers(reach)
        assertPersistedByStorageState()
        assertMeasuredAxisInventory(probe)

        assertThat(probe.sawPositiveControl())
            .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
            .isTrue()

        assertThat(probe.traceEvents())
            .withFailMessage(
                "강제 TRACE 에서 TRACE 이벤트가 0건이다 — 루트 레벨 상향이 먹지 않았다. " +
                    "이 상태의 「유출 0」은 억제이지 탐지가 아니다(총 %d 이벤트).",
                probe.totalEvents(),
            ).isNotZero()

        assertThat(probe.retainTruncated())
            .withFailMessage(
                "보관이 잘렸다 — 계정 생성 구간 %d자가 상한 %d자를 넘겨 소급 대조가 전량이 아니다. " +
                    "이 상태에서는 **토큰 축 커버리지를 잃는다**. 상한을 올려 미루기 전에 " +
                    "보관 구간이 왜 그만큼 커졌는지 답하라.",
                probe.retainedCharsSeen(),
                probe.retainCharLimit(),
            ).isFalse()
        assertRetroMatchIsMeasured(probe)

        val residual = probe.residualCanaryFragments()
        assertThat(residual)
            .withFailMessage(
                "실패 메시지가 카나리 원문 조각을 실어 나른다 — 일치한 자리 %s. " +
                    "조각 값은 찍지 않는다. `CanaryProbe.snippet` 의 치환이 **자르기와 등록 " +
                    "양쪽보다 먼저**인지 보라(같은 결함이 두 번 났다).",
                residual.take(5),
            ).isEmpty()

        assertThat(probe.hits())
            .withFailMessage(
                "강제 TRACE 로그에 카나리가 실렸다 — 아래가 지목이다.%n%s%n" +
                    "고칠 방향: 지목된 로거를 `api/src/main/resources/application.yml` 의 명시 고정에 " +
                    "이름으로 더하고(강제), 이 케이스는 그대로 둔다(탐지). **기대값을 낮춰 덮지 마라** — " +
                    "허용 목록은 CLAUDE.md 규칙 4 ⑵ 가 금지한 면제 조항이다.",
                probe.report(),
            ).isEmpty()
    }

    /** 도달 핀 — 요청 일곱 갈래가 각각 의도한 층에서 처리·거절됐다. */
    private fun assertReachedIntendedLayers(reach: ReachLog) {
        val observed = reach.observed()

        assertThat(EXPECTED_REACH)
            .withFailMessage(
                "도달 기대의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶). " +
                    "요청을 정말 다 뺐다면 이 케이스를 지워야 하고, 그 diff 가 신고다.",
            ).isNotEmpty()

        assertThat(EXPECTED_REACH)
            .withFailMessage(
                "선언 개수(%d)가 상수(%d)와 다르다 — 둘을 함께 고쳐라",
                EXPECTED_REACH.size,
                EXPECTED_REACH_COUNT,
            ).hasSize(EXPECTED_REACH_COUNT)

        assertThat(observed)
            .withFailMessage(
                "카나리가 **의도한 층**에 도달하지 않았다 — 아래가 지목이다(상태/모양).%n%s%n" +
                    "이 상태의 「유출 0건」은 **동어반복**이다: 본문·제목이 그 층에 닿지 않았으니 " +
                    "로그에 없는 것이 당연하다. 다른 열두 성질이 전부 참이어도 결론이 없다.%n" +
                    "거절 자체는 결함이 아니다(셋은 거절이 정상이다) — **거절된 층이 다른 것**이 " +
                    "결함이다. 401 은 인증이, 415 는 미디어 타입 협상이, 404·405 는 경로·메서드가 " +
                    "끊었다는 뜻이고 그 어디서도 본문은 제품 경로를 지나지 않는다.%n" +
                    "**모양이 %s 로 바뀌었으면 스키마·프레임워크 바인딩 층이 먼저 물었다는 뜻**이고, " +
                    "그 갈래의 본문은 도메인에 닿지 않았다 — 앞단 검증·요청 스키마 제약이 들어왔는지 " +
                    "보라(계약 F3). **기대값을 실제에 맞춰 덮지 마라** — 그러면 이 핀이 아무것도 " +
                    "재지 않는다.",
                reachDiff(observed),
                DetailShape.ARRAY,
            ).isEqualTo(EXPECTED_REACH)
    }

    /** 도달 지목. 라벨·정수·열거값만 담는다 — 응답 본문은 [ReachLog] 가 애초에 들고 있지 않다. */
    private fun reachDiff(observed: List<Reached>): String {
        val actualByStep = observed.associateBy { it.step }
        val lines = mutableListOf<String>()
        EXPECTED_REACH.forEach { expected ->
            val actual = actualByStep[expected.step]
            when {
                actual == null -> lines += "  · ${expected.step} — 이 요청이 나가지 않았다(기대 ${expected.mark()})"
                actual != expected -> lines += "  · ${expected.step} — 기대 ${expected.mark()} · 실제 ${actual.mark()}"
            }
        }
        (actualByStep.keys - EXPECTED_REACH.map { it.step }.toSet()).sorted().forEach { step ->
            lines += "  · $step — 선언에 없는 요청이 늘었다(실제 ${actualByStep[step]?.mark()})"
        }
        if (lines.isEmpty()) {
            lines += "  · 상태·모양은 전부 같고 **순서**가 다르다(또는 같은 갈래가 두 번 나갔다)."
            lines += "    기대 ${EXPECTED_REACH.map { it.step }}"
            lines += "    실제 ${observed.map { it.step }}"
        }
        return lines.joinToString(System.lineSeparator())
    }

    /** 저장 핀 — 접수된 두 팔이 실제로 Postgres 행이 됐다. */
    private fun assertPersistedByStorageState() {
        val documentRows = documentRowsByFormat()
        val conversionRows = rowCount(CONVERSIONS_TABLE)
        val jobRows = rowCount(JOBS_TABLE)
        val placeholderRows = placeholderCipherRows()
        val titleCanaryRows = storedTitleCanaryRows()

        assertThat(EXPECTED_DOCUMENT_ROWS)
            .withFailMessage("저장 기대의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶).")
            .isNotEmpty()

        assertThat(documentRows)
            .withFailMessage(
                "저장된 문서 행이 선언과 다르다.%n  없어진 것(선언에만): %s%n  새로 생긴 것(실제에만): %s%n" +
                    "  `text` 는 ⑵ 붙여넣기, `docx` 는 ⑶ 파일 업로드다 — **없어진 쪽이 저장 경로에 닿지 " +
                    "않은 팔**이고, 그 팔의 카나리는 암호화·JDBC 를 지나지 않았다. 202 는 「접수하기로 " +
                    "했다」는 응답 사실이지 「행이 써졌다」는 저장 사실이 아니다.",
                (EXPECTED_DOCUMENT_ROWS - documentRows.toSet()).ifEmpty { listOf("없음") },
                (documentRows - EXPECTED_DOCUMENT_ROWS.toSet()).ifEmpty { listOf("없음") },
            ).isEqualTo(EXPECTED_DOCUMENT_ROWS)

        assertThat(listOf(conversionRows, jobRows))
            .withFailMessage(
                "변환·작업 행 수가 문서 행 수와 어긋난다(변환 %d · 작업 %d, 둘 다 %d 이어야 한다) — " +
                    "문서·변환·작업이 한 트랜잭션에서 확정된다는 계획 §4.4 가 깨졌다.",
                conversionRows,
                jobRows,
                EXPECTED_DOCUMENT_ROWS.size,
            ).containsOnly(EXPECTED_DOCUMENT_ROWS.size)

        assertThat(placeholderRows)
            .withFailMessage(
                "본문이 행에 실제로 들어가지 않은 문서 행이 %d 건이다 — `char_count <= 0` 이거나 " +
                    "암호문 길이가 `char_count` 이하다. AEAD 는 길이를 줄이지 않고 평문의 UTF-8 " +
                    "바이트 수는 코드 포인트 수보다 작을 수 없으므로, 진짜 암호문은 언제나 " +
                    "`char_count` 를 넘는다. 넘지 못하면 카나리가 암호화 경로를 지나지 않았다.",
                placeholderRows,
            ).isEqualTo(EXPECTED_PLACEHOLDER_ROWS)

        assertThat(titleCanaryRows)
            .withFailMessage(
                "제목 카나리가 실린 문서 행이 %d 건이다(%d 이어야 한다) — 제목이 저장 경로를 " +
                    "지나지 않았거나 다른 값으로 바뀌었다. **값은 찍지 않는다.**",
                titleCanaryRows,
                EXPECTED_TITLE_CANARY_ROWS,
            ).isEqualTo(EXPECTED_TITLE_CANARY_ROWS)
        assertStorageQueriesRanOnce()
    }

    /** 질의 횟수 핀 — 판정 구간에서 저장 질의가 정확히 [EXPECTED_STORAGE_QUERIES] 번 나갔다. */
    private fun assertStorageQueriesRanOnce() {
        assertThat(storageQueries)
            .withFailMessage(
                "저장 질의가 %d 번 나갔다(%d 이어야 한다). `withFailMessage` 의 인자는 **성공 " +
                    "경로에서도** 평가되므로 단언 안에서 질의를 호출하면 회차마다 두세 번 나간다 — " +
                    "값을 **먼저 한 번** 읽어 지역 변수로 써라. 실 DB 왕복이라 Testcontainers " +
                    "실행 시간에 그대로 실린다.",
                storageQueries,
                EXPECTED_STORAGE_QUERIES,
            ).isEqualTo(EXPECTED_STORAGE_QUERIES)
    }

    /** 저장 질의가 나간 횟수. 「한 번씩만」을 단언으로 만드는 계수기다. */
    private var storageQueries = 0

    /** `형식=행수` 목록. 형식 이름과 개수만 나오므로 값이 실리지 않는다. */
    private fun documentRowsByFormat(): List<String> {
        storageQueries++
        return database.queryFirstColumn(
            "SELECT source_format || '=' || count(*)::text FROM documents GROUP BY source_format ORDER BY 1",
        )
    }

    private fun rowCount(table: String): Int {
        storageQueries++
        return database.queryInt("SELECT count(*) FROM $table")
    }

    /** 암호문이 자리표시자인 행 수. 평문을 꺼내지 않는다 — 두 열의 관계만 본다. */
    private fun placeholderCipherRows(): Int {
        storageQueries++
        return database.queryInt(
            "SELECT count(*) FROM documents " +
                "WHERE char_count <= 0 OR octet_length(source_text_encrypted) <= char_count",
        )
    }

    /** 제목 카나리가 실린 행 수. */
    private fun storedTitleCanaryRows(): Int {
        storageQueries++
        return database.queryFirstColumn("SELECT title FROM documents").count { it == TITLE_CANARY }
    }

    /** 재고 핀 — 재고 있는 축의 집합이 선언과 정확히 일치하고, 그 선언이 비어 있지 않다. */
    private fun assertMeasuredAxisInventory(probe: CanaryProbe) {
        assertThat(EXPECTED_AXES)
            .withFailMessage(
                "재는 축의 선언이 비었다 — 아래 대조가 0건 검사가 된다(CLAUDE.md 규칙 4 ⑶). " +
                    "축을 정말 다 뺐다면 이 케이스를 지워야 하고, 그 diff 가 신고다.",
            ).isNotEmpty()

        assertThat(EXPECTED_AXES)
            .withFailMessage("선언 개수(%d)가 상수(%d)와 다르다 — 둘을 함께 고쳐라", EXPECTED_AXES.size, EXPECTED_AXIS_COUNT)
            .hasSize(EXPECTED_AXIS_COUNT)

        assertThat(probe.registeredAxes())
            .withFailMessage(
                "재고 있는 축이 선언과 다르다.%n  없어진 축(선언에만): %s%n  새로 생긴 축(실제에만): %s%n" +
                    "  축 등록이 지워지면 그 축은 **조용히 안 재진다** — 통제 카나리도 " +
                    "`pendingRetroMatches()` 도 그것을 보지 못한다. 정말 뺐다면 EXPECTED_AXES 와 " +
                    "EXPECTED_AXIS_COUNT 를 함께 고쳐라(그 diff 가 신고다).",
                (EXPECTED_AXES - probe.registeredAxes()).ifEmpty { setOf("없음") },
                (probe.registeredAxes() - EXPECTED_AXES).ifEmpty { setOf("없음") },
            ).isEqualTo(EXPECTED_AXES)
    }

    /**
     * 소급 대조 축의 통제. 세 단언이 겨누는 것이 각각 다르다 — 하나로 줄이면 그 차이가
     * 사라지고, 그 차이를 못 본 것이 이 세션의 세 번째 같은 결함이었다.
     */
    private fun assertRetroMatchIsMeasured(probe: CanaryProbe) {
        assertThat(probe.sawRetroControl())
            .withFailMessage(
                "소급 루프가 보관 구간의 표식을 보지 못했다 — 보관이 비었거나 루프가 죽었다" +
                    "(보관 %d건 관측, %d자).",
                probe.retainedEvents(),
                probe.retainedCharsSeen(),
            ).isTrue()

        assertThat(probe.controlHitAxes())
            .withFailMessage(
                "늦게 등록된 통제 카나리가 보관분에 대해 적중을 내지 않았다 — 소급 대조가 " +
                    "카나리 집합을 지나지 않는다는 뜻이고, **토큰 축도 같이 죽어 있다**. " +
                    "`sawRetroControl()` 은 %s 였다 — 두 통제가 겨누는 것이 다른 이유가 이것이다.",
                probe.sawRetroControl(),
            ).contains(RETRO_CONTROL_AXIS)

        assertThat(probe.pendingRetroMatches())
            .withFailMessage(
                "소급 대조를 지나지 않은 늦은 등록이 있다: %s. 그 축은 보관 구간을 재지 못했다 — " +
                    "등록을 `rescanRetained()` **앞**으로 두거나, 등록 뒤에 한 번 더 대조하라.",
                probe.pendingRetroMatches(),
            ).isEmpty()
    }

    /**
     * 계정을 만들고 토큰을 받는다. 두 요청 다 도달 핀에 기록한다 — 비밀번호 축이 요청
     * 바이트·Argon2·JDBC 를 지나는 자리가 이 둘이고, 여기서 끊기면 그 축이 조용히 죽는다.
     */
    private fun newAccount(reach: ReachLog): String {
        val credentials =
            json.writeValueAsString(mapOf("email" to "canary@example.test", "password" to PASSWORD_CANARY))
        reach.record(STEP_SIGNUP, send(post(null, credentials, SIGNUP_PATH)))
        val login = send(post(null, credentials, LOGIN_PATH))
        reach.record(STEP_LOGIN, login)
        return json.readValue(login.body(), Map::class.java)["access_token"].toString()
    }

    private fun createFromText(
        reach: ReachLog,
        step: String,
        token: String,
        body: String,
    ) {
        reach.record(step, send(post(token, body)))
    }

    private fun upload(
        reach: ReachLog,
        step: String,
        token: String,
        body: MultipartBody,
    ) {
        reach.record(
            step,
            send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                    .header("Content-Type", body.contentType())
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.build())),
            ),
        )
    }

    /** 파일 파트. 파일 이름이 제목 카나리를 나른다 — 이름이 새는지도 같이 잰다. */
    private fun docxPart(bytes: ByteArray): MultipartBody = MultipartBody().file(FILE_PART, "$TITLE_CANARY.docx", bytes)

    /** 상한을 확실히 넘기는 본문. 호출부를 한 줄에 두려고 뺐다 — 값은 그대로다. */
    private fun overLimitBody(): String = textBody(BODY_CANARY + "가".repeat(OVER_LIMIT_CHARS), TITLE_CANARY)

    private fun textBody(
        text: String,
        title: String,
    ): String = json.writeValueAsString(mapOf("text" to text, "title" to title))

    private fun post(
        token: String?,
        body: String,
        path: String = DOCUMENTS_PATH,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** 오류 본문 `detail` 의 모양. 값이 아니라 모양만 남기는 것이 이 열거의 존재 이유다. */
    private enum class DetailShape {
        /** `detail` 키가 없다(2xx 본문) — 또는 값이 JSON `null` 이다. 우리 계약에 후자는 없다. */
        NONE,

        /** 문자열 `detail` — 도메인 예외 매핑(`ErrorResponse`). */
        STRING,

        /** 배열 `detail` — 검증 경로(`ValidationErrorResponse`). */
        ARRAY,

        /** 숫자·객체·불리언. 계약에 없는 모양이다. */
        OTHER,

        /** 본문이 비었거나 JSON 이 아니다. */
        UNREADABLE,
    }

    /** 요청 하나의 도달 지문. 본문을 담지 않는다 — 상태 코드와 모양 열거값뿐이다. */
    private data class Reached(
        val step: String,
        val status: Int,
        val detail: DetailShape,
    ) {
        /** 지목 줄에 쓰는 표기. 두 축을 한 쌍으로 붙여 어느 축이 어긋났는지 바로 보이게 한다. */
        fun mark(): String = "$status/$detail"
    }

    /** 요청 하나가 낸 상태 코드와 `detail` 모양만 순서대로 모은다. */
    private class ReachLog(private val json: ObjectMapper) {
        private val steps = mutableListOf<Reached>()

        fun record(
            step: String,
            response: HttpResponse<String>,
        ) {
            steps += Reached(step, response.statusCode(), shapeOf(response.body()))
        }

        fun observed(): List<Reached> = steps.toList()

        /** 본문에서 모양만 뽑고 본문은 즉시 버린다. */
        private fun shapeOf(body: String?): DetailShape {
            // 갈래를 늘리면 detekt ReturnCount 를 넘기면서 얻는 것이 없다.
            val parsed =
                body
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text -> runCatching { json.readValue(text, Map::class.java) }.getOrNull() }
                    ?: return DetailShape.UNREADABLE
            return when (parsed[DETAIL_KEY]) {
                null -> DetailShape.NONE
                is String -> DetailShape.STRING
                is List<*> -> DetailShape.ARRAY
                else -> DetailShape.OTHER
            }
        }
    }

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"

        /** 계약 `DocumentFileRequest.properties` 의 파일 파트 이름. */
        private const val FILE_PART = "file"

        /** 자연 발생하지 않는 값이어야 「없다」가 뜻을 갖는다. */
        private const val BODY_CANARY = "CANARY-DOCUMENT-BODY-7Q2XZ"
        private const val TITLE_CANARY = "CANARY-DOCUMENT-TITLE-4M8VW"
        private const val PASSWORD_CANARY = "CANARY-CREDENTIAL-9L3RT-correct-horse"

        /** 소급 대조 통제 축의 이름. 유출 축이 아니라 통제 집합에 등록된다. */
        private const val RETRO_CONTROL_AXIS = "소급 대조 통제"

        private const val BODY_AXIS = "본문"
        private const val TITLE_AXIS = "제목"
        private const val PASSWORD_AXIS = "자격증명(비밀번호)"
        private const val TOKEN_AXIS = "자격증명(액세스 토큰)"

        /**
         * 이 케이스가 재는 축의 정본 재고. `CanaryProbe.registeredAxes()` 와 정확히 일치해야
         * 한다. 접두 `유출`/`통제` 는 등록 레지스트리를 뜻한다 — 통제 축은 유출 축의 예외가 아니라
         * 다른 집합이다(`CanaryProbe` KDoc 참고).
         */
        private val EXPECTED_AXES =
            setOf(
                "유출 $BODY_AXIS",
                "유출 $TITLE_AXIS",
                "유출 $PASSWORD_AXIS",
                "유출 $TOKEN_AXIS",
                "통제 $RETRO_CONTROL_AXIS",
            )

        /** 목록과 함께 고쳐야 하는 개수 핀. 한 축을 빼고 다른 축을 넣는 편집을 드러낸다. */
        private const val EXPECTED_AXIS_COUNT = 5

        /**
         * 도달 핀의 요청 라벨. 컴파일 상수이고 응답에서 온 값이 아니다 — 실패 메시지가
         * CI 로그로 나가므로 라벨에 런타임 문자열을 섞지 않는다.
         */
        private const val STEP_SIGNUP = "⑴-a 계정 생성"
        private const val STEP_LOGIN = "⑴-b 로그인"
        private const val STEP_TEXT = "⑵ 붙여넣기 성공"
        private const val STEP_FILE = "⑶ 파일 업로드 성공"
        private const val STEP_OVER_LIMIT = "⑷ 본문 상한 초과"
        private const val STEP_BROKEN_FILE = "⑸ 손상 파일"
        private const val STEP_UNSAVABLE = "⑹ 저장할 수 없는 문자"

        /** 오류 본문의 유일한 키. 계약 `{"detail": …}`. */
        private const val DETAIL_KEY = "detail"

        /** 이 케이스가 태우는 요청과, 각 요청이 닿아야 하는 층의 지문(상태 코드 + `detail` 모양). */
        private val EXPECTED_REACH: List<Reached> =
            listOf(
                Reached(STEP_SIGNUP, 201, DetailShape.NONE),
                Reached(STEP_LOGIN, 200, DetailShape.NONE),
                Reached(STEP_TEXT, 202, DetailShape.NONE),
                Reached(STEP_FILE, 202, DetailShape.NONE),
                Reached(STEP_OVER_LIMIT, 422, DetailShape.STRING),
                Reached(STEP_BROKEN_FILE, 422, DetailShape.STRING),
                Reached(STEP_UNSAVABLE, 422, DetailShape.STRING),
            )

        /** 목록과 함께 고쳐야 하는 개수 핀. 한 갈래를 빼고 다른 갈래를 넣는 편집을 드러낸다. */
        private const val EXPECTED_REACH_COUNT = 7

        private const val CONVERSIONS_TABLE = "conversions"
        private const val JOBS_TABLE = "conversion_jobs"

        /** 저장 상태 기대. 응답이 아니라 DB 쪽에서 보는 값들이다. */
        private val EXPECTED_DOCUMENT_ROWS = listOf("docx=1", "text=1")

        /** 암호문이 자리표시자인 행은 하나도 없어야 한다. */
        private const val EXPECTED_PLACEHOLDER_ROWS = 0

        /** 제목 카나리가 실린 행 — ⑵ 뿐이다(⑶ 은 제목을 주지 않아 대체 제목이다). */
        private const val EXPECTED_TITLE_CANARY_ROWS = 1

        /** 판정 구간에서 나가야 하는 저장 질의 수. */
        private const val EXPECTED_STORAGE_QUERIES = 5

        /** 계약 상한을 확실히 넘기는 길이. 정확한 경계는 DC-9·DC-10 이 잰다. */
        private const val OVER_LIMIT_CHARS = 5_000

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_log_leak") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

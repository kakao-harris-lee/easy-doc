package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import kr.easydoc.api.support.MultipartBody
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * **문서 본문·제목·자격증명이 로그에 남지 않는다** — 원장 조건 18(표 18).
 *
 * ## 이 케이스가 **강제 TRACE** 에서 도는 이유
 *
 * 종전 형태는 **제품 기본 로그 구성(root INFO) 그대로** 돌았다. 그것이 증명하는 것은
 * *"기본 레벨에서는 안 새어 나온다"* 뿐이고, **기본 레벨에서 안 새는 이유는 우리 장치가
 * 아니라 로거 레벨이 낮아 그 줄이 아예 방출되지 않기 때문**이다. 즉 억제를 탐지로 오인한
 * 상태였다(게이트 28 교차 종합 #1 — codex F-1 `critical` 과 Claude M-2 가 독립 합의).
 *
 * 원장 조건 18 이 겨눈 축은 **강제 TRACE** 다. 근거는 실측이다
 * (`reviews/03_security-workspaces-fixes_privacy-gate.md` 기록 ③) — 루트를 TRACE 로 올리자
 * 프레임워크 로거 **3종**이 이름·이메일·평문 비밀번호·PHC·토큰을 실제로 찍었다
 * (`Http11InputBuffer` = 원시 요청 바이트 · `StatementCreatorUtils` = JDBC 바인딩 파라미터 ·
 * `QueryExecutorImpl` = 와이어 프로토콜). 기본·DEBUG 에서는 0이었다.
 *
 * 그래서 이 케이스는 **루트 로거를 TRACE 로 올린 상태**에서 요청을 태운다. 강제 TRACE 는
 * 기본 구성의 **상위 집합**이다(어떤 로거의 레벨도 내려가지 않고 방출만 늘어난다) —
 * 그러므로 여기서 0이면 제품 기본 구성에서도 0이며, 종전이 재던 축은 이 축에 포함된다.
 *
 * ## 강제 + 탐지 병용 — 「레벨 고정 = 은폐형」은 오분류였다
 *
 * 종전 KDoc 은 *"로거 레벨을 못박는 것은 은폐형이므로 쓰지 않는다"* 고 적었다. **그 분류가
 * 틀렸다**(게이트 28 리더 판정 P-3). `CLAUDE.md` 규칙 4 의 은폐형은 *"신호를 줄인다 —
 * 무시 패턴·억제·예외 목록·면제 조항"* 인데, 로거를 INFO 로 못박는 것은 신호를 가리는 것이
 * 아니라 **방출 자체를 막는 강제·표현형**이다. 이웃 `application.yml` 이 이미
 * `org.springframework.web`·`org.springframework.security`·`org.flywaydb` 를 그렇게 고정하며
 * 강제와 탐지를 병용하고 있었다.
 *
 * 그래서 이 자리도 **두 겹으로 간다**:
 * - **강제** — `api/src/main/resources/application.yml` 이 그 세 로거를 **이름으로** INFO 에
 *   못박는다. 열거이므로 **네 번째 로거는 못 잡는다**(그 한계는 그 파일 주석에 적혀 있다).
 * - **탐지(이 파일)** — 열거의 빈자리를 이 케이스가 덮는다. **루트만** TRACE 로 올리고
 *   **로거 이름은 모른 채** 방출된 것 전부를 훑으므로, ⑴ 누가 그 고정을 지우면 ⑵ 판올림이
 *   네 번째 로거로 같은 것을 흘리기 시작하면 ⑶ 우리 방어가 사라지면 **그 커밋에서 빨개진다.**
 *   실패 메시지가 **어느 로거·어느 레벨·어느 축**인지 지목한다.
 *
 * 반대 방향의 처방 — *"불변식을 기본 운영 레벨로 축소한다"* — 은 **기각됐다**(같은 판정).
 * 그것이야말로 규칙 4 ⑵ 가 금지한 **면제 조항**이다.
 *
 * ## 「강제 TRACE 에서 오늘 유출이 있다」를 어떻게 다뤘는가
 *
 * 기록 ③ 의 실측이 그것이다. 그래서 이 케이스의 「유출 0」은 **⒝ 의 고정이 실제로 방출을
 * 막을 때만** 성립한다 — 그 조건 위에서 **0을 단언한다**. 유출을 낸 로거를 적어 두고
 * 넘기는 **허용 목록(특성화)을 두지 않았다**: 목록을 두면 그것이 규칙 4 ⑵ 의 면제 조항이
 * 되고, 목록에 든 로거가 다음에 무엇을 더 흘려도 초록이다. 음성 대조 N-A(고정 3줄을 지우면
 * 빨강)가 **0이 억제 덕이 아니라 강제 덕임을** 실행으로 보인다.
 *
 * ## 카나리를 네 축으로 나눈다
 *
 * **본문 · 제목 · 비밀번호 · 액세스 토큰**. 축을 나누지 않으면 「무엇이 샜는가」가 실패
 * 메시지에서 사라지고, 한 축만 막은 구현이 다른 축의 유출을 데리고 통과한다.
 *
 * 토큰은 **발급 전에는 값을 모른다**. 그래서 계정 생성 구간만 이벤트를 **보관**해 두고
 * 발급 직후 그 보관분을 **소급 대조**한다(`rescanRetained`) — 로그인 응답 바이트를 찍는
 * 로거가 있으면 그 자리에서 잡힌다. 문서 요청 구간은 보관 없이 흐름 대조만 한다(강제 TRACE
 * 의 방출량이 힙을 넘길 수 있다).
 *
 * ## 실패 경로를 함께 태운다
 *
 * 유출은 성공 경로보다 **오류 경로**에서 난다(예외 메시지·스택트레이스가 입력을 담는다).
 * 그래서 거절되는 요청 셋 — 상한 초과 본문 · 손상 파일 · 저장할 수 없는 문자 — 도 같은
 * 캡처 구간에서 돌린다.
 *
 * ## 양성 대조 둘
 *
 * ⑴ 「0건」은 **캡처가 비어 있어도** 참이다. 요청 전에 표식을 직접 찍고 그것이 캡처에
 * 있는지 먼저 본다. ⑵ 강제 TRACE 축에서는 표식 하나로 부족하다 — **TRACE 이벤트가 실제로
 * 방출됐는지**(레벨 상향이 먹었는지)도 함께 단언한다. 레벨을 올리지 못한 채 「0건」을
 * 보고하는 것이 이 케이스의 가장 조용한 실패이고, 그것이 종전 형태의 결함이었다.
 */
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
        val probe = CanaryProbe()
        probe.addCanary("본문", BODY_CANARY)
        probe.addCanary("제목", TITLE_CANARY)
        probe.addCanary("자격증명(비밀번호)", PASSWORD_CANARY)

        // 콘솔 appender 를 잠시 떼어 둔다 — 강제 TRACE 의 방출량이 빌드 로그를 덮는 것을
        // 막을 뿐이고, **판정은 probe 가 받은 전량으로 한다**(신호를 줄이지 않는다).
        val detached: List<Appender<ILoggingEvent>> = root.iteratorForAppenders().asSequence().toList()
        val restoreLevel: Level? = root.level
        try {
            detached.forEach { root.detachAppender(it) }
            root.addAppender(probe)
            root.level = Level.TRACE

            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)

            // ⑴ 계정 생성 — 평문 비밀번호가 요청 바이트·Argon2·JDBC 를 지난다. 이 구간만
            //    보관해 두고, 토큰을 손에 쥔 직후 소급 대조한다.
            val token = newAccount()
            probe.addCanary("자격증명(액세스 토큰)", token)
            probe.rescanRetained()
            probe.stopRetaining()

            // ⑵ 성공 — 본문·제목이 저장 경로 전체(암호화·JDBC·트랜잭션)를 지난다.
            createFromText(token, textBody("$BODY_CANARY 안내 본문입니다", TITLE_CANARY))
            // ⑶ 파일 모드 — multipart 파싱과 파서까지 지난다.
            upload(token, MultipartBody().file("file", "$TITLE_CANARY.docx", UploadFixtures.sampleDocx()))
            // ⑷ 상한 초과 — 서비스 층 거절.
            createFromText(token, textBody(BODY_CANARY + "가".repeat(OVER_LIMIT_CHARS), TITLE_CANARY))
            // ⑸ 손상 파일 — 파서 예외가 도메인 예외로 바뀌는 경로.
            upload(token, MultipartBody().file("file", "$TITLE_CANARY.docx", BODY_CANARY.toByteArray(Charsets.UTF_8)))
            // ⑹ 저장할 수 없는 문자 — `PlainBody` 거절 경로.
            send(post(token, """{"text":"$BODY_CANARY\ud800"}"""))
        } finally {
            root.level = restoreLevel
            root.detachAppender(probe)
            detached.forEach { root.addAppender(it) }
            probe.stop()
        }

        // 양성 대조 ⑴ — 캡처가 살아 있는가.
        assertThat(probe.sawPositiveControl())
            .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
            .isTrue()
        // 양성 대조 ⑵ — 레벨 상향이 실제로 먹었는가. 이것이 없으면 「0건」은
        // 「TRACE 를 켜지 못했다」와 구분되지 않는다 — 그것이 종전 형태의 결함이다.
        assertThat(probe.traceEvents())
            .withFailMessage(
                "강제 TRACE 에서 TRACE 이벤트가 0건이다 — 루트 레벨 상향이 먹지 않았다. " +
                    "이 상태의 「유출 0」은 억제이지 탐지가 아니다(총 %d 이벤트).",
                probe.totalEvents(),
            ).isNotZero()

        assertThat(probe.hits())
            .withFailMessage(
                "강제 TRACE 로그에 카나리가 실렸다 — 아래가 지목이다.%n%s%n" +
                    "고칠 방향: 지목된 로거를 `api/src/main/resources/application.yml` 의 명시 고정에 " +
                    "이름으로 더하고(강제), 이 케이스는 그대로 둔다(탐지). **기대값을 낮춰 덮지 마라** — " +
                    "허용 목록은 CLAUDE.md 규칙 4 ⑵ 가 금지한 면제 조항이다.",
                probe.report(),
            ).isEmpty()
    }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val credentials =
            json.writeValueAsString(mapOf("email" to "canary@example.test", "password" to PASSWORD_CANARY))
        send(post(null, credentials, "/auth/signup"))
        return json
            .readValue(send(post(null, credentials, "/auth/login")).body(), Map::class.java)["access_token"]
            .toString()
    }

    private fun createFromText(
        token: String,
        body: String,
    ): HttpResponse<String> = send(post(token, body))

    private fun upload(
        token: String,
        body: MultipartBody,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$DOCUMENTS_PATH"))
                .header("Content-Type", body.contentType())
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build())),
        )

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

    companion object {
        private const val DOCUMENTS_PATH = "/documents"

        /** 자연 발생하지 않는 값이어야 「없다」가 뜻을 갖는다. */
        private const val BODY_CANARY = "CANARY-DOCUMENT-BODY-7Q2XZ"
        private const val TITLE_CANARY = "CANARY-DOCUMENT-TITLE-4M8VW"
        private const val PASSWORD_CANARY = "CANARY-CREDENTIAL-9L3RT-correct-horse"

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

/** 이 문자열이 캡처에 없으면 「유출 0건」은 캡처가 비었다는 뜻이다. */
private const val POSITIVE_CONTROL_MARKER = "T18-LOG-CAPTURE-ALIVE"

/**
 * 방출된 로그 이벤트를 **그 자리에서 훑는** appender.
 *
 * 강제 TRACE 는 이벤트를 수만 건 낸다 — 전량을 쌓으면 힙이 위험하고 문자열로 이어 붙이면
 * 실패 메시지가 통째로 쏟아진다. 그래서 이벤트마다 즉시 대조하고 **적중만** (로거·레벨·축·
 * 경계 잘라낸 조각) 남긴다. 적중 지목이 이 장치의 산출물이다.
 *
 * **보관 구간**은 예외다 — 발급 전에는 값을 모르는 카나리(액세스 토큰)를 소급 대조해야 하므로,
 * `stopRetaining()` 호출 전까지는 렌더링 결과를 상한(`RETAIN_CHAR_LIMIT`) 안에서 들고 있는다.
 * 상한을 넘기면 보관을 포기하고 그 사실을 `retainTruncated` 로 남긴다 — 조용히 버리지 않는다.
 *
 * 요청은 Tomcat 워커 스레드에서 처리되므로 수집 구조는 동시 접근을 견뎌야 한다.
 */
private class CanaryProbe : AppenderBase<ILoggingEvent>() {
    private val canaries = ConcurrentLinkedQueue<Pair<String, String>>()
    private val found = ConcurrentLinkedQueue<String>()
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    private val retained = ConcurrentLinkedQueue<Pair<String, String>>()
    private val retainedChars = AtomicLong()
    private val total = AtomicLong()
    private val trace = AtomicLong()

    @Volatile private var retaining = true

    @Volatile private var retainTruncated = false

    @Volatile private var positiveControl = false

    init {
        name = "canary-probe"
        start()
    }

    /** 카나리를 늦게 등록할 수 있다 — 토큰은 발급 뒤에야 값을 안다. */
    fun addCanary(
        axis: String,
        needle: String,
    ) {
        canaries += axis to needle
    }

    /** 보관분을 현재 카나리 전체로 다시 훑는다. 늦게 등록한 축의 과거 방출을 잡는 유일한 길이다. */
    fun rescanRetained() {
        retained.forEach { (logger, rendered) -> match(logger, rendered) }
    }

    /** 보관을 끝내고 힙을 놓는다. 이후 구간은 흐름 대조만 한다. */
    fun stopRetaining() {
        retaining = false
        retained.clear()
        retainedChars.set(0)
    }

    override fun append(event: ILoggingEvent) {
        total.incrementAndGet()
        if (event.level == Level.TRACE) trace.incrementAndGet()
        val rendered = render(event)
        if (rendered.contains(POSITIVE_CONTROL_MARKER)) positiveControl = true
        val label = "${event.loggerName}@${event.level}"
        if (retaining) {
            if (retainedChars.addAndGet(rendered.length.toLong()) <= RETAIN_CHAR_LIMIT) {
                retained += label to rendered
            } else {
                retainTruncated = true
            }
        }
        match(label, rendered)
    }

    private fun match(
        label: String,
        rendered: String,
    ) {
        canaries.forEach { (axis, needle) ->
            if (rendered.contains(needle)) {
                // 로거·축 조합당 한 줄만 남긴다 — 같은 로거가 수천 번 찍어도 지목은 한 번이면 된다.
                if (pinned.add("$label|$axis")) {
                    found += "  · $label — $axis 축: …${snippet(rendered, needle)}…"
                }
            }
        }
    }

    fun hits(): List<String> = found.toList().sorted()

    fun report(): String =
        buildString {
            append(hits().joinToString(System.lineSeparator()).ifEmpty { "  (지목 없음)" })
            if (retainTruncated) {
                append(System.lineSeparator())
                append("  ※ 보관 상한 초과 — 계정 생성 구간의 소급 대조가 전량이 아니다.")
            }
        }

    fun sawPositiveControl(): Boolean = positiveControl

    fun totalEvents(): Long = total.get()

    fun traceEvents(): Long = trace.get()

    /** 로그 한 줄이 실제로 파일·콘솔에 남기는 것 전부 — 메시지와 예외 체인(스택 프레임 포함). */
    private fun render(event: ILoggingEvent): String =
        buildString {
            append(event.loggerName).append(' ').append(event.formattedMessage)
            var throwable: IThrowableProxy? = event.throwableProxy
            while (throwable != null) {
                append('\n').append(throwable.className).append(": ").append(throwable.message)
                throwable.stackTraceElementProxyArray?.forEach { append('\n').append(it.steAsString) }
                throwable = throwable.cause
            }
        }

    private fun snippet(
        text: String,
        needle: String,
    ): String {
        val at = text.indexOf(needle)
        val from = (at - CONTEXT_CHARS).coerceAtLeast(0)
        val to = (at + needle.length + CONTEXT_CHARS).coerceAtMost(text.length)
        return text.substring(from, to).replace('\n', '⏎').replace('\r', '⏎')
    }

    private companion object {
        /** 계정 생성 구간만 보관한다 — 그 구간의 실측 방출량(기록 ③ 기준 수백~수천 건)의 여유분. */
        const val RETAIN_CHAR_LIMIT = 32L * 1024 * 1024
        const val CONTEXT_CHARS = 60
    }
}

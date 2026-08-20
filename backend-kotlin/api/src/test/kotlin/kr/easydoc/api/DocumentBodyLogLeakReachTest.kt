package kr.easydoc.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
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

/**
 * **문서 본문·제목·자격증명이 로그에 남지 않는다** — 원장 조건 18(표 18).
 *
 * ## 왜 로거 이름을 열거하지 않는가
 *
 * 원장이 지목한 것은 강제 TRACE 에서 값을 찍은 **프레임워크 로거 3종**이었다
 * (`Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl`, 실측 근거는
 * `reviews/03_security-workspaces-fixes_privacy-gate.md` 기록 ③). 그 셋의 레벨을
 * `application.yml` 에 못박는 처방은 **열거이자 은폐형**이다 — 다음 라이브러리가 다른
 * 로거로 같은 것을 흘리면 아무도 모르고, `CLAUDE.md` 규칙 4 ⑵ 가 그 방향을 금한다
 * (은폐형은 넓히지 말고 **탐지형으로 갈아탄다**). Phase 3 ⓘ 가 같은 판단을 이미 집행했다.
 *
 * ## 그래서 탐지로 간다
 *
 * **제품 기본 로그 구성 그대로** 앱을 띄우고, 카나리 문자열을 실은 요청을 `POST /documents`
 * 로 태운 뒤 그 사이에 찍힌 **모든 로그**(메시지 + 예외 체인 + 스택 프레임)에 카나리가
 * 0건인지 본다. 이 장치는 **로거 이름을 모른다** — 그래서 로거가 늘어도 같이 잡고,
 * 누가 레벨을 내리거나 라이브러리가 요청 바이트를 INFO 로 찍기 시작하면 그 커밋에서
 * 빨개진다.
 *
 * ## 카나리를 세 축으로 나눈다
 *
 * **본문 · 제목 · 자격증명**. 축을 나누지 않으면 「무엇이 샜는가」가 실패 메시지에서
 * 사라지고, 한 축만 막은 구현이 다른 축의 유출을 데리고 통과한다.
 *
 * ## 실패 경로를 함께 태운다
 *
 * 유출은 성공 경로보다 **오류 경로**에서 난다(예외 메시지·스택트레이스가 입력을 담는다).
 * 그래서 거절되는 요청 셋 — 상한 초과 본문 · 손상 파일 · 저장할 수 없는 문자 — 도 같은
 * 캡처 구간에서 돌린다.
 *
 * ## 양성 대조
 *
 * 「0건」은 **캡처가 비어 있어도** 참이다. 요청 전에 표식을 직접 찍고 그것이 캡처에
 * 있는지 먼저 본다 — 없으면 이 케이스는 아무것도 보고 있지 않은 것이다.
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
    @DisplayName("문서 본문·제목·자격증명 카나리가 제품 기본 로그 구성의 **어느 로그에도** 남지 않는다 (양성 대조 포함)")
    fun `문서 본문이 로그로 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)
            val token = newAccount()

            // ⑴ 성공 — 본문·제목이 저장 경로 전체(암호화·JDBC·트랜잭션)를 지난다.
            createFromText(token, textBody("$BODY_CANARY 안내 본문입니다", TITLE_CANARY))
            // ⑵ 파일 모드 — multipart 파싱과 파서까지 지난다.
            upload(token, MultipartBody().file("file", "$TITLE_CANARY.docx", UploadFixtures.sampleDocx()))
            // ⑶ 상한 초과 — 서비스 층 거절.
            createFromText(token, textBody(BODY_CANARY + "가".repeat(OVER_LIMIT_CHARS), TITLE_CANARY))
            // ⑷ 손상 파일 — 파서 예외가 도메인 예외로 바뀌는 경로.
            upload(token, MultipartBody().file("file", "$TITLE_CANARY.docx", BODY_CANARY.toByteArray(Charsets.UTF_8)))
            // ⑸ 저장할 수 없는 문자 — `PlainBody` 거절 경로.
            send(post(token, """{"text":"$BODY_CANARY\ud800"}"""))

            val captured = appender.list.joinToString("\n") { render(it) }

            assertThat(captured)
                .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
                .contains(POSITIVE_CONTROL_MARKER)

            canaries(token).forEach { (axis, needle) ->
                assertThat(captured)
                    .withFailMessage(
                        "로그에 %s 카나리가 실렸다 — 로거 레벨이 내려갔거나 라이브러리가 요청 값을 찍기 시작했다. " +
                            "레벨을 올려 덮지 말고 **무엇이 찍는지** 찾아라(은폐형 금지, CLAUDE.md 규칙 4 ⑵).",
                        axis,
                    ).doesNotContain(needle)
            }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    /** (축, 로그에 있으면 안 되는 문자열). 세 축을 나누는 이유는 클래스 KDoc 에 있다. */
    private fun canaries(token: String): List<Pair<String, String>> =
        listOf(
            "본문" to BODY_CANARY,
            "제목" to TITLE_CANARY,
            "자격증명(비밀번호)" to PASSWORD_CANARY,
            "자격증명(액세스 토큰)" to token,
        )

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

        /** 이 문자열이 캡처에 없으면 「유출 0건」은 캡처가 비었다는 뜻이다. */
        private const val POSITIVE_CONTROL_MARKER = "T18-LOG-CAPTURE-ALIVE"

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

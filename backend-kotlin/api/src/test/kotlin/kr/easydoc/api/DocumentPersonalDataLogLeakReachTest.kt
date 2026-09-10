package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import kr.easydoc.api.support.CanaryProbe
import kr.easydoc.api.support.POSITIVE_CONTROL_MARKER
import kr.easydoc.api.support.RETRO_CONTROL_MARKER
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
 * 개인정보 경고용 검출값이 로그로 새지 않는다 — 계획
 * `docs/plans/2026-09-10-personal-data-warning.md` §4 수용 기준 8, §2.3.
 *
 * `DocumentBodyLogLeakReachTest`(원장 조건 18)와 같은 기법(`CanaryProbe`, 강제 TRACE)을
 * 쓰되, 그 파일을 고치지 않는다 — 그 파일은 축·재고·질의 횟수가 정확히 맞물린 인구조사
 * 스타일 테스트라 새 갈래를 끼우면 그 불변식을 전부 다시 맞춰야 한다. 이 케이스는
 * 카나리 값을 **요청 전에** 등록하므로 그 파일의 소급 대조(rescan) 장치가 필요 없다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentPersonalDataLogLeakReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("검출된 주민등록번호·카드번호 원문이 강제 TRACE 로그로도 새지 않는다")
    fun `개인정보 검출값이 로그로 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        // 소급 대조를 쓰지 않으므로 통제 문자열 자체는 이 케이스에 의미가 없다 — 생성자
        // 인자 자리를 채우는 값일 뿐이다.
        val probe = CanaryProbe(RETRO_CONTROL_MARKER)
        probe.addCanary(RRN_AXIS, VALID_RRN)
        probe.addCanary(CARD_AXIS, VALID_CARD)

        val detached: List<Appender<ILoggingEvent>> = root.iteratorForAppenders().asSequence().toList()
        val restoreLevel: Level? = root.level
        val response: HttpResponse<String>
        try {
            detached.forEach { root.detachAppender(it) }
            root.addAppender(probe)
            root.level = Level.TRACE

            LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)

            val token = newAccount()
            response =
                send(
                    post(
                        token,
                        json.writeValueAsString(
                            mapOf("text" to "주민등록번호 $VALID_RRN, 카드번호 $VALID_CARD 안내"),
                        ),
                    ),
                )
        } finally {
            root.level = restoreLevel
            root.detachAppender(probe)
            detached.forEach { root.addAppender(it) }
            probe.stop()
        }

        assertThat(response.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).isNotEmpty()

        assertThat(probe.sawPositiveControl())
            .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
            .isTrue()
        assertThat(probe.traceEvents())
            .withFailMessage("강제 TRACE 에서 TRACE 이벤트가 0건이다 — 루트 레벨 상향이 먹지 않았다")
            .isNotZero()
        assertThat(probe.hits())
            .withFailMessage(
                "강제 TRACE 로그에 개인정보 검출값이 실렸다 — 아래가 지목이다.%n%s",
                probe.report(),
            ).isEmpty()
    }

    private fun newAccount(): String {
        val email = "personal-data-log@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to "correct horse battery"))
        send(post(null, credentials, SIGNUP_PATH))
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        val login = send(post(null, credentials, LOGIN_PATH))
        return json.readValue(login.body(), Map::class.java)["access_token"].toString()
    }

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

    private companion object {
        const val DOCUMENTS_PATH = "/documents"
        const val SIGNUP_PATH = "/auth/signup"
        const val LOGIN_PATH = "/auth/login"
        const val UNPROCESSABLE = 422
        const val PERSONAL_DATA_KINDS_HEADER = "X-Personal-Data-Kinds"

        const val RRN_AXIS = "주민등록번호"
        const val CARD_AXIS = "카드번호"

        /** 검증식(모듈러스 11)을 통과하는 합성 주민등록번호 — 실존 인물과 무관하다. */
        const val VALID_RRN = "900101-1234568"

        /** Luhn 을 통과하는 표준 테스트 카드번호(Visa 공개 테스트 번호). */
        const val VALID_CARD = "4111-1111-1111-1111"

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_personal_data_log") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

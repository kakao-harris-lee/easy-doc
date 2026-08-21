package kr.easydoc.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
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

/** 깨진 PHC 를 만난 로그에 해시·비밀번호·이메일이 실리지 않는다 — privacy-gate L-1 / 게이트 21 B1. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordHashLogLeakReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("깨진 PHC 로 로그인해도 로그에 PHC·평문 비밀번호·이메일이 남지 않는다 (양성 대조 포함)")
    fun `깨진 해시가 로그로 새지 않는다`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            BROKEN_HASHES.forEachIndexed { index, broken ->
                val email = "leak$index@example.test"
                post("/auth/signup", credentials(email, PASSWORD))
                database.execute("UPDATE users SET password_hash = '$broken' WHERE email = '$email'")

                LoggerFactory.getLogger(javaClass).warn(POSITIVE_CONTROL_MARKER)
                val response = post("/auth/login", credentials(email, PASSWORD))

                assertThat(response.statusCode())
                    .withFailMessage("깨진 PHC 로그인이 %d 로 나갔다 — 실패 갈래가 구분된다", response.statusCode())
                    .isEqualTo(UNAUTHORIZED)
            }

            val captured = appender.list.joinToString("\n") { render(it) }

            assertThat(captured)
                .withFailMessage("표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다")
                .contains(POSITIVE_CONTROL_MARKER)

            leakCandidates().forEach { (label, needle) ->
                assertThat(captured)
                    .withFailMessage("로그에 %s 가 실렸다 — L-1 이 걱정한 유출이 실제로 일어났다", label)
                    .doesNotContain(needle)
            }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

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

    /** (라벨, 로그에 있으면 안 되는 문자열). */
    private fun leakCandidates(): List<Pair<String, String>> =
        BROKEN_HASHES.map { "주입한 깨진 PHC" to it } +
            listOf(
                "PHC 접두사" to "\$argon2id\$v=",
                "평문 비밀번호" to PASSWORD,
                "이메일" to "@example.test",
            )

    private fun credentials(
        email: String,
        password: String,
    ): String = json.writeValueAsString(mapOf("email" to email, "password" to password))

    private fun post(
        path: String,
        payload: String,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )

    companion object {
        private const val UNAUTHORIZED = 401
        private const val PASSWORD = "correct horse battery"

        /** 이 문자열이 캡처에 없으면 「유출 0건」은 캡처가 비었다는 뜻이다. */
        private const val POSITIVE_CONTROL_MARKER = "L1-LOG-CAPTURE-ALIVE"

        /**
         * 두 갈래를 넣는다 — PHC 로 보이지도 않는 값과, 형식은 맞는데 잘린 값.
         * 라이브러리가 파싱 어느 단계에서 실패하든 메시지에 원본을 싣지 않아야 한다.
         */
        private val BROKEN_HASHES =
            listOf(
                "NOT-A-PHC-STRING",
                "\$argon2id\$v=19\$m=65536,t=3,p=4\$TRUNCATED",
            )

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("auth_log_leak") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

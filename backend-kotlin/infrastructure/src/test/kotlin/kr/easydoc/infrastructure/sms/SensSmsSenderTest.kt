package kr.easydoc.infrastructure.sms

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NAVER SENS SMS v2 어댑터 — 로컬 스텁 서버로 서명·본문·상태 판정·타임아웃을 잰다. */
class SensSmsSenderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val clock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC)
    private val settings =
        SensSmsSettings(
            serviceId = "svc-test",
            accessKey = "access-test",
            secretKey = Secret("secret-test"),
            fromNumber = "01000000000",
            timeout = Duration.ofSeconds(2),
            baseUrl = "http://127.0.0.1:${server.address.port}",
        )
    private val sender = SensSmsSender(settings, clock)

    @AfterEach
    fun close() {
        server.stop(0)
    }

    @Test
    fun `서명·본문·헤더가 SENS v2 계약대로 나간다`() {
        var capturedBody: String? = null
        server.createContext("/sms/v2/services/svc-test/messages") { exchange ->
            capturedBody = String(exchange.requestBody.readAllBytes())
            val expectedTimestamp = clock.millis().toString()
            assertThat(exchange.requestHeaders.getFirst("x-ncp-apigw-timestamp")).isEqualTo(expectedTimestamp)
            assertThat(exchange.requestHeaders.getFirst("x-ncp-iam-access-key")).isEqualTo("access-test")
            assertThat(exchange.requestHeaders.getFirst("x-ncp-apigw-signature-v2"))
                .isEqualTo(expectedSignature(expectedTimestamp))
            val response = """{"statusCode":"202"}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        sender.send("01099998888", "123456", 5)

        assertThat(capturedBody)
            .contains("\"to\":\"01099998888\"", "\"from\":\"01000000000\"", "\"countryCode\":\"82\"")
        assertThat(capturedBody).contains("123456")
    }

    @Test
    fun `202가 아닌 statusCode는 ExternalServiceUnavailableException이다`() {
        server.createContext("/sms/v2/services/svc-test/messages") { exchange ->
            val response = """{"statusCode":"400"}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        assertThatThrownBy { sender.send("01099998888", "123456", 5) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    @Test
    fun `연결 실패는 ExternalServiceUnavailableException이다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val deadSender = SensSmsSender(settings.copy(baseUrl = "http://127.0.0.1:$closedPort"), clock)

        assertThatThrownBy { deadSender.send("01099998888", "123456", 5) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    @Test
    fun `로그에 전화번호와 인증코드가 남지 않는다`() {
        server.createContext("/sms/v2/services/svc-test/messages") { exchange ->
            val response = """{"statusCode":"500"}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        val events = capture { runCatching { sender.send("01099998888", "123456", 5) } }

        val rendered = render(events)
        assertThat(rendered).doesNotContain("01099998888")
        assertThat(rendered).doesNotContain("123456")
    }

    private fun expectedSignature(timestamp: String): String {
        val value = "POST /sms/v2/services/svc-test/messages\n$timestamp\naccess-test"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret-test".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    /**
     * [SensSmsSender] 자신의 로거만 잰다 — `ExtractionLoggingTest` 처럼 루트 로거를 TRACE 로
     * 올리면 Spring `RestClient` 의 요청/응답 와이어 로깅(운영에서는 TRACE 를 켜지 않는 한
     * 나오지 않는다)까지 같이 잡혀, 이 어댑터가 남기지 않는 것까지 실패로 보인다 — 우리가
     * 지키려는 불변식은 "이 클래스의 `log.warn` 호출에 번호·코드가 없다"이지 프레임워크
     * 배선 로그 전체가 아니다.
     */
    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(SensSmsSender::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)

        logger.level = Level.TRACE
        try {
            block()
        } finally {
            logger.level = previousLevel
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    private fun render(events: List<ILoggingEvent>): String =
        buildString {
            events.forEach { event ->
                appendLine(event.formattedMessage)
                var throwable = event.throwableProxy
                while (throwable != null) {
                    appendLine("${throwable.className}: ${throwable.message}")
                    throwable = throwable.cause
                }
            }
        }
}

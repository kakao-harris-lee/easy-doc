package kr.easydoc.infrastructure.telegram

import tools.jackson.databind.json.JsonMapper
import com.sun.net.httpserver.HttpServer
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

class TelegramOperatorNotificationAdapterTest {
    private val stub = StubTelegramServer()

    @AfterEach
    fun closeStub() {
        stub.close()
    }

    @Test
    fun `sendMessage는 토큰 경로와 두 JSON 필드만 한 번 본다`() {
        stub.replyWith(200, """{"ok":true}""")
        val adapter = adapter(readTimeout = Duration.ofSeconds(1))

        adapter.send("가입 <plain> & text")

        val request = stub.received.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/botfake-bot-token/sendMessage")
        val body = JsonMapper.builder().build().readTree(request.body)
        assertThat(body.size()).isEqualTo(2)
        assertThat(body.path("chat_id").asString()).isEqualTo("-100123")
        assertThat(body.path("text").asString()).isEqualTo("가입 <plain> & text")
        assertThat(body.has("parse_mode")).isFalse()
    }

    @Test
    fun `HTTP 오류와 비정상 응답은 실패이고 재시도하지 않는다`() {
        listOf(
            Triple(500, """{"ok":true}""", "http"),
            Triple(200, """{"ok":false}""", "not ok"),
            Triple(200, """{"ok":"true"}""", "non-boolean ok"),
            Triple(200, """{"ok":true""", "malformed"),
            Triple(204, "", "null body"),
        ).forEach { (status, body, label) ->
            stub.replyWith(status, body)
            val before = stub.received.size
            assertThatThrownBy { adapter().send("secret provider response") }
                .withFailMessage(label)
                .isInstanceOf(RuntimeException::class.java)
            assertThat(stub.received.size).isEqualTo(before + 1)
        }
    }

    @Test
    fun `연결 실패와 읽기 지연은 실패다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val settings = properties()
        assertThatThrownBy {
            TelegramOperatorNotificationAdapter(
                TelegramConfiguration().telegramRestClient(settings, "http://127.0.0.1:$closedPort"),
                settings,
            ).send("message")
        }.isInstanceOf(RuntimeException::class.java)

        stub.replyWith(200, """{"ok":true}""", Duration.ofMillis(250))
        assertThatThrownBy { adapter(readTimeout = Duration.ofMillis(50)).send("message") }
            .isInstanceOf(RuntimeException::class.java)
    }

    private fun properties(
        readTimeout: Duration = Duration.ofSeconds(1),
    ): TelegramProperties =
        TelegramProperties(
            enabled = true,
            botToken = Secret("fake-bot-token"),
            chatId = "-100123",
            connectTimeout = Duration.ofMillis(100),
            readTimeout = readTimeout,
        )

    private fun adapter(
        baseUrl: String = stub.baseUrl,
        readTimeout: Duration = Duration.ofSeconds(1),
    ): TelegramOperatorNotificationAdapter {
        val settings = properties(readTimeout = readTimeout)
        return TelegramOperatorNotificationAdapter(
            TelegramConfiguration().telegramRestClient(settings, baseUrl), settings,
        )
    }

    private class StubTelegramServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private var status = 200
        private var body = """{"ok":true}"""
        private var delay = Duration.ZERO
        val received = mutableListOf<RecordedTelegramRequest>()
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                exchange.use {
                    received += RecordedTelegramRequest(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                    )
                    if (!delay.isZero) Thread.sleep(delay.toMillis())
                    val payload = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
            }
            server.start()
        }

        fun replyWith(status: Int, body: String, delay: Duration = Duration.ZERO) {
            this.status = status
            this.body = body
            this.delay = delay
        }

        override fun close() = server.stop(0)
    }

    private data class RecordedTelegramRequest(
        val method: String,
        val path: String,
        val body: String,
    )
}

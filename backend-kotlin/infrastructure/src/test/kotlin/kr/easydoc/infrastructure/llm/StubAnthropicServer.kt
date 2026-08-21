package kr.easydoc.infrastructure.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/** 어댑터를 HTTP 수준에서 시험하기 위한 스텁 서버. */
internal class StubAnthropicServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, ANY_PORT), 0)

    private val recorded = mutableListOf<RecordedRequest>()

    private var reply: StubReply = StubReply(status = 200, body = "{}")

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://$LOOPBACK:${server.address.port}"

    /** 스텁이 받은 요청들. 순서를 보존한다. */
    val received: List<RecordedRequest> get() = recorded.toList()

    /** 요청이 하나뿐임을 전제로 그것을 돌려준다. */
    fun singleRequest(): RecordedRequest = recorded.single()

    fun replyWith(
        status: Int = 200,
        body: String,
        delay: Duration = Duration.ZERO,
    ) {
        reply = StubReply(status = status, body = body, delay = delay)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            recorded +=
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.toString(),
                    headers = exchange.requestHeaders.mapValues { (_, values) -> values.toList() },
                    body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                )

            if (!reply.delay.isZero) {
                Thread.sleep(reply.delay.toMillis())
            }

            val payload = reply.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, payload.size.toLong())
            exchange.responseBody.write(payload)
        }
    }

    override fun close() {
        server.stop(0)
    }

    private data class StubReply(
        val status: Int,
        val body: String,
        val delay: Duration = Duration.ZERO,
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ANY_PORT = 0
    }
}

/** 스텁이 받은 요청 한 건. */
internal data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    /** 헤더 이름은 대소문자를 가리지 않는다(`HttpExchange` 가 `X-Api-Key` 로 정규화한다). */
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    /** 요청 라인 + 헤더 + 본문을 한 문자열로 이어 붙인다. */
    fun wireDump(): String =
        buildString {
            appendLine("$method $path")
            headers.forEach { (key, values) -> values.forEach { appendLine("$key: $it") } }
            appendLine()
            append(body)
        }
}

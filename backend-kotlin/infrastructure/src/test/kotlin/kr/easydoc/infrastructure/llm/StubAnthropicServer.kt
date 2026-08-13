package kr.easydoc.infrastructure.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 어댑터를 **HTTP 수준**에서 시험하기 위한 스텁 서버.
 *
 * ## MockWebServer·WireMock 을 쓰지 않은 이유
 *
 * 둘 다 Spring Boot BOM 이 관리하지 않는다(4.1.0 BOM 실측 — okhttp/mockwebserver/wiremock
 * 좌표 없음). 즉 버전을 우리가 직접 골라야 하는데, 이 저장소는 그렇게 골랐다가 이미 한 번
 * 데였다: kotlinx-serialization 1.11.0 을 직접 박았더니 **테스트 클래스패스의 kotlin-stdlib
 * 만** 올라가 컴파일과 테스트 실행이 서로 다른 stdlib 을 보게 됐다
 * (`gradle/libs.versions.toml` 주석). MockWebServer 는 Kotlin 으로 쓰였고 kotlin-stdlib 을
 * 끌고 오므로 같은 함정이 그대로 있다.
 *
 * JDK 내장 `com.sun.net.httpserver` 는 의존성이 0 이면서 **진짜 소켓·진짜 바이트**다.
 * "요청 본문에 마스킹된 텍스트만 실렸는가", "API 키가 헤더 밖으로 새지 않는가" 같은 질문은
 * 바로 그 층에서 물어야 의미가 있다 — `MockRestServiceServer` 처럼 RequestFactory 를
 * 갈아 끼우는 방식이면 직렬화와 헤더 조립을 건너뛰어 정작 확인하려는 것을 확인하지 못한다.
 *
 * **한계**: HTTP/1.1 전용이고 녹화 DSL 이 없다(그래서 아래 [received] 를 손으로 모은다).
 * 다중 요청 시나리오가 필요해지면 그때 다시 판단한다.
 */
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

    /**
     * 요청 라인 + 헤더 + 본문을 한 문자열로 이어 붙인다.
     *
     * "API 키가 **어디에도** 실리지 않았다"를 확인하려면 본문만 봐서는 부족하다 —
     * 경로(쿼리 문자열)와 다른 헤더까지 한 번에 훑어야 선언한 범위와 실제 검사 범위가 같아진다.
     */
    fun wireDump(): String =
        buildString {
            appendLine("$method $path")
            headers.forEach { (key, values) -> values.forEach { appendLine("$key: $it") } }
            appendLine()
            append(body)
        }
}

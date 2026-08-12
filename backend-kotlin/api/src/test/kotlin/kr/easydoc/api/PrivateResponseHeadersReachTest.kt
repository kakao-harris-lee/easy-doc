package kr.easydoc.api

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * **H-1 실측 — 전역 헤더 필터가 어디까지 닿는가** (`api-contract-freeze` §5.2,
 * 계약 `x-global-response-headers.x-phase3-measurement`).
 *
 * ## 왜 MockMvc 가 아닌가
 *
 * 2026-08-12 리더 판정이 사적 응답 헤더를 전역으로 바꾸면서 **실패 양상이 바뀌었다.**
 * 위험이 "누가 한 자리를 빠뜨렸는가"에서 **"필터가 그 응답까지 닿는가"**로 옮겨갔다.
 * 전자는 목록 대조로 잡히지만 후자는 실행해야만 잡힌다.
 *
 * MockMvc 는 서블릿 컨테이너를 띄우지 않는다. 필터를 손으로 체인에 끼워 넣고 디스패처를
 * 부를 뿐이라, **컨테이너가 필터 앞이나 바깥에서 만드는 응답**을 재현하지 못한다. 그래서
 * MockMvc 로 재면 *측정한 것처럼 보이는 통과*가 나온다. 여기서는
 * `@SpringBootTest(RANDOM_PORT)` 로 실제 포트를 열고 **원시 소켓**으로 보낸다 — HTTP
 * 클라이언트 라이브러리는 깨진 요청을 교정해 버려 malformed 케이스를 만들지 못한다.
 *
 * ## 실측 결과 (2026-08-12, Tomcat 11.0.22 / Spring Boot 4.1.0)
 *
 * | 응답 | 만드는 곳 | 서블릿 필터 | 최종 |
 * |---|---|---|---|
 * | 매칭 핸들러 없는 404 (`GET /nope`) | DispatcherServlet | 닿음 | **있음** |
 * | 미지원 Content-Type 415 | DispatcherServlet | 닿음 | **있음** |
 * | 요청 본문 상한 초과 413 | DispatcherServlet (`checkMultipart`) | 닿음 | **있음** |
 * | CORS 프리플라이트 200 | `CorsFilter` | 닿음 | **있음** |
 * | `sendError` → `/error` ERROR 디스패치 | 컨테이너 재디스패치 | 닿음 | **있음** |
 * | 요청 대상에 금지 문자 → 400 | Tomcat 요청 줄 파싱 | **못 닿음** | **있음**(밸브) |
 * | 요청 줄 자체가 쓰레기 → 400 | Tomcat 요청 줄 파싱 | **못 닿음** | **있음**(밸브) |
 * | 헤더 상한 초과 → 400 | Tomcat 헤더 파싱 | **못 닿음** | **있음**(밸브) |
 *
 * **필터만으로는 구멍이 있었다.** 아래 세 줄이 그 구멍이고, 요청이 서블릿에 매핑되지 않아
 * 필터 체인이 시작조차 하지 않는 자리다. 계약을 좁히는 대신
 * `PrivateResponseHeadersValve`(Tomcat Engine 밸브)를 한 층 더 두어 닿게 만들었다 —
 * `api-contract-freeze` §5.2 의 "① 필터 배치로 닿게 만든다"에 해당한다. **미해결로 남은
 * 자리는 없다.**
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // 413 을 만들기 위한 상한. 기본값(10MB)으로는 테스트가 10MB를 소켓에 밀어 넣어야 한다.
        "spring.servlet.multipart.max-request-size=1KB",
        "spring.servlet.multipart.max-file-size=1KB",
    ],
)
class PrivateResponseHeadersReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @DisplayName("매칭 핸들러 없는 404 에 두 헤더가 있다")
    fun `핸들러 없는 404 에 닿는다`() {
        val response = exchange(RawHttp.get("/nope", port))

        assertThat(response.statusCode).isEqualTo(NOT_FOUND)
        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("미지원 Content-Type 415 에 두 헤더가 있다")
    fun `415 에 닿는다`() {
        val response =
            exchange(
                RawHttp.post(
                    path = "/__probe/body",
                    port = port,
                    contentType = "text/plain",
                    body = "본문".toByteArray(StandardCharsets.UTF_8),
                ),
            )

        assertThat(response.statusCode).isEqualTo(UNSUPPORTED_MEDIA_TYPE)
        assertPrivateHeaders(response)
    }

    /**
     * 요청 본문 상한 초과.
     *
     * Tomcat 이 `MultipartConfigElement` 의 상한을 강제해 던지고, Spring 이
     * `MaxUploadSizeExceededException` 으로 감싼다. **핸들러 매핑보다 앞**
     * (`DispatcherServlet.checkMultipart`)에서 터지지만 필터 체인 **안**이라 헤더는 이미
     * 응답 객체에 실려 있다 — 그 사실을 여기서 고정한다.
     */
    @Test
    @DisplayName("요청 본문 상한 초과 413 에 두 헤더가 있다")
    fun `413 에 닿는다`() {
        val response = exchange(RawHttp.oversizedMultipart("/__probe/body", port))

        assertThat(response.statusCode)
            .withFailMessage("본문 상한 초과가 413 이 아니다 (실제 %d) — 상한 설정이나 예외 매핑을 확인한다", response.statusCode)
            .isEqualTo(PAYLOAD_TOO_LARGE)
        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("CORS 프리플라이트(OPTIONS) 응답에 두 헤더가 있다")
    fun `프리플라이트에 닿는다`() {
        val response = exchange(RawHttp.preflight("/health", port))

        assertThat(response.statusCode).isEqualTo(OK)
        assertThat(response.values("access-control-allow-origin"))
            .withFailMessage("프리플라이트가 CORS 필터에서 처리되지 않았다 — 이 측정의 전제가 깨졌다")
            .containsExactly(ALLOWED_ORIGIN)
        assertPrivateHeaders(response)
    }

    /**
     * H-1 후보 원인 ⓐ 의 직접 측정.
     *
     * `HttpServletResponse.sendError()` 는 컨테이너가 `/error` 로 **두 번째 디스패치**를
     * 돌게 만든다. `OncePerRequestFilter.shouldNotFilterErrorDispatch()` 기본값이 `true` 라
     * 그 디스패치에서는 필터가 돌지 않는 것이 기본 동작이다. 그 경우에도 첫 디스패치에서
     * 쓴 헤더가 남는지는 **컨테이너가 헤더를 보존한다는 전제**에 기댄 것이라, 우리는
     * 기본값을 뒤집고 `DispatcherType.ERROR` 도 등록해 전제를 하나 줄였다.
     *
     * 이 단언이 지키는 것은 그 조합이다. 둘 중 하나만 되돌려도 여기가 깨진다.
     */
    @Test
    @DisplayName("sendError 가 만든 ERROR 디스패치 응답에 두 헤더가 있다")
    fun `ERROR 디스패치에 닿는다`() {
        val response = exchange(RawHttp.get("/__probe/send-error", port))

        assertThat(response.statusCode).isEqualTo(SERVICE_UNAVAILABLE)
        assertPrivateHeaders(response)
    }

    /**
     * **이 테스트가 H-1 의 핵심이다.**
     *
     * 요청 줄·헤더 블록이 깨진 요청은 `Http11InputBuffer` 파싱 단계에서 400 이 되고,
     * Tomcat 은 그것을 서블릿에 매핑하지 않는다. 즉 **서블릿 필터 체인이 시작조차 하지
     * 않는다.** 전역 부착을 필터 하나로만 구현하면 여기가 조용히 비고, 증상은 "malformed
     * 요청에만 헤더가 없다" 하나뿐이라 어떤 계약 테스트로도 드러나지 않는다 — 계약이
     * 경고한 *"전역이라고 적혀 있는 채로 비어 있는"* 상태 그대로다.
     *
     * `PrivateResponseHeadersValve`(Tomcat Engine 밸브)가 이 자리를 덮는다. 밸브를 빼면
     * 이 테스트만 깨진다.
     *
     * 세 갈래를 함께 보는 이유는 파싱 실패 지점이 서로 달라서다 — 요청 대상 문자 검사,
     * 요청 줄 형식, 헤더 크기 상한. 한 갈래만 두면 다른 갈래에서 도달 경로가 달라져도
     * 모른다.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MalformedRequest::class)
    @DisplayName("서블릿에 매핑되지 않는 malformed 400 에도 두 헤더가 있다 (밸브가 덮는 자리)")
    fun `malformed 400 에도 닿는다`(kind: MalformedRequest) {
        val response = exchange(kind.build(port))

        assertThat(response.statusCode)
            .withFailMessage("Tomcat 이 이 요청을 400 으로 거절하지 않았다 (실제 %d) — 측정 전제가 깨졌다", response.statusCode)
            .isEqualTo(BAD_REQUEST)
        assertPrivateHeaders(response)
    }

    private fun assertPrivateHeaders(response: RawHttpResponse) {
        // 값이 아니라 개수까지 본다 — 이중 부착(`no-store, no-store`)은 값만 보면 통과한다.
        assertThat(response.values(CACHE_CONTROL))
            .withFailMessage("Cache-Control 이 %s 다 — 계약은 no-store 하나를 요구한다", response.values(CACHE_CONTROL))
            .containsExactly("no-store")
        assertThat(response.values(X_CONTENT_TYPE_OPTIONS))
            .withFailMessage("X-Content-Type-Options 가 %s 다", response.values(X_CONTENT_TYPE_OPTIONS))
            .containsExactly("nosniff")
    }

    /** 원시 소켓으로 보내고 응답 **헤더 블록까지만** 읽는다. 본문은 이 측정의 대상이 아니다. */
    private fun exchange(request: ByteArray): RawHttpResponse =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), SOCKET_TIMEOUT_MILLIS)
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            parseHead(readHead(socket.getInputStream()))
        }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        private const val CACHE_CONTROL = "cache-control"
        private const val X_CONTENT_TYPE_OPTIONS = "x-content-type-options"
        private const val ALLOWED_ORIGIN = "http://localhost:5173"

        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val PAYLOAD_TOO_LARGE = 413
        private const val UNSUPPORTED_MEDIA_TYPE = 415
        private const val SERVICE_UNAVAILABLE = 503

        /**
         * 이 측정에는 DB 가 필요 없지만 앱 기동에는 필요하다 — Flyway 가 컨텍스트 초기화
         * 중에 돌기 때문이다. `ApiStartupWithDatabaseTest` 와 같은 컨테이너를 공유한다.
         */
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            val database = PostgresTestSupport.createEmptyDatabase("private_headers_reach")
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/**
 * 서블릿에 매핑되지 않는 요청 세 갈래. **파싱이 실패하는 단계가 서로 다르다** —
 * 요청 대상 문자 검사, 요청 줄 형식, 헤더 크기 상한. 한 갈래만 두면 다른 갈래에서 도달
 * 경로가 달라져도 모른다.
 */
enum class MalformedRequest {
    /**
     * 요청 대상에 금지 문자(`<`).
     *
     * Tomcat 의 `relaxedPathChars` 기본값이 비어 있어 거절된다
     * ("Invalid character found in the request target" — `Http11InputBuffer.parseRequestLine`).
     */
    INVALID_REQUEST_TARGET {
        override fun build(port: Int): ByteArray =
            RawHttp.raw("GET /health<bad> HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
    },

    /** 요청 줄이 HTTP 로 보이지도 않는다 — 평문 포트에 TLS 핸드셰이크가 들어온 상황이 실제 사례다. */
    GARBAGE_REQUEST_LINE {
        override fun build(port: Int): ByteArray = RawHttp.raw(" ÿ garbage\r\n\r\n")
    },

    /** 헤더 블록이 `server.max-http-request-header-size`(기본 8KB)를 넘는다. */
    OVERSIZED_HEADER {
        override fun build(port: Int): ByteArray =
            RawHttp.raw(
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "X-Big: ${"a".repeat(OVERSIZED_HEADER_BYTES)}\r\nConnection: close\r\n\r\n",
            )
    },
    ;

    abstract fun build(port: Int): ByteArray

    private companion object {
        /** 기본 상한(8KB)을 확실히 넘기되, 소켓 송신 버퍼 안에 들어가 쓰기가 막히지 않는 크기. */
        const val OVERSIZED_HEADER_BYTES = 20_000
    }
}

/** 응답의 상태 줄과 헤더. 헤더 이름은 소문자로 정규화하고 **값 목록**을 그대로 보존한다. */
private class RawHttpResponse(
    val statusCode: Int,
    private val headers: Map<String, List<String>>,
) {
    fun values(lowercaseName: String): List<String> = headers[lowercaseName] ?: emptyList()
}

/** 요청 바이트를 손으로 만든다. HTTP 클라이언트는 깨진 요청을 교정해 버려 쓸 수 없다. */
private object RawHttp {
    private const val CRLF = "\r\n"
    private const val BOUNDARY = "easydocboundary"

    /** multipart 상한(1KB)을 확실히 넘기는 크기. 소켓 버퍼 안에 들어가 쓰기가 막히지 않는다. */
    private const val OVERSIZED_PART_BYTES = 4096

    fun get(
        path: String,
        port: Int,
    ): ByteArray = head("GET", path, port, emptyList()).toByteArray(StandardCharsets.ISO_8859_1)

    fun post(
        path: String,
        port: Int,
        contentType: String,
        body: ByteArray,
    ): ByteArray {
        val head =
            head(
                "POST",
                path,
                port,
                listOf("Content-Type: $contentType", "Content-Length: ${body.size}"),
            )
        return head.toByteArray(StandardCharsets.ISO_8859_1) + body
    }

    fun preflight(
        path: String,
        port: Int,
    ): ByteArray =
        head(
            "OPTIONS",
            path,
            port,
            listOf("Origin: http://localhost:5173", "Access-Control-Request-Method: GET"),
        ).toByteArray(StandardCharsets.ISO_8859_1)

    fun oversizedMultipart(
        path: String,
        port: Int,
    ): ByteArray {
        val body =
            buildString {
                append("--").append(BOUNDARY).append(CRLF)
                append("""Content-Disposition: form-data; name="file"; filename="big.txt"""").append(CRLF)
                append("Content-Type: text/plain").append(CRLF).append(CRLF)
                append("x".repeat(OVERSIZED_PART_BYTES)).append(CRLF)
                append("--").append(BOUNDARY).append("--").append(CRLF)
            }.toByteArray(StandardCharsets.ISO_8859_1)
        return post(path, port, "multipart/form-data; boundary=$BOUNDARY", body)
    }

    fun raw(text: String): ByteArray = text.toByteArray(StandardCharsets.ISO_8859_1)

    private fun head(
        method: String,
        path: String,
        port: Int,
        extraHeaders: List<String>,
    ): String =
        buildString {
            append("$method $path HTTP/1.1$CRLF")
            append("Host: 127.0.0.1:$port$CRLF")
            extraHeaders.forEach { append("$it$CRLF") }
            // 응답 헤더 블록만 읽고 소켓을 닫으므로 keep-alive 를 쓰지 않는다.
            append("Connection: close$CRLF$CRLF")
        }
}

/** 응답 헤더 블록(`\r\n\r\n` 까지)만 읽는다. 본문까지 읽으면 큰 응답에서 무의미하게 기다린다. */
private fun readHead(input: InputStream): String {
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val next = input.read()
        check(next >= 0) { "응답 헤더가 끝나기 전에 연결이 닫혔다: $head" }
        head.append(Char(next))
    }
    return head.toString()
}

private fun parseHead(head: String): RawHttpResponse {
    val lines = head.trim().split("\r\n")
    // "HTTP/1.1 404 Not Found" — 두 번째 토큰이 상태 코드다. 사유 문구는 비어 있을 수 있다.
    val statusCode =
        lines
            .first()
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?: error("상태 줄을 읽지 못했다: ${lines.firstOrNull()}")
    val headers = mutableMapOf<String, MutableList<String>>()
    lines.drop(1).forEach { line ->
        val separator = line.indexOf(':')
        if (separator > 0) {
            headers
                .getOrPut(line.take(separator).trim().lowercase()) { mutableListOf() }
                .add(line.substring(separator + 1).trim())
        }
    }
    return RawHttpResponse(statusCode, headers)
}

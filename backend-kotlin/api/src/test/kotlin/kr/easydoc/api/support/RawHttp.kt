package kr.easydoc.api.support

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 원시 소켓 HTTP 측정 도구. **전역 응답 계약이 어디까지 닿는지 재는 유일한 수단이다.**
 *
 * ## 왜 MockMvc 도 HTTP 클라이언트도 아닌가
 *
 * MockMvc 는 서블릿 컨테이너를 띄우지 않는다. 필터를 손으로 체인에 끼워 넣고 디스패처를
 * 부를 뿐이라 **컨테이너가 필터 앞이나 바깥에서 만드는 응답**을 재현하지 못한다. 그래서
 * MockMvc 로 재면 *측정한 것처럼 보이는 통과*가 나온다 — 전역 선언은 초록인데 실제 도달은
 * 일부뿐인 상태가 그대로 남는다.
 *
 * HTTP 클라이언트 라이브러리도 쓸 수 없다. 요청 줄이 깨진 요청을 만들어야 하는데
 * 클라이언트는 그것을 교정하거나 아예 보내기를 거부한다.
 *
 * ## 두 측정이 같은 도구를 쓴다
 *
 * [kr.easydoc.api.PrivateResponseHeadersReachTest] (헤더)와
 * [kr.easydoc.api.ContractErrorBodyReachTest] (본문)가 이 파일을 공유한다. 도구가 갈리면
 * "헤더는 닿는데 본문은 안 닿는다"가 도구 차이인지 실제 차이인지 가릴 수 없다.
 */
object RawHttp {
    private const val CRLF = "\r\n"
    private const val BOUNDARY = "easydocboundary"
    private const val LOOPBACK = "127.0.0.1"
    private const val SOCKET_TIMEOUT_MILLIS = 10_000

    /** multipart 상한(1KB)을 확실히 넘기는 크기. 소켓 버퍼 안에 들어가 쓰기가 막히지 않는다. */
    private const val OVERSIZED_PART_BYTES = 4096

    fun get(
        path: String,
        port: Int,
        extraHeaders: List<String> = emptyList(),
    ): ByteArray = head("GET", path, port, extraHeaders).toByteArray(StandardCharsets.ISO_8859_1)

    fun head(
        path: String,
        port: Int,
    ): ByteArray = head("HEAD", path, port, emptyList()).toByteArray(StandardCharsets.ISO_8859_1)

    fun post(
        path: String,
        port: Int,
        contentType: String,
        body: ByteArray,
    ): ByteArray {
        val requestHead =
            head(
                "POST",
                path,
                port,
                listOf("Content-Type: $contentType", "Content-Length: ${body.size}"),
            )
        return requestHead.toByteArray(StandardCharsets.ISO_8859_1) + body
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

    /** 요청 바이트를 그대로 보내고 상태 줄·헤더·**본문**을 읽는다. */
    fun exchange(
        port: Int,
        request: ByteArray,
    ): RawHttpResponse =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), SOCKET_TIMEOUT_MILLIS)
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            val input = socket.getInputStream()
            val (statusCode, headers) = parseHead(readHead(input))
            RawHttpResponse(statusCode, headers, readBody(input, headers))
        }

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
            // 본문 끝을 EOF 로 알기 위해 keep-alive 를 쓰지 않는다.
            append("Connection: close$CRLF$CRLF")
        }
}

/**
 * 응답의 상태 줄·헤더·본문.
 *
 * 헤더 이름은 소문자로 정규화하되 **값 목록을 그대로 보존한다** — 이중 부착
 * (`no-store, no-store`)은 값만 보면 통과하고 개수를 봐야 잡힌다.
 */
class RawHttpResponse(
    val statusCode: Int,
    private val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun values(lowercaseName: String): List<String> = headers[lowercaseName] ?: emptyList()

    /** 본문을 UTF-8 문자열로 읽는다. 이 API 가 내보내는 오류 본문은 전부 UTF-8 JSON 이다. */
    val bodyText: String get() = String(body, StandardCharsets.UTF_8)

    val contentType: String? get() = values("content-type").firstOrNull()
}

/** 응답 헤더 블록(`\r\n\r\n` 까지)을 읽는다. */
private fun readHead(input: InputStream): String {
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val next = input.read()
        check(next >= 0) { "응답 헤더가 끝나기 전에 연결이 닫혔다: $head" }
        head.append(Char(next))
    }
    return head.toString()
}

private fun parseHead(head: String): Pair<Int, Map<String, List<String>>> {
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
    return statusCode to headers
}

/**
 * 본문을 읽는다.
 *
 * `Content-Length` 가 있으면 정확히 그만큼만 읽는다. 없으면 `Connection: close` 를 보냈으므로
 * EOF 가 본문 경계다. **본문이 비어 있는 것과 본문이 없는 것을 구분하지 않는다** — 계약이
 * 요구하는 것은 `{"detail": ...}` 이고 둘 다 그것이 아니기 때문이다.
 */
private fun readBody(
    input: InputStream,
    headers: Map<String, List<String>>,
): ByteArray {
    val declaredLength = headers["content-length"]?.firstOrNull()?.toIntOrNull()
    val chunked = headers["transfer-encoding"]?.any { it.contains("chunked", ignoreCase = true) } == true
    return when {
        declaredLength != null -> input.readNBytes(declaredLength)
        chunked -> readChunked(input)
        else -> input.readBytes()
    }
}

private fun readChunked(input: InputStream): ByteArray {
    val body = mutableListOf<Byte>()
    var size = nextChunkSize(input)
    while (size > 0) {
        input.readNBytes(size).forEach(body::add)
        // 청크 뒤의 CRLF 를 버린다.
        readLine(input)
        size = nextChunkSize(input)
    }
    return body.toByteArray()
}

/** 청크 크기 줄을 읽는다. 마지막 청크(0)와 읽을 수 없는 줄을 모두 0으로 본다 — 둘 다 끝이다. */
private fun nextChunkSize(input: InputStream): Int =
    readLine(input)
        .substringBefore(';')
        .trim()
        .toIntOrNull(radix = HEX_RADIX)
        ?: 0

private const val HEX_RADIX = 16

private fun readLine(input: InputStream): String {
    val line = StringBuilder()
    while (!line.endsWith("\r\n")) {
        val next = input.read()
        if (next < 0) {
            break
        }
        line.append(Char(next))
    }
    return line.toString().trim()
}

/**
 * 서블릿에 매핑되지 않는 요청들. **파싱이 실패하는 단계가 서로 다르다** — 요청 대상 문자
 * 검사, 요청 줄 형식, 헤더 줄 형식, 헤더 크기 상한, `Host` 검증, 프로토콜 버전. 한 갈래만
 * 두면 다른 갈래에서 도달 경로가 달라져도 모른다.
 *
 * ## 계약이 든 7종 중 여기 없는 하나 (게이트 21 · contract-keeper §3-4)
 *
 * 계약 `x-global-response-headers.x-phase3-measurement.unreachable_by_filter.cases` 는
 * 이 자리를 **7종**으로 열거하는데 이 열거자는 **6개**다. 빠진 하나는 「알 수 없는 메서드
 * → 405」인데, 원시 소켓 실측 결과 **그것은 컨테이너가 거절하는 자리가 아니다** —
 * `FROB /health` 는 서블릿까지 도달해 Spring MVC 가 405 를 만든다(`Allow: GET` 이 붙고
 * 본문이 우리 `{"detail":"Method Not Allowed"}` 이며 Content-Type 에 밸브가 붙이는
 * `;charset=UTF-8` 이 없다). 즉 `reachable_by_filter` 소속이고 계약 쪽 분류가 사실과
 * 다르다. 계약 수정 권한은 `contract-keeper` 에 있으므로 여기서는 **열거자를 넣지 않고**
 * 그 경로를 [kr.easydoc.api.PrivateResponseHeadersReachTest] 의 도달 케이스로 따로 잰다.
 *
 * [expectedStatus] 는 이 요청이 어떤 상태 코드로 거절되는지다. 그 값이 달라지면 측정의
 * 전제가 깨진 것이므로 테스트가 먼저 그것을 알린다.
 */
enum class ContainerRejectedRequest(val expectedStatus: Int) {
    /**
     * 요청 대상에 금지 문자(`<`).
     *
     * Tomcat 의 `relaxedPathChars` 기본값이 비어 있어 거절된다
     * ("Invalid character found in the request target" — `Http11InputBuffer.parseRequestLine`).
     */
    INVALID_REQUEST_TARGET(BAD_REQUEST) {
        override fun build(port: Int): ByteArray =
            RawHttp.raw("GET /health<bad> HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
    },

    /** 요청 줄이 HTTP 로 보이지도 않는다 — 평문 포트에 TLS 핸드셰이크가 들어온 상황이 실제 사례다. */
    GARBAGE_REQUEST_LINE(BAD_REQUEST) {
        override fun build(port: Int): ByteArray = RawHttp.raw(" ÿ garbage\r\n\r\n")
    },

    /** 헤더 블록이 `server.max-http-request-header-size`(기본 8KB)를 넘는다. */
    OVERSIZED_HEADER(BAD_REQUEST) {
        override fun build(port: Int): ByteArray =
            RawHttp.raw(
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "X-Big: ${"a".repeat(OVERSIZED_HEADER_BYTES)}\r\nConnection: close\r\n\r\n",
            )
    },

    /**
     * 헤더 줄에 콜론이 없다. 요청 줄은 멀쩡하고 **헤더 블록 파싱**에서 거절된다.
     *
     * 실측: 400 이고 본문 Content-Type 이 `application/json;charset=UTF-8` — 밸브가 만드는
     * 형태다(서블릿 경로는 charset 을 붙이지 않는다).
     */
    HEADER_WITHOUT_COLON(BAD_REQUEST) {
        override fun build(port: Int): ByteArray =
            RawHttp.raw(
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "BrokenHeaderLine\r\nConnection: close\r\n\r\n",
            )
    },

    /** HTTP/1.1 인데 `Host` 가 없다. 요청 줄은 멀쩡하지만 헤더 검증에서 거절된다. */
    MISSING_HOST(BAD_REQUEST) {
        override fun build(port: Int): ByteArray = RawHttp.raw("GET /health HTTP/1.1\r\nConnection: close\r\n\r\n")
    },

    /** 알 수 없는 프로토콜 버전 — 505 다. 400 과 다른 코드로도 같은 자리를 지나는지 본다. */
    UNKNOWN_PROTOCOL(HTTP_VERSION_NOT_SUPPORTED) {
        override fun build(port: Int): ByteArray =
            RawHttp.raw("GET /health HTTP/9.9\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
    },
    ;

    abstract fun build(port: Int): ByteArray

    private companion object {
        /** 기본 상한(8KB)을 확실히 넘기되, 소켓 송신 버퍼 안에 들어가 쓰기가 막히지 않는 크기. */
        const val OVERSIZED_HEADER_BYTES = 20_000
    }
}

private const val BAD_REQUEST = 400
private const val HTTP_VERSION_NOT_SUPPORTED = 505

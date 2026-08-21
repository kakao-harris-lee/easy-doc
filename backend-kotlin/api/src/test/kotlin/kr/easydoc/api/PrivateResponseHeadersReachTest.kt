package kr.easydoc.api

import kr.easydoc.api.support.ContainerRejectedRequest
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.RawHttp
import kr.easydoc.api.support.RawHttpResponse
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
import java.nio.charset.StandardCharsets

/**
 * H-1 실측 — 전역 헤더 필터가 어디까지 닿는가 (`api-contract-freeze` §5.2,
 * 계약 `x-global-response-headers.x-phase3-measurement`).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [

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

    /** 요청 본문 상한 초과. */
    @Test
    @DisplayName("요청 본문 상한 초과 413 에 두 헤더가 있다")
    fun `413 에 닿는다`() {
        val response = exchange(RawHttp.oversizedMultipart("/__probe/body", port))

        assertThat(response.statusCode)
            .withFailMessage("본문 상한 초과가 413 이 아니다 (실제 %d) — 상한 설정이나 예외 매핑을 확인한다", response.statusCode)
            .isEqualTo(PAYLOAD_TOO_LARGE)
        assertPrivateHeaders(response)
    }

    /** 알 수 없는 HTTP 메서드는 컨테이너가 거절하지 않는다 (게이트 21 · contract-keeper §3-4). */
    @Test
    @DisplayName("알 수 없는 메서드 405(서블릿까지 도달)에 두 헤더가 있다")
    fun `알 수 없는 메서드에 닿는다`() {
        val request = "FROB /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
        val response = exchange(RawHttp.raw(request))

        assertThat(response.statusCode)
            .withFailMessage("알 수 없는 메서드가 %d 로 나갔다 — 거절 단계가 바뀌었으면 계약 분류를 다시 본다", response.statusCode)
            .isEqualTo(METHOD_NOT_ALLOWED)

        assertThat(response.values("allow"))
            .withFailMessage("405 에 Allow 가 없다 — 서블릿이 만든 응답이 아니다(계약의 unreachable 분류가 맞을 수 있다)")
            .isNotEmpty()
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

    /** H-1 후보 원인 ⓐ 의 직접 측정. */
    @Test
    @DisplayName("sendError 가 만든 ERROR 디스패치 응답에 두 헤더가 있다")
    fun `ERROR 디스패치에 닿는다`() {
        val response = exchange(RawHttp.get("/__probe/send-error", port))

        assertThat(response.statusCode).isEqualTo(SERVICE_UNAVAILABLE)
        assertPrivateHeaders(response)
    }

    /** 이 테스트가 H-1 의 핵심이다. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ContainerRejectedRequest::class)
    @DisplayName("서블릿에 매핑되지 않는 컨테이너 거절 응답에도 두 헤더가 있다 (밸브가 덮는 자리)")
    fun `컨테이너 거절 응답에도 닿는다`(kind: ContainerRejectedRequest) {
        val response = exchange(kind.build(port))

        assertThat(response.statusCode)
            .withFailMessage(
                "Tomcat 이 이 요청을 %d 로 거절하지 않았다 (실제 %d) — 측정 전제가 깨졌다",
                kind.expectedStatus,
                response.statusCode,
            ).isEqualTo(kind.expectedStatus)
        assertPrivateHeaders(response)
    }

    private fun assertPrivateHeaders(response: RawHttpResponse) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            val observed = response.values(header.lowercase())
            assertThat(observed)
                .withFailMessage("%s 가 %s 다 — 계약은 그 값 하나를 요구한다", header, observed)
                .containsExactly(value)
        }
    }

    /** 원시 소켓으로 보내고 응답을 읽는다. */
    private fun exchange(request: ByteArray): RawHttpResponse = RawHttp.exchange(port, request)

    companion object {
        private const val ALLOWED_ORIGIN = "http://localhost:5173"

        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
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

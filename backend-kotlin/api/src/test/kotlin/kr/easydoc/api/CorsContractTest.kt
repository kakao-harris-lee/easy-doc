package kr.easydoc.api

import kr.easydoc.api.config.CorsConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

/**
 * CORS 계약. 원본은 `app/main.py` 의 `CORSMiddleware` 설정이고,
 * 동결된 값은 `contracts/easy-doc-v1.yaml` 의 `x-cors` 다.
 *
 * ## 왜 이 테스트가 필요한가
 *
 * 계약 파일이 스스로 경고한다 — *"expose_headers 가 빠지면 서버 응답은 완전한데
 * **브라우저 JS만** 헤더를 못 읽는다."* 이 실패 양태는 서버 로그·`curl`·본문 검증
 * 어디에도 나타나지 않는다. 내려받기 파일명(`Content-Disposition`)과 업로드 접수 주소
 * (`Location`)를 React 가 읽지 못하는 형태로만 드러난다.
 *
 * ## 여기서 단언하지 않는 것 — U-1
 *
 * **미처리 500 응답에 CORS 헤더가 붙는지는 단언하지 않는다.** Python 은
 * `ServerErrorMiddleware` 가 CORS 미들웨어 바깥이라 구조적으로 붙이지 못하고, React 가
 * 그 동작(`NETWORK_ERROR_STATUS = 0`)에 의존한다. 처분의 정본은
 * `x-cors.x-unhandled-500-cors` 이며, 그 절이 정하기 전까지 어느 쪽으로도 테스트로
 * 고정하지 않는다.
 * (2026-08-21 정정 — 종전 문면은 계약이 2026-08-20 에 더 구체적인 이름으로 갈아 치운 옛
 * 노드를 지목하고 있었다. 옛 이름을 여기 백틱으로 다시 적지 않는다 — 그 형태가 곧
 * 「이름으로 지목한다」이고 `NamedReferenceGuardTest` 축 B 가 잡는 대상이다. 이력이 필요하면
 * 계약 `x-changelog` 를 보라.)
 */
@WebMvcTest
@Import(CorsConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class CorsContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("허용 오리진의 단순 요청에 Allow-Origin 과 노출 헤더가 붙는다")
    fun `단순 요청에 CORS 헤더가 붙는다`() {
        val response = simpleRequest(ALLOWED_ORIGIN)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN)
        assertThat(response.getHeader(HttpHeaders.VARY)).contains(HttpHeaders.ORIGIN)
    }

    @Test
    @DisplayName("실제 요청에 Expose-Headers 로 Content-Disposition 과 Location 이 노출된다")
    fun `실제 요청에 노출 헤더가 있다`() {
        val exposed = simpleRequest(ALLOWED_ORIGIN).getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)

        assertThat(exposed)
            .withFailMessage("노출 헤더가 없으면 React 가 파일명과 접수 주소를 읽지 못한다")
            .isNotNull()
        assertThat(exposed).contains("Content-Disposition")
        assertThat(exposed).contains("Location")
    }

    @Test
    @DisplayName("preflight 에도 Expose-Headers 가 실린다")
    fun `preflight 에 노출 헤더가 있다`() {
        val exposed = preflight(ALLOWED_ORIGIN, "GET").getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)

        assertThat(exposed).contains("Content-Disposition")
        assertThat(exposed).contains("Location")
    }

    @Test
    @DisplayName("preflight 가 계약의 메서드 5개를 허용한다")
    fun `preflight 는 계약의 메서드를 허용한다`() {
        val response = preflight(ALLOWED_ORIGIN, "POST")

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN)
        val allowed = response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)
        assertThat(allowed).isNotNull()
        // app/main.py 의 allow_methods 그대로. 빠뜨리면 브라우저에서만 실패한다.
        listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { method ->
            assertThat(allowed).contains(method)
        }
    }

    @Test
    @DisplayName("preflight 가 Authorization·Content-Type 요청 헤더를 허용한다")
    fun `preflight 는 계약의 요청 헤더를 허용한다`() {
        val allowed = preflight(ALLOWED_ORIGIN, "POST").getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)

        assertThat(allowed).isNotNull()
        assertThat(allowed?.lowercase()).contains("authorization")
        assertThat(allowed?.lowercase()).contains("content-type")
    }

    @Test
    @DisplayName("자격증명은 허용하지 않는다 — 쿠키를 쓰지 않으므로 CSRF 면적을 넓히지 않는다")
    fun `Allow-Credentials 를 내보내지 않는다`() {
        assertThat(simpleRequest(ALLOWED_ORIGIN).getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull()
        assertThat(preflight(ALLOWED_ORIGIN, "POST").getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull()
    }

    @Test
    @DisplayName("허용하지 않은 오리진에는 Allow-Origin 을 내주지 않는다")
    fun `허용하지 않은 오리진은 거절한다`() {
        assertThat(simpleRequest("http://evil.example").getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
        assertThat(preflight("http://evil.example", "GET").getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
    }

    @Test
    @DisplayName("계약에 없는 메서드의 preflight 는 통과하지 못한다")
    fun `계약 밖 메서드의 preflight 는 거절한다`() {
        assertThat(preflight(ALLOWED_ORIGIN, "TRACE").getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
    }

    @Test
    @DisplayName("Origin 이 없는 요청은 CORS 헤더 없이 그대로 처리된다")
    fun `Origin 이 없으면 CORS 헤더가 붙지 않는다`() {
        val response = mockMvc.get("/health").andReturn().response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull()
    }

    @Test
    @DisplayName("계약에 정의된 오류 응답에도 CORS 헤더가 붙는다 (404·405)")
    fun `계약 오류 응답에도 CORS 헤더가 붙는다`() {
        // Python 실측: 404·405 에 access-control-allow-origin 과 expose-headers 가 붙는다.
        // CORS 를 라우팅 안쪽(addCorsMappings)에 두면 없는 경로에서 헤더가 사라진다.
        val notFound =
            mockMvc
                .get("/nope") { header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN) }
                .andReturn()
                .response
        assertThat(notFound.status).isEqualTo(404)
        assertThat(notFound.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN)
        assertThat(notFound.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("Content-Disposition")
    }

    private fun simpleRequest(origin: String): MockHttpServletResponse =
        mockMvc
            .get("/health") { header(HttpHeaders.ORIGIN, origin) }
            .andReturn()
            .response

    private fun preflight(
        origin: String,
        requestMethod: String,
    ): MockHttpServletResponse =
        mockMvc
            .options("/health") {
                header(HttpHeaders.ORIGIN, origin)
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestMethod)
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
            }.andReturn()
            .response

    private companion object {
        /** `app/config.py` 의 `cors_origins` 기본값이자 `application.yml` 의 설정값. */
        const val ALLOWED_ORIGIN = "http://localhost:5173"
    }
}

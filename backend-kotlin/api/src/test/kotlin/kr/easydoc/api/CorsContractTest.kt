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

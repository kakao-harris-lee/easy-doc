package kr.easydoc.api

import kr.easydoc.api.config.CorsConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
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
import org.springframework.test.web.servlet.post

/**
 * 사적 응답 헤더의 **전역 부착** 계약. 정본은 `contracts/easy-doc-v1.yaml` 의
 * `x-global-response-headers` 이고, 체크 항목은 `api-contract-freeze` §5.1 이다.
 *
 * ## 여기서 재는 것과 재지 못하는 것
 *
 * MockMvc 는 서블릿 컨테이너를 띄우지 않는다. 그래서 **디스패처를 통과하는 응답**까지만
 * 여기서 잰다(G-B·G-C·G-D·G-F). 컨테이너가 필터 앞이나 바깥에서 직접 만드는 응답
 * (malformed HTTP 400, ERROR 디스패치)은 이 테스트로 측정되지 않는다 — 통과해도 근거가
 * 되지 않는다는 것이 §5.2 의 첫 항목이다. 그쪽은 [PrivateResponseHeadersReachTest] 가
 * 실제 소켓으로 잰다.
 *
 * ## G-A(열거 10곳 하한선)는 아직 여기 없다
 *
 * 계약이 지목한 고위험 10곳은 Phase 3·4 에서 만들어진다. 그 엔드포인트가 생길 때 개별
 * 단언을 함께 붙인다 — 전역 필터가 있어도 지우지 않는다(리더 판정 부수 결정 1).
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, CorsConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class PrivateResponseHeadersContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("G-D 값이 정확히 no-store / nosniff 다")
    fun `헤더 값이 계약의 const 와 같다`() {
        val response = mockMvc.get("/health").andReturn().response

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        assertThat(response.getHeader(X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff")
    }

    /**
     * 계약 `x-global-response-headers.enforcement` 가 `add` 가 아니라 **`set`** 을 지정한
     * 이유를 고정한다.
     *
     * 필터와 컨트롤러 `ResponseEntity` 가 같은 헤더를 **둘 다** 실으면
     * `Cache-Control: no-store, no-store` 가 나간다. 계약은 값을 `const: "no-store"` 로
     * 못박았으므로 위반인데, **값만 보는 단언(`getHeader`)은 첫 값만 읽어 통과한다.**
     * 그래서 값이 아니라 **개수**를 본다.
     */
    @Test
    @DisplayName("G-D 헤더가 하나씩만 붙는다 (no-store, no-store 이중 부착 금지)")
    fun `헤더가 중복 부착되지 않는다`() {
        assertSingleValued(mockMvc.get("/health").andReturn().response)
        assertSingleValued(mockMvc.get("/nope").andReturn().response)
        assertSingleValued(mockMvc.get("/__probe/domain/credentials").andReturn().response)
        assertSingleValued(mockMvc.post("/__probe/get-only").andReturn().response)
    }

    @Test
    @DisplayName("G-C 본문 없는 204 응답에도 붙는다")
    fun `본문 없는 응답에도 붙는다`() {
        val response = mockMvc.get("/__probe/get-only").andReturn().response

        assertThat(response.status).isEqualTo(204)
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        assertThat(response.getHeader(X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff")
    }

    /**
     * G-F. 계약 파일이 문법상 적을 자리가 없다고 명시한 자리다
     * (`x-openapi-expressibility` ④ — 프리플라이트에는 오퍼레이션이 없다).
     *
     * `CorsFilter` 는 preflight 를 스스로 끝내고 체인의 나머지를 부르지 않는다. 헤더
     * 필터가 CORS 필터보다 **뒤에** 등록되면 이 단언만 깨진다 — 다른 모든 응답은 멀쩡하다.
     */
    @Test
    @DisplayName("G-F CORS 프리플라이트(OPTIONS) 응답에도 붙는다")
    fun `프리플라이트 응답에도 붙는다`() {
        val response =
            mockMvc
                .options("/health") {
                    header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                }.andReturn()
                .response

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
            .withFailMessage("프리플라이트가 CORS 필터에서 처리되지 않았다 — 이 테스트의 전제가 깨졌다")
            .isEqualTo(ALLOWED_ORIGIN)
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
            .withFailMessage("프리플라이트 응답에 no-store 가 없다 — 헤더 필터가 CORS 필터보다 뒤에 등록됐는지 확인한다")
            .isEqualTo("no-store")
        assertThat(response.getHeader(X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff")
    }

    /**
     * 순서 상수를 값으로 고정한다. 위 프리플라이트 단언이 이 순서에 의존하는데, 순서가
     * 뒤집혀도 나머지 응답은 전부 멀쩡해서 원인을 찾기 어렵다. 무엇이 깨졌는지 이름으로
     * 말해 주는 단언을 하나 둔다.
     */
    @Test
    @DisplayName("헤더 필터가 CORS 필터보다 바깥에 있다")
    fun `필터 순서가 CORS 보다 앞이다`() {
        assertThat(PrivateResponseHeadersConfig.PRIVATE_HEADER_FILTER_ORDER)
            .withFailMessage("헤더 필터가 CORS 필터보다 뒤로 밀리면 프리플라이트 응답에서만 헤더가 사라진다")
            .isLessThan(PrivateResponseHeadersConfig.CORS_FILTER_ORDER)
    }

    private fun assertSingleValued(response: MockHttpServletResponse) {
        assertThat(response.getHeaders(HttpHeaders.CACHE_CONTROL))
            .withFailMessage(
                "Cache-Control 이 %s 로 나갔다 — 필터가 add 를 쓰거나 컨트롤러와 이중 부착됐다",
                response.getHeaders(HttpHeaders.CACHE_CONTROL),
            ).containsExactly("no-store")
        assertThat(response.getHeaders(X_CONTENT_TYPE_OPTIONS)).containsExactly("nosniff")
    }

    private companion object {
        /** `app/config.py` 의 `cors_origins` 기본값이자 `application.yml` 의 설정값. */
        const val ALLOWED_ORIGIN = "http://localhost:5173"

        /** Spring `HttpHeaders` 에 상수가 없다. */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    }
}

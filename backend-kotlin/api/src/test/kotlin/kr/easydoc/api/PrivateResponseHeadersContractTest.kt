package kr.easydoc.api

import kr.easydoc.api.config.CorsConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.ContractSpec
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

    /**
     * **값을 코드에 적지 않는다** (게이트 20 C-6).
     *
     * 종전 판은 이름이 「계약의 `const` 와 같다」인데 그 값을 계약이 아니라 코드 리터럴에서
     * 가져왔다. 음성 대조 N-3(`CacheControlNoStore.schema.const` 를 바꾼다)에서 api 117건
     * 중 빨강이 셋뿐이었고 **전역을 담당하는 이 테스트가 초록으로 남은** 이유가 그것이다.
     * 전역 조항의 값 강제자가 auth 3곳으로 한정돼 있었다.
     */
    @Test
    @DisplayName("G-D 값이 계약 컴포넌트의 const 와 정확히 같다")
    fun `헤더 값이 계약의 const 와 같다`() {
        val response = mockMvc.get("/health").andReturn().response

        val expected = ContractSpec.globalHeaderValues()
        assertThat(expected).isNotEmpty()
        expected.forEach { (header, value) ->
            assertThat(response.getHeader(header))
                .withFailMessage("%s 가 계약 컴포넌트 const 와 다르다", header)
                .isEqualTo(value)
        }
    }

    /**
     * 계약 **안**의 두 절이 갈리지 않았는지 본다.
     *
     * `x-global-response-headers.headers` 의 값과 각 헤더 컴포넌트의 `schema.const` 는
     * 같은 것을 두 번 적는다. 갈리면 어느 쪽이 정본인지 정해지지 않고, 한쪽만 읽는 테스트는
     * 다른 쪽 변경에 반응하지 않는다.
     */
    @Test
    @DisplayName("계약 안 두 절(전역 절 · 컴포넌트 const)의 값이 서로 같다")
    fun `계약 내부의 헤더 값 이중 선언이 일치한다`() {
        assertThat(ContractSpec.globalHeaderValues())
            .withFailMessage("전역 절의 값과 컴포넌트 const 가 갈렸다")
            .isEqualTo(ContractSpec.globalResponseHeaders())
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
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeader(header)).isEqualTo(value)
        }
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
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeader(header))
                .withFailMessage("프리플라이트 응답에 %s 가 없다 — 헤더 필터가 CORS 필터보다 뒤에 등록됐는지 확인한다", header)
                .isEqualTo(value)
        }
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
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeaders(header))
                .withFailMessage(
                    "%s 가 %s 로 나갔다 — 필터가 add 를 쓰거나 컨트롤러와 이중 부착됐다",
                    header,
                    response.getHeaders(header),
                ).containsExactly(value)
        }
    }

    private companion object {
        /** `app/config.py` 의 `cors_origins` 기본값이자 `application.yml` 의 설정값. */
        const val ALLOWED_ORIGIN = "http://localhost:5173"
    }
}

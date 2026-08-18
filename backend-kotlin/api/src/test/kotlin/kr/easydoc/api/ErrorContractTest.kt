package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get

/**
 * 도메인 예외 → HTTP 응답 계약. `app/api/errors.py` 의 `_MAPPINGS` 를 그대로 옮겼는지
 * **HTTP 경계에서** 확인한다.
 *
 * ## 왜 핸들러 직접 호출이 아닌가 (리뷰 C-2)
 *
 * 이 테스트의 1차 판은 `GlobalExceptionHandler` 를 직접 생성해 메서드를 불렀다. 그래서
 * 프레임워크 예외가 전부 500 으로 나가는 실제 이탈(C-1)이 존재하는 채로 10건이 전부
 * 초록이었다. `api-contract-freeze` 스킬 §5 가 못박은 대로 상태 코드 매핑·헤더 부착·
 * 직렬화는 전부 경계에서 결정되므로, 경계를 통과하지 않는 단언은 계약을 지키지 못한다.
 *
 * 예외를 던지는 자리는 [kr.easydoc.api.support.ErrorProbeController] 다 —
 * 테스트 소스셋에만 있고 운영 JAR 에 들어가지 않는다.
 *
 * ## 왜 [PrivateResponseHeadersConfig] 를 [Import] 하는가
 *
 * `@WebMvcTest` 슬라이스는 `@Bean` 으로 등록된 `FilterRegistrationBean` 을 자동으로
 * 끌어오지 않는다(`@Controller`·`@ControllerAdvice`·`Filter` 빈 등만 포함한다).
 * 명시적으로 들여와야 실제 체인과 같은 필터가 붙는다 — `CorsContractTest` 와 같은 이유다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class ErrorContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        // app/api/errors.py 의 _MAPPINGS 순서 그대로.
        "invalid-input,       422",
        "unsupported-format,  422",
        "extraction,          422",
        "too-large,           413",
        "duplicate-email,     409",
        "conflict,            409",
        "credentials,         401",
        "not-found,           404",
        "queue,               502",
        "llm-truncated,       502",
        "configuration,       503",
        "storage,             500",
    )
    @DisplayName("도메인 예외가 _MAPPINGS 의 상태 코드로 나간다")
    fun `도메인 예외 상태 코드 매핑`(
        kind: String,
        expectedStatus: Int,
    ) {
        val result = mockMvc.get("/__probe/domain/{kind}", kind).andReturn()

        assertThat(result.response.status)
            .withFailMessage(
                "%s 는 %d 여야 한다 (app/api/errors.py 의 _MAPPINGS)",
                kind,
                expectedStatus,
            ).isEqualTo(expectedStatus)
    }

    @Test
    @DisplayName("detail 은 도메인 예외가 만든 메시지 그대로다")
    fun `detail 은 예외 메시지 그대로다`() {
        mockMvc.get("/__probe/domain/not-found").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            // 키는 detail 하나뿐이다 — type·title·instance 가 섞이면 ProblemDetail 이다.
            content { json("""{"detail":"문서를 찾을 수 없습니다"}""", JsonCompareMode.STRICT) }
        }
    }

    @Test
    @DisplayName("자격증명 오류 → 401 + WWW-Authenticate: Bearer")
    fun `자격증명 오류는 401 이고 표준 헤더가 붙는다`() {
        val result = mockMvc.get("/__probe/domain/credentials").andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
    }

    @Test
    @DisplayName("401 아닌 오류에는 WWW-Authenticate 가 붙지 않는다")
    fun `404 에는 WWW-Authenticate 가 없다`() {
        val result = mockMvc.get("/__probe/domain/not-found").andReturn()

        assertThat(result.response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull()
    }

    @Test
    @DisplayName("매핑되지 않은 도메인 예외 → 500 + 고정 문자열 (예외 메시지 미노출)")
    fun `매핑 누락은 고정 문자열로 500 이다`() {
        val result =
            mockMvc
                .get("/__probe/domain/unmapped")
                .andExpect {
                    status { isInternalServerError() }
                    content { json("""{"detail":"요청을 처리하지 못했습니다"}""", JsonCompareMode.STRICT) }
                }.andReturn()

        // 예외 메시지에 무엇이 담길지 이 지점에서는 알 수 없다 — 그대로 노출하지 않는다.
        assertThat(result.response.contentAsString).doesNotContain("홍길동")
    }

    @Test
    @DisplayName("도메인 밖 예외 → 500 + 고정 문자열 (예외 메시지 미노출)")
    fun `예상하지 못한 예외는 고정 문자열로 500 이다`() {
        // 프로브가 알지 못하는 종류를 주면 IllegalStateException 이 난다 — 도메인 밖 예외다.
        val result =
            mockMvc
                .get("/__probe/domain/주민등록번호-900101-1234567")
                .andExpect {
                    status { isInternalServerError() }
                    content { json("""{"detail":"서버 오류가 발생했습니다"}""", JsonCompareMode.STRICT) }
                }.andReturn()

        assertThat(result.response.contentAsString).doesNotContain("900101")
    }

    @Test
    @DisplayName("오류 응답에도 사적 응답 헤더가 붙는다 (OQ-1 전역 부착)")
    fun `오류 응답에 사적 응답 헤더가 있다`() {
        assertPrivateHeaders(mockMvc.get("/__probe/domain/credentials").andReturn())
        assertPrivateHeaders(mockMvc.get("/__probe/domain/not-found").andReturn())
        assertPrivateHeaders(mockMvc.get("/__probe/domain/conflict").andReturn())
        assertPrivateHeaders(mockMvc.get("/__probe/domain/unmapped").andReturn())
    }

    /**
     * 계약 `x-global-response-headers`: *"성공 응답, 오류 응답(4xx·5xx), 본문 없는 204 …
     * 전부 포함"*. 2026-08-12 리더 판정(OQ-1 종결)으로 **부호가 뒤집힌 단언**이다.
     *
     * 이 자리의 종전 판은 "오류 응답에 헤더가 **없다**"였고, 그 근거는 열거식 범위
     * (성공 응답 10곳)였다. 그 범위가 이 저장소에서 두 번 누락된 것이 G4 근거가 되어
     * 전역 부착으로 바뀌었다. **지우지 않고 반대 단언으로 바꾼다** — 지우기만 하면 이
     * 자리에 아무 검사도 남지 않는다(`api-contract-freeze` §5.1 G-B).
     */
    private fun assertPrivateHeaders(result: MvcResult) {
        assertThat(result.response.getHeader(HttpHeaders.CACHE_CONTROL))
            .withFailMessage("오류 응답에 Cache-Control 이 없다 — 계약은 모든 응답에 no-store 를 요구한다")
            .isEqualTo("no-store")
        assertThat(result.response.getHeader("X-Content-Type-Options"))
            .withFailMessage("오류 응답에 X-Content-Type-Options 가 없다 — 계약은 모든 응답에 nosniff 를 요구한다")
            .isEqualTo("nosniff")
    }
}

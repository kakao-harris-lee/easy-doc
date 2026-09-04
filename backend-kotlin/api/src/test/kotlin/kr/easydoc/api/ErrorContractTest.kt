package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.ContractSpec
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
 * 도메인 예외 → HTTP 응답 계약. `contracts/easy-doc-v1.yaml` 이 선언한 상태 코드로
 * 나가는지 HTTP 경계에서 확인한다. Python 의 `_MAPPINGS` 는 정본이 아니다 — 폐기
 * 대상이고, 계약 v1.3.0 은 그것이 쓰던 502 를 폐기했다(`x-retired-responses`).
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class ErrorContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "invalid-input,       422",
        "unsupported-format,  422",
        "extraction,          422",
        "too-large,           413",
        "duplicate-email,     409",
        "conflict,            409",
        "credentials,         401",
        "not-found,           404",
        "llm-truncated,       500",
        "configuration,       503",
        "storage,             500",
        "oauth-state,         400",
        "external-unavailable,502",
    )
    @DisplayName("도메인 예외가 계약이 선언한 상태 코드로 나간다")
    fun `도메인 예외 상태 코드 매핑`(
        kind: String,
        expectedStatus: Int,
    ) {
        val result = mockMvc.get("/__probe/domain/{kind}", kind).andReturn()

        assertThat(result.response.status)
            .withFailMessage(
                "%s 는 %d 여야 한다 (contracts/easy-doc-v1.yaml)",
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

        assertThat(result.response.contentAsString).doesNotContain("홍길동")
    }

    @Test
    @DisplayName("도메인 밖 예외 → 500 + 고정 문자열 (예외 메시지 미노출)")
    fun `예상하지 못한 예외는 고정 문자열로 500 이다`() {
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
     * 전부 포함"*. 2026-08-12 리더 판정(OQ-1 종결)으로 부호가 뒤집힌 단언이다.
     */
    private fun assertPrivateHeaders(result: MvcResult) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(result.response.getHeader(header))
                .withFailMessage("오류 응답에 %s 가 계약값으로 붙지 않았다 — 계약은 모든 응답에 요구한다", header)
                .isEqualTo(value)
        }
    }
}

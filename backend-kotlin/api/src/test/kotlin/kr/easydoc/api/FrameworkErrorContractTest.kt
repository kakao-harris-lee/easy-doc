package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Spring MVC 자체가 던지는 예외의 계약. 리뷰 C-1 의 회귀 고정판이다. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class FrameworkErrorContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("없는 경로 → 404 {\"detail\":\"Not Found\"} (Python 과 같다)")
    fun `없는 경로는 404 다`() {
        mockMvc.get("/nope").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            content { json("""{"detail":"Not Found"}""", JsonCompareMode.STRICT) }
        }
    }

    @Test
    @DisplayName("허용되지 않는 메서드 → 405 + Allow, {\"detail\":\"Method Not Allowed\"}")
    fun `허용되지 않는 메서드는 405 다`() {
        val result =
            mockMvc
                .post("/__probe/get-only")
                .andExpect {
                    status { isMethodNotAllowed() }
                    content { json("""{"detail":"Method Not Allowed"}""", JsonCompareMode.STRICT) }
                }.andReturn()

        assertThat(result.response.getHeader(HttpHeaders.ALLOW)).contains("GET")
    }

    @Test
    @DisplayName("Accept: application/xml 이어도 200 JSON 이다 (FastAPI 는 내용 협상을 하지 않는다)")
    fun `Accept 헤더로 406 을 내지 않는다`() {
        mockMvc
            .get("/health") {
                accept(MediaType.APPLICATION_XML)
            }.andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }

                content { json("""{"status":"ok"}""", JsonCompareMode.LENIENT) }
            }
    }

    @Test
    @DisplayName("깨진 JSON 본문 → 422 + detail 배열 (Spring 기본 400 이 아니다)")
    fun `깨진 JSON 은 422 배열이다`() {
        mockMvc
            .post("/__probe/body") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"""
            }.andExpect {
                status { isUnprocessableContent() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }

                jsonPath("$.detail[0].loc[0]") { value("body") }
                jsonPath("$.detail[0].msg") { value("JSON decode error") }
                jsonPath("$.detail[0].type") { value("json_invalid") }
            }
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락 → 422 + loc=[query, ...] / type=missing")
    fun `쿼리 파라미터 누락은 422 배열이다`() {
        mockMvc.get("/__probe/query").andExpect {
            status { isUnprocessableContent() }

            jsonPath("$.detail[0].loc[0]") { value("query") }
            jsonPath("$.detail[0].loc[1]") { value("limit") }
            jsonPath("$.detail[0].msg") { value("Field required") }
            jsonPath("$.detail[0].type") { value("missing") }
        }
    }

    @Test
    @DisplayName("쿼리 파라미터 형식 오류 → 422 + loc=[query, ...]")
    fun `쿼리 파라미터 형식 오류는 422 배열이다`() {
        val result =
            mockMvc
                .get("/__probe/query") { param("limit", "열개") }
                .andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.detail[0].loc[0]") { value("query") }
                    jsonPath("$.detail[0].loc[1]") { value("limit") }
                }.andReturn()

        assertThat(result.response.contentAsString).doesNotContain("열개")
    }

    /** 이 단언은 계약의 다섯 요청 필드에 적용되지 않는다. 리뷰 C-A(X6)가 지적한 자리다. */
    @Test
    @DisplayName("스키마 층 검증 실패 → 422 + detail 배열 (Spring 기본 400 이 아니다)")
    fun `Bean Validation 실패는 422 배열이다`() {
        mockMvc.post("/__probe/bean-validation").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { isArray() }
            jsonPath("$.detail[0].loc[0]") { value("body") }

            jsonPath("$.detail[0].loc[1]") { value("probe") }
            jsonPath("$.detail[0].msg") { value("must not be blank") }
            jsonPath("$.detail[0].type") { value("missing") }

            jsonPath("$.detail[0].input") { doesNotExist() }
            jsonPath("$.detail[0].ctx") { doesNotExist() }
        }
    }

    /**
     * F3 이 요구하는 반대쪽 모양. 계약의 다섯 필드는 길이·형식 위반도 도메인 예외로
     * 올라오므로 `detail` 이 배열이 아니라 한국어 문자열 하나여야 한다.
     */
    @Test
    @DisplayName("서비스 층 입력 규칙 위반 → 422 + detail 문자열 (배열이 아니다)")
    fun `서비스 층 길이 위반은 422 문자열이다`() {
        mockMvc.get("/__probe/domain/invalid-input").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { isString() }
            content { json("""{"detail":"제목이 너무 깁니다"}""", JsonCompareMode.STRICT) }
        }
    }

    /**
     * 2026-08-12 리더 판정(OQ-1 종결)으로 부호가 뒤집힌 단언이다. 종전 판은
     * "프레임워크 오류 응답에 캐시 금지 헤더가 없다"였고 근거는 열거식 범위였다.
     */
    @Test
    @DisplayName("프레임워크 오류 응답에도 사적 응답 헤더가 붙는다 (OQ-1 전역 부착)")
    fun `프레임워크 오류 응답에 사적 응답 헤더가 있다`() {
        listOf(
            mockMvc.get("/nope").andReturn(),
            mockMvc.post("/__probe/get-only").andReturn(),
            mockMvc.get("/__probe/query").andReturn(),
        ).forEach { result ->

            ContractSpec.globalHeaderValues().forEach { (header, value) ->
                assertThat(result.response.getHeader(header))
                    .withFailMessage("프레임워크 오류 응답에 %s 가 계약값으로 붙지 않았다", header)
                    .isEqualTo(value)
            }
        }
    }

    @Test
    @DisplayName("프레임워크 오류 본문도 ProblemDetail 이 아니라 detail 한 키다")
    fun `프레임워크 오류도 detail 한 키다`() {
        val body =
            mockMvc
                .get("/nope")
                .andReturn()
                .response.contentAsString

        assertThat(body).doesNotContain("\"type\"")
        assertThat(body).doesNotContain("\"title\"")
        assertThat(body).doesNotContain("\"instance\"")
        assertThat(body).doesNotContain("\"status\"")
    }
}

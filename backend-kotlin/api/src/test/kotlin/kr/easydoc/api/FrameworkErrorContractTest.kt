package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
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

/**
 * **Spring MVC 자체가 던지는 예외**의 계약. 리뷰 C-1 의 회귀 고정판이다.
 *
 * ## 무엇이 있었나
 *
 * `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception::class)` 백스톱이
 * `NoResourceFoundException`·`HttpRequestMethodNotSupportedException` 같은 프레임워크
 * 예외까지 삼켜 **전부 500** 으로 나갔다. 살아 있는 두 런타임 실측(2026-08-12):
 *
 * | 요청 | Python(8000) | 고치기 전 Kotlin(8100) |
 * |---|---|---|
 * | `GET /nope` | 404 `{"detail":"Not Found"}` | 500 |
 * | `POST /health` | 405 + `allow: GET` | 500 |
 * | `GET /health` `Accept: application/xml` | 200 | 500 |
 *
 * 지금은 엔드포인트가 `/health` 하나뿐이라 피해가 작아 보이지만, 같은 백스톱에
 * `MethodArgumentNotValidException` 이 떨어지면 계약이 요구하는 **422 + `detail` 배열**이
 * **500 + 고정 문자열**이 된다. 그래서 검증 실패 계열도 여기서 함께 고정한다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class)
class FrameworkErrorContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    // ---------------------------------------------------------------- C-1 세 케이스

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

        // Python 은 `allow: GET` 을 준다. 허용 메서드를 알려주지 않으면 클라이언트가 고칠 수 없다.
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
                content { json("""{"status":"ok"}""", JsonCompareMode.STRICT) }
            }
    }

    // ---------------------------------------------------------------- 검증 실패 = 422 배열

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
                // Python: {"detail":[{"loc":["body",9],"msg":"JSON decode error","type":"json_invalid"}]}
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
            // Python: {"detail":[{"loc":["query","limit"],"msg":"Field required","type":"missing"}]}
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

        // 거절된 입력값을 되돌려주지 않는다 — 비밀번호가 응답 본문·액세스 로그에 남는 경로다.
        assertThat(result.response.contentAsString).doesNotContain("열개")
    }

    @Test
    @DisplayName("Bean Validation 실패 → 422 + loc=[body, 필드] (Spring 기본 400 이 아니다)")
    fun `Bean Validation 실패는 422 배열이다`() {
        mockMvc.post("/__probe/bean-validation").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].loc[0]") { value("body") }
            jsonPath("$.detail[0].loc[1]") { value("email") }
            jsonPath("$.detail[0].msg") { value("must not be blank") }
            // input·ctx 는 금지 — FieldError 가 들고 있는 rejectedValue 를 직렬화하지 않는다.
            jsonPath("$.detail[0].input") { doesNotExist() }
            jsonPath("$.detail[0].ctx") { doesNotExist() }
        }
    }

    // ---------------------------------------------------------------- 공통 계약

    /**
     * 2026-08-12 리더 판정(OQ-1 종결)으로 **부호가 뒤집힌 단언**이다. 종전 판은
     * "프레임워크 오류 응답에 캐시 금지 헤더가 **없다**"였고 근거는 열거식 범위였다.
     *
     * 프레임워크 오류는 컨트롤러를 **아예 거치지 않는** 응답이라, 헤더를 `ResponseEntity`
     * 로 싣는 방식으로는 붙일 자리가 없다. 전역 필터가 이 자리를 덮는지가 여기서 갈린다.
     * 다만 MockMvc 는 서블릿 컨테이너를 띄우지 않으므로 **컨테이너가 직접 만드는 응답**은
     * 이 테스트로 측정되지 않는다 — 그쪽은 [PrivateResponseHeadersReachTest] 가 실제
     * 소켓으로 잰다.
     */
    @Test
    @DisplayName("프레임워크 오류 응답에도 사적 응답 헤더가 붙는다 (OQ-1 전역 부착)")
    fun `프레임워크 오류 응답에 사적 응답 헤더가 있다`() {
        listOf(
            mockMvc.get("/nope").andReturn(),
            mockMvc.post("/__probe/get-only").andReturn(),
            mockMvc.get("/__probe/query").andReturn(),
        ).forEach { result ->
            assertThat(result.response.getHeader(HttpHeaders.CACHE_CONTROL))
                .withFailMessage("프레임워크 오류 응답에 Cache-Control 이 없다 — 계약은 모든 응답에 no-store 를 요구한다")
                .isEqualTo("no-store")
            assertThat(result.response.getHeader("X-Content-Type-Options"))
                .withFailMessage("프레임워크 오류 응답에 X-Content-Type-Options 가 없다")
                .isEqualTo("nosniff")
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

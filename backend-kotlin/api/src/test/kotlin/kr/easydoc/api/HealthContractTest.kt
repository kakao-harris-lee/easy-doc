package kr.easydoc.api

import kr.easydoc.api.health.HealthController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * `GET /health` 가 계약(`contracts/easy-doc-v1.yaml`)대로 응답하는지 확인한다.
 *
 * `@WebMvcTest` 라 DataSource·Flyway 없이 돈다 — 계약 검증은 배선과 분리한다.
 * 실제 DB 위에서의 기동 확인은 [ApiStartupWithDatabaseTest] 가 맡는다.
 */
@WebMvcTest(HealthController::class)
class HealthContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("200 과 {\"status\":\"ok\"} 를 돌려준다")
    fun `health 는 상수 ok 를 돌려준다`() {
        mockMvc.get("/health").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            // 필드는 status 하나뿐이다. 버전·기동 시각을 덧붙이면 계약 위반이다.
            content { json("""{"status":"ok"}""", JsonCompareMode.STRICT) }
        }
    }

    @Test
    @DisplayName("인증 없이 접근할 수 있다")
    fun `health 는 인증을 요구하지 않는다`() {
        mockMvc.get("/health").andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("캐시 금지 헤더를 붙이지 않는다")
    fun `health 응답에는 캐시 금지 헤더가 없다`() {
        // 계약: "인증이 필요 없고 캐시 금지 헤더도 붙지 않는다(실측 확인 2026-08-12)."
        // 이 응답에는 개인정보도 자격증명도 없다. PRIVATE_RESPONSE_HEADERS 대상 10곳에
        // /health 는 들어 있지 않다. 반대 방향의 회귀(무심코 no-store 를 전역으로 붙이는 것)를
        // 여기서 잡는다.
        val response = mockMvc.get("/health").andReturn().response

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull()
        assertThat(response.getHeader("X-Content-Type-Options")).isNull()
    }

    @Test
    @DisplayName("의존 서비스를 진단하지 않는다 — DataSource 가 없어도 200")
    fun `의존 서비스가 없어도 ok 를 돌려준다`() {
        // 이 테스트 컨텍스트에는 DataSource 도 Flyway 도 없다. 그런데도 200 ok 가 나오는 것이
        // 곧 "상수만 돌려준다"의 증명이다. 나중에 누군가 DB 핑을 넣으면 여기서 깨진다.
        mockMvc.get("/health").andExpect {
            status { isOk() }
            content { json("""{"status":"ok"}""") }
        }
    }
}

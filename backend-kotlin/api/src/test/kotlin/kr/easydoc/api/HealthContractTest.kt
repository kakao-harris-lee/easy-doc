package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.health.HealthController
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/** `GET /health` 가 계약(`contracts/easy-doc-v1.yaml`)대로 응답하는지 확인한다. */
@WebMvcTest(HealthController::class)
@Import(PrivateResponseHeadersConfig::class, kr.easydoc.api.support.AuthSliceBeans::class)
class HealthContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = ObjectMapper()

    @Test
    @DisplayName("200 이고 본문 키가 **정확히** HealthResponse.required 다 — 필드 부재·추가 모두 잡는다")
    fun `본문 키가 계약 required 와 정확히 같다`() {
        val response = health()

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(HEALTH_PATH, GET))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .withFailMessage(
                "본문 키가 계약 %s 와 다르다. 실제: %s — 필드가 빠지면 계약 위반이고, " +
                    "늘면 인증 없는 엔드포인트가 계약 밖 정보를 내보낸다",
                HEALTH_SCHEMA,
                bodyOf(response).keys,
            ).isEqualTo(ContractSpec.schemaRequired(HEALTH_SCHEMA))
    }

    @Test
    @DisplayName("상태 값이 계약 enum 집합 안이다 — 집합을 계약에서 읽어 대조한다")
    fun `상태 값이 계약 enum 안이다`() {
        val declared =
            ContractSpec
                .strings("components", "schemas", HEALTH_SCHEMA, "properties", STATUS_PROPERTY, "enum")
                .toSet()

        assertThat(declared).isNotEmpty()
        assertThat(bodyOf(health())[STATUS_PROPERTY].toString()).isIn(declared)
    }

    @Test
    @DisplayName("의존 서비스가 하나도 배선되지 않으면 `checks` 가 **빈 객체**이고 상태가 `ok` 다")
    fun `배선이 없으면 빈 검사와 ok 다`() {
        val body = bodyOf(health())

        assertThat(body[CHECKS_PROPERTY]).isInstanceOf(Map::class.java)
        assertThat(body[CHECKS_PROPERTY] as Map<*, *>).isEmpty()
        assertThat(body[STATUS_PROPERTY]).isEqualTo(OK_STATUS)
    }

    @Test
    @DisplayName("`checks` 의 값은 **불리언뿐**이다 — 예외 메시지·호스트가 실릴 통로가 없다")
    fun `검사 값은 불리언뿐이다`() {
        val declaredType =
            ContractSpec.text(
                "components",
                "schemas",
                HEALTH_SCHEMA,
                "properties",
                CHECKS_PROPERTY,
                "additionalProperties",
                "type",
            )

        assertThat(declaredType).isEqualTo("boolean")
        (bodyOf(health())[CHECKS_PROPERTY] as Map<*, *>).values.forEach {
            assertThat(it).isInstanceOf(Boolean::class.javaObjectType)
        }
    }

    @Test
    @DisplayName("인증 없이 접근할 수 있다 — 계약 `security: []`")
    fun `health 는 인증을 요구하지 않는다`() {
        assertThat(ContractSpec.security(HEALTH_PATH, GET)).isEmpty()
        assertThat(health().status).isEqualTo(ContractSpec.successStatus(HEALTH_PATH, GET))
    }

    @Test
    @DisplayName("사적 응답 헤더를 붙인다 (전역 부착 — 계약이 `GET /health` 를 명시적으로 포함한다)")
    fun `health 응답에도 사적 응답 헤더가 있다`() {
        val response = health()

        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.getHeader(header))
                .withFailMessage("/health 응답에 %s 가 계약값으로 붙지 않았다", header)
                .isEqualTo(value)
        }
    }

    private fun health(): MockHttpServletResponse =
        mockMvc
            .get(HEALTH_PATH)
            .andReturn()
            .response
            .also { assertThat(it.contentType).contains(MediaType.APPLICATION_JSON_VALUE) }

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private companion object {
        const val HEALTH_PATH = "/health"
        const val GET = "get"

        const val HEALTH_SCHEMA = "HealthResponse"
        const val STATUS_PROPERTY = "status"
        const val CHECKS_PROPERTY = "checks"

        /** 계약 `HealthResponse.properties.status.enum` 의 첫 값. 위 케이스가 집합을 읽어 대조한다. */
        const val OK_STATUS = "ok"
    }
}

package kr.easydoc.api

import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

/** `GET /documents` 의 사적 헤더 하한선 — 전역 장치를 뺀 컨텍스트에서 잰다. */
@WebMvcTest
@Import(AuthSliceBeans::class)
class DocumentListHeaderFloorTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    @Test
    @DisplayName("전역 부착 장치가 없는 컨텍스트에서도 목록 응답에 사적 헤더 2종이 나간다 (X-D1 하한선)")
    fun `개별 부착이 하한선을 진다`() {
        val expected = ContractSpec.globalHeaderValues()
        assertThat(expected)
            .withFailMessage("계약에서 읽은 헤더 목록이 비었다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        val response = list(newOwner())

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        expected.forEach { (header, value) ->
            assertThat(response.getHeaders(header))
                .withFailMessage(
                    "전역 장치가 없을 때 %s 가 나가지 않았다 — 컨트롤러의 개별 부착이 사라졌다는 뜻이고, " +
                        "그것이 계약 하한선(x-private-response-headers.applies_to)의 위반이다. 실제: %s",
                    header,
                    response.getHeaders(header),
                ).containsExactly(value)
        }
    }

    @Test
    @DisplayName("이 컨텍스트에 전역 부착 장치가 **실제로 없다** — 있으면 위 단언이 개별 부착을 재지 못한다")
    fun `전역 장치가 이 컨텍스트에 없다`() {
        val response = mockMvc.get(HEALTH_PATH).andReturn().response

        ContractSpec.globalHeaderValues().keys.forEach { header ->
            assertThat(response.getHeader(header))
                .withFailMessage(
                    "전역 부착 장치가 이 컨텍스트에 들어와 있다 — 위 케이스가 개별 부착을 재고 있지 않다(%s)",
                    header,
                ).isNull()
        }
    }

    private fun list(owner: UUID) =
        mockMvc
            .get(DOCUMENTS_PATH) {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
            }.andReturn()
            .response

    private fun newOwner(): UUID {
        val id = users.create("floor-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val DOCUMENTS_PATH = "/documents"
        const val HEALTH_PATH = "/health"
        const val GET = "get"
    }
}

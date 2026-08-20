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

/**
 * **`GET /documents` 의 사적 헤더 하한선 — 전역 장치를 뺀 컨텍스트에서 잰다.**
 *
 * ## 왜 [DocumentListContractTest] 의 DL-1 만으로는 부족한가 (실측)
 *
 * 계약은 이 오퍼레이션을 고위험 **하한선 10곳**에 넣었고
 * (`x-private-response-headers.applies_to` — *"사용자가 적어 준 제목이 실린다"*),
 * 그래서 컨트롤러가 전역 필터와 **겹쳐** 헤더를 붙인다. 겹침의 목적은 하나다 —
 * 전역 장치가 빠지거나 체인 순서가 어긋났을 때 여기서 먼저 깨지게 하는 것.
 *
 * 그런데 **전역 필터가 살아 있는 컨텍스트에서는 개별 부착을 지워도 아무것도 빨개지지
 * 않는다.** 2026-08-21 실측이다:
 *
 * | 변이 | `DocumentListContractTest` |
 * |---|---|
 * | 슬라이스에서 `PrivateResponseHeadersConfig` 제거 | 9건 **전부 초록**(개별 부착이 응답을 든다) |
 * | 컨트롤러의 개별 부착 두 줄 제거(전역 유지) | 9건 **전부 초록**(전역이 덮는다) |
 *
 * 둘째 줄이 문제다 — 하한선의 근거는 「전역이 없을 때도 나간다」인데, 그 성질이 무너지는
 * 편집을 잡는 장치가 없었다. 「관측했는데 장치가 없다」의 전형이라 이 파일을 만든다.
 *
 * ## 무엇이 다른가 — **전역 부착 장치를 들이지 않는다**
 *
 * `@Import` 에 [kr.easydoc.api.config.PrivateResponseHeadersConfig] 가 **없다.** 그래서 이
 * 컨텍스트의 응답에 헤더를 붙일 수 있는 것은 컨트롤러뿐이고, 개별 부착이 사라지면 이
 * 단언이 곧바로 빨개진다.
 *
 * ## 범위를 이 오퍼레이션 하나로 둔다
 *
 * 같은 빈자리가 하한선의 다른 아홉 자리(auth 3 · workspaces 3 · 아직 없는 3)에도 있다.
 * 그것을 한 번에 덮는 장치(계약 `applies_to` 를 읽어 열 오퍼레이션을 유도하는 형태)는 이
 * 커밋의 근거를 넘는다 — **이 커밋이 만든 자리는 `GET /documents` 하나**다.
 * 일반화는 `04_kotlin-implementer_improvement-backlog.md` 에 후보로 적었다.
 *
 * 값과 헤더 이름을 코드에 적지 않는다 — [ContractSpec.globalHeaderValues] 가 계약
 * `components/headers` 의 `const` 에서 읽는다.
 */
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
        // 전제가 조용히 깨지는 경로가 있다: 누군가 `PrivateResponseHeadersConfig` 를
        // `@Configuration` 스캔 대상으로 바꾸거나 슬라이스 기본 포함으로 옮기면, 위 케이스는
        // 초록인 채 **전역을 재게 된다.** 그래서 전제 자체를 단언한다 — 인증이 필요 없는
        // 경로(`/health`)에 헤더가 붙지 않는 것이 그 관측이다.
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

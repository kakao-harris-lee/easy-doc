package kr.easydoc.api

import kr.easydoc.api.support.ContainerRejectedRequest
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **파싱 거절 열거자가 계약이 든 갈래와 같은 집합인지 본다.**
 *
 * ## 왜 이 장치가 필요한가
 *
 * 계약 `x-global-response-headers.x-phase3-measurement.unreachable_by_filter.cases` 는
 * "필터가 물리적으로 닿을 수 없어 밸브가 덮어야 하는 응답"을 열거하고,
 * [ContainerRejectedRequest] 는 그것을 실제로 쏴 보는 열거자다. 두 목록이 갈리면 —
 *
 * - 계약에만 있는 갈래 → **아무도 재지 않는다.** 밸브가 그 자리를 놓쳐도 초록이다.
 * - 열거자에만 있는 갈래 → 계약이 요구하지 않는 것을 재고 있다. 계약이 그 갈래를 뺀
 *   이유(예: 실은 서블릿까지 도달한다)가 코드에 반영되지 않은 상태다.
 *
 * **개수로 대조하면 둘 다 놓친다** — 계약이 한 갈래를 빼고 다른 갈래를 넣으면 개수는
 * 그대로다. 이 저장소는 그 형태를 실제로 겪었다: 계약이 7종이라 적은 자리를 상시 회귀
 * 열거자가 6종만 돌았고(게이트 21 사실 ⑧), 개수가 갈렸기에 눈에 띄었을 뿐이다. 맞바뀜은
 * 개수가 같아 그렇게도 드러나지 않는다.
 *
 * ## Spring 컨텍스트가 필요 없다
 *
 * 재는 것이 **선언 두 벌의 일치**이지 런타임 동작이 아니다. 실제 응답 측정은
 * [PrivateResponseHeadersReachTest] 가 원시 소켓으로 하고, 이 테스트는 그쪽이 **무엇을
 * 쏘는지가 계약과 같은지**를 지킨다.
 */
class ContainerRejectionCoverageContractTest {
    @Test
    @DisplayName("열거자가 재는 갈래 집합이 계약의 unreachable_by_filter.cases 와 정확히 같다")
    fun `열거자와 계약이 같은 집합이다`() {
        val declared = ContractSpec.containerRejectedCases()
        val enumerated = ContainerRejectedRequest.entries.map { it.contractCase }.toSet()

        assertThat(declared)
            .withFailMessage("계약의 unreachable_by_filter.cases 가 비었다 — 대조할 대상이 없다")
            .isNotEmpty()
        assertThat(enumerated)
            .withFailMessage(
                "계약에만 있는 갈래(아무도 재지 않는다): %s / 열거자에만 있는 갈래(계약이 요구하지 않는다): %s",
                declared - enumerated,
                enumerated - declared,
            ).isEqualTo(declared)
    }

    @Test
    @DisplayName("갈래 이름이 상수마다 서로 다르다 — 둘이 같은 이름을 들면 집합 크기가 줄어 대조가 헐거워진다")
    fun `갈래 이름이 겹치지 않는다`() {
        val names = ContainerRejectedRequest.entries.map { it.contractCase }

        // 두 상수가 같은 이름을 들면 집합으로 접혀, 계약 갈래 하나가 안 재지고 있어도
        // 크기가 맞아 통과할 수 있다.
        assertThat(names).doesNotHaveDuplicates()
    }
}

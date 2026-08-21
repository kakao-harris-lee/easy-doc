package kr.easydoc.api

import kr.easydoc.api.support.ContainerRejectedRequest
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 파싱 거절 열거자가 계약이 든 갈래와 같은 집합인지 본다. */
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

        assertThat(names).doesNotHaveDuplicates()
    }
}

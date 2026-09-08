package kr.easydoc.core.segment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `compliantSourceUnits` — 계획 §11 수용 기준 C1.
 *
 * `checkStyle` 위반 0건이고 공백만이 아닌 원본 단위의 0 기반 색인만 남는다. 마스킹은
 * 관여하지 않는다 — 이 함수는 정말로 그냥 문자열 목록 하나를 받아 색인 목록을 돌려주는
 * `core` 순수 함수다(호출부가 마스킹 전/후 어느 텍스트를 넘기든 이 파일은 개의치 않는다).
 */
class CompliantSourceUnitsTest {
    @Test
    @DisplayName("C1 — 빈 줄·공백 줄·길이 위반 단위는 빠지고, 통과 단위 색인만 오름차순으로 남는다")
    fun `통과 단위만 색인으로 남는다`() {
        val tooLong = "가".repeat(60) + "."

        val result =
            compliantSourceUnits(
                listOf("짧은 문장입니다.", "", "   ", tooLong),
            )

        assertThat(result).containsExactly(0)
    }

    @Test
    @DisplayName("C1 — 어려운 표현·이중 피동이 남은 단위도 위반이라 빠진다")
    fun `어려운 표현과 이중 피동 단위도 빠진다`() {
        val result =
            compliantSourceUnits(
                // "통지를"의 "를"이 낱말 경계 조사라 "통지"가 온전한 낱말로 잡힌다(DifficultWords.kt).
                listOf("짧은 문장입니다.", "통지를 확인하세요.", "결과가 발표되어지다."),
            )

        assertThat(result).containsExactly(0)
    }

    @Test
    @DisplayName("C1 — 통과 단위가 하나도 없으면 빈 목록이다")
    fun `통과 단위가 없으면 빈 목록`() {
        assertThat(compliantSourceUnits(listOf("", "   "))).isEmpty()
    }

    @Test
    @DisplayName("C1 — 통과 단위 여럿이면 원래 순서대로(오름차순) 중복 없이 담긴다")
    fun `여러 통과 단위가 오름차순 중복 없이 담긴다`() {
        val result =
            compliantSourceUnits(
                listOf("짧은 문장입니다.", "통지를 확인하세요.", "다시 짧은 문장이다."),
            )

        assertThat(result).containsExactly(0, 2)
        assertThat(result).isSorted()
        assertThat(result.distinct()).isEqualTo(result)
    }
}

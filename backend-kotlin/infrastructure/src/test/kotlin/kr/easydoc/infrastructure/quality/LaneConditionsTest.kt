package kr.easydoc.infrastructure.quality

import kr.easydoc.core.document.ReadingLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** 조건 줄 조립만 검증한다. 리포트 머리줄과 `conditions.txt` 가 읽는 바로 그 문자열이다. */
class LaneConditionsTest {
    @Test
    @DisplayName("수준을 정하지 않은 실행도 조건 줄에 grade_5_6 을 찍는다")
    fun `기본 수준도 조건 줄에 남는다`() {
        assertThat(describe(ReadingLevel.GRADE_5_6)).contains("readingLevel=grade_5_6")
    }

    @Test
    @DisplayName("고른 수준의 wire 이름을 조건 줄에 찍는다 — 리포트와 conditions.txt 가 같은 문자열을 쓴다")
    fun `고른 수준을 조건 줄에 남긴다`() {
        val description = describe(ReadingLevel.GRADE_3_4)

        assertThat(description).startsWith("variant=baseline · readingLevel=grade_3_4 · ")
        assertThat(description).contains("provider=anthropic · dict=off · transcript=off · documents=all(8)")
        assertThat(description).endsWith("runs=2 cap_usd=11")
    }

    private fun describe(readingLevel: ReadingLevel): String =
        LaneConditions(
            variant = GoldenLaneVariant.BASELINE,
            readingLevel = readingLevel,
            runs = 2,
            budgetUsd = BigDecimal("11"),
        ).describe(
            provider = "provider=anthropic",
            dictionary = "dict=off",
            transcript = "transcript=off",
            selection = "documents=all(8)",
        )
}

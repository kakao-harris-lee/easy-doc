package kr.easydoc.core.segment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [splitUnits]·[joinUnits] 왕복 — 계획 §6 A1. */
class SourceUnitsTest {
    @Test
    @DisplayName("A1 왕복 — 빈 문자열")
    fun `빈 문자열이 왕복한다`() {
        assertRoundTrips("")
    }

    @Test
    @DisplayName("A1 왕복 — 한 글자")
    fun `한 글자가 왕복한다`() {
        assertRoundTrips("a")
    }

    @Test
    @DisplayName("A1 왕복 — 끝에 개행 하나")
    fun `끝에 개행이 있어도 왕복한다`() {
        assertRoundTrips("a\n")
    }

    @Test
    @DisplayName("A1 왕복 — 빈 줄을 낀 두 줄")
    fun `빈 줄을 껴도 왕복한다`() {
        assertRoundTrips("a\n\nb")
    }

    @Test
    @DisplayName("A1 왕복 — CRLF")
    fun `CRLF 도 왕복한다`() {
        assertRoundTrips("a\r\nb")
    }

    @Test
    @DisplayName("A1 왕복 — 개행 하나뿐")
    fun `개행 하나뿐이어도 왕복한다`() {
        assertRoundTrips("\n")
    }

    @Test
    @DisplayName("split 은 줄 수를 그대로 보존한다 — 빈 줄도 원소로 남는다")
    fun `split 이 빈 줄도 원소로 남긴다`() {
        assertThat(splitUnits("a\n\nb")).containsExactly("a", "", "b")
        assertThat(splitUnits("a\n")).containsExactly("a", "")
        assertThat(splitUnits("\n")).containsExactly("", "")
    }

    private fun assertRoundTrips(text: String) {
        assertThat(joinUnits(splitUnits(text))).isEqualTo(text)
    }
}

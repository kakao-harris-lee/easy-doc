package kr.easydoc.core.segment

import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.maskText
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

    @Test
    @DisplayName("대상 줄에 PII 가 있으면 단위의 자리표시자가 전체 마스킹과 같은 번호로 남는다")
    fun `PII 가 있는 줄의 단위 마스킹이 전체와 일치한다`() {
        val fullMasking = maskText("신청자 900101-1234567 님\n둘째 줄")
        val unit = maskedUnitOf(fullMasking, 0)

        assertThat(unit.maskedText.value).isEqualTo("신청자 [[주민등록번호1]] 님")
        assertThat(unit.items).hasSize(1)
        val item = unit.items.single()
        assertThat(item.category).isEqualTo(MaskCategory.RRN)
        assertThat(item.placeholder).isEqualTo("[[주민등록번호1]]")
        assertThat(item.original.reveal()).isEqualTo("900101-1234567")
        assertThat(fullMasking.items).containsExactly(item)
    }
}

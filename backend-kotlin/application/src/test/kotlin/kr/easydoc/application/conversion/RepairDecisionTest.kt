package kr.easydoc.application.conversion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 보정 채택 판정식(CNV-04) — 인벤토리 §3.1 (다). */
class RepairDecisionTest {
    /** 규칙 위반이 없는 문장. 두 축을 섞지 않기 위해 건수는 항상 0으로 고정한다. */
    private val plain = "오늘 서류를 내세요."

    @Test
    @DisplayName("자리표시자를 지키며 위반이 늘지 않으면 채택한다 — 과잉 거부 가드")
    fun `지키면 채택한다`() {
        val decision =
            decideRepairAdoption(
                original = "[[주민등록번호1]] 을 $plain",
                candidate = "$plain [[주민등록번호1]] 확인",
                placeholders = listOf("[[주민등록번호1]]"),
            )

        assertThat(decision.accepted).isTrue()
        assertThat(decision.lostPlaceholders).isEmpty()
    }

    @Test
    @DisplayName("판정은 존재 여부이지 위치·순서가 아니다")
    fun `순서가 바뀌어도 채택한다`() {
        val decision =
            decideRepairAdoption(
                original = "[[주민등록번호1]] 과 [[카드번호1]] 을 확인",
                candidate = "[[카드번호1]] 과 [[주민등록번호1]] 을 확인",
                placeholders = listOf("[[주민등록번호1]]", "[[카드번호1]]"),
            )

        assertThat(decision.accepted).isTrue()
    }

    @Test
    @DisplayName("하나만 잃어도 기각한다 — 전부-아니면-전무가 아니다")
    fun `부분 유실도 기각한다`() {
        val decision =
            decideRepairAdoption(
                original = "[[주민등록번호1]] 과 [[카드번호1]] 을 확인",
                candidate = "[[주민등록번호1]] 만 확인",
                placeholders = listOf("[[주민등록번호1]]", "[[카드번호1]]"),
            )

        assertThat(decision.accepted).isFalse()
        assertThat(decision.lostPlaceholders).containsExactly("[[카드번호1]]")
    }

    @Test
    @DisplayName("1차 결과에 애초에 없던 자리표시자는 '잃은 것'이 아니다")
    fun `원본에 없으면 유실이 아니다`() {
        val decision =
            decideRepairAdoption(
                original = plain,
                candidate = plain,
                placeholders = listOf("[[주민등록번호1]]"),
            )

        assertThat(decision.accepted).isTrue()
        assertThat(decision.lostPlaceholders).isEmpty()
    }

    @Test
    @DisplayName("판정의 입력이 된 두 건수를 함께 보고한다")
    fun `건수를 보고한다`() {
        val decision = decideRepairAdoption(original = plain, candidate = plain, placeholders = emptyList())

        assertThat(decision.originalIssueCount).isZero()
        assertThat(decision.candidateIssueCount).isZero()
    }
}

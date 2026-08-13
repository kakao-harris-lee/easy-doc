package kr.easydoc.application.conversion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 보정 채택 판정식(CNV-04) — 인벤토리 §3.1 (다).
 *
 * > **채택 = (자리표시자를 하나도 잃지 않았다) AND (규칙 위반 건수가 늘지 않았다)**
 *
 * 여기서 재는 것은 **자리표시자 축의 경계**다. 건수 축의 경계(같으면 채택)는
 * `ConvertDocumentUseCaseTest` 와 parity fixture 가 실제 위반 문장으로 재고, 이 파일은
 * 그것과 무관하게 자리표시자 규칙만 고립시킨다 — 어려운 말 사전이 바뀌어도 여기는
 * 흔들리지 않아야 하고, 흔들리면 그것 자체가 신호다.
 *
 * 명세 §2.1 이 "세 가지 잘못된 구현이 기존 5건을 통과했다"고 적은 자리가 아래 셋이다.
 */
class RepairDecisionTest {
    /** 규칙 위반이 없는 문장. 두 축을 섞지 않기 위해 건수는 항상 0으로 고정한다. */
    private val plain = "오늘 서류를 내세요."

    @Test
    @DisplayName("자리표시자를 지키며 위반이 늘지 않으면 채택한다 — 과잉 거부 가드")
    fun `지키면 채택한다`() {
        // 이 케이스가 없으면 '자리표시자가 든 결과는 아예 보정하지 않는' 구현이 통과한다.
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
        // 쉬운 글 변환은 문장을 쪼개고 묶는다. 위치·인덱스로 대조하는 구현은 정상 재작성을
        // 전부 기각해 보정이 죽는다.
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
        // 1차가 이미 지운 자리표시자를 보정에게 되살릴 의무를 지우면, 정상적인 보정이
        // 전부 기각된다. 그 자리표시자는 유실 목록으로 검수자에게 간다.
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
        // parity 비교기가 이 두 수를 되먹여 accepted 를 다시 계산한다. 보고하지 않으면
        // 게이트는 "정책 판정의 입력이 없다"로 막는다.
        val decision = decideRepairAdoption(original = plain, candidate = plain, placeholders = emptyList())

        assertThat(decision.originalIssueCount).isZero()
        assertThat(decision.candidateIssueCount).isZero()
    }
}

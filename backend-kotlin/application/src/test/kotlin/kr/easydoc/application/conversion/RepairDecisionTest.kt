package kr.easydoc.application.conversion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
                maskedSource = "",
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
                maskedSource = "",
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
                maskedSource = "",
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
                maskedSource = "",
            )

        assertThat(decision.accepted).isTrue()
        assertThat(decision.lostPlaceholders).isEmpty()
    }

    @Test
    @DisplayName("판정의 입력이 된 두 건수를 함께 보고한다")
    fun `건수를 보고한다`() {
        val decision =
            decideRepairAdoption(original = plain, candidate = plain, placeholders = emptyList(), maskedSource = "")

        assertThat(decision.originalIssueCount).isZero()
        assertThat(decision.candidateIssueCount).isZero()
    }

    @Test
    @DisplayName("maskedSource 에 사실이 없으면(빈 문자열 명시) 사실 보존 게이트는 항상 통과한다")
    fun `빈 원문을 명시하면 사실 보존을 재지 않는다`() {
        val decision =
            decideRepairAdoption(
                original = "전화번호가 없습니다.",
                candidate = "전화번호가 없습니다.",
                placeholders = emptyList(),
                maskedSource = "",
            )

        assertThat(decision.factsMissingBefore).isZero()
        assertThat(decision.factsMissingAfter).isZero()
        assertThat(decision.accepted).isTrue()
    }

    @Nested
    @DisplayName("사실 보존 (backlog §1.3)")
    inner class FactPreservationGate {
        private val source = "02-1234-5678로 문의하고 $plain"

        @Test
        @DisplayName("보정문이 사실을 그대로 지키면 채택한다")
        fun `사실을 지키면 채택한다`() {
            val decision =
                decideRepairAdoption(
                    maskedSource = source,
                    original = "문의는 02-1234-5678요. $plain",
                    candidate = "$plain 문의: 02-1234-5678",
                    placeholders = emptyList(),
                )

            assertThat(decision.accepted).isTrue()
            assertThat(decision.factsMissingBefore).isZero()
            assertThat(decision.factsMissingAfter).isZero()
        }

        @Test
        @DisplayName("보정문이 이미 있던 사실을 새로 빠뜨리면 기각한다")
        fun `보정이 사실을 새로 빠뜨리면 기각한다`() {
            val decision =
                decideRepairAdoption(
                    maskedSource = source,
                    original = "문의는 02-1234-5678요. $plain",
                    candidate = plain,
                    placeholders = emptyList(),
                )

            assertThat(decision.accepted).isFalse()
            assertThat(decision.factsMissingBefore).isZero()
            assertThat(decision.factsMissingAfter).isEqualTo(1)
        }

        @Test
        @DisplayName("이미 빠져 있던 사실이 그대로여도 늘지만 않으면 기각하지 않는다")
        fun `기존 누락이 그대로면 기각하지 않는다`() {
            val decision =
                decideRepairAdoption(
                    maskedSource = source,
                    original = plain,
                    candidate = plain,
                    placeholders = emptyList(),
                )

            assertThat(decision.factsMissingBefore).isEqualTo(1)
            assertThat(decision.factsMissingAfter).isEqualTo(1)
            assertThat(decision.accepted).isTrue()
        }

        @Test
        @DisplayName("건수가 같아도(1→1) 새로 빠뜨린 사실이 다르면 기각한다 — 집합 비교(리뷰 HIGH-3)")
        fun `건수가 같아도 다른 사실을 새로 빠뜨리면 기각한다`() {
            val twoFacts = "3명이 4층에서 신청합니다."
            // 1차 결과: 3명을 빠뜨렸다(4층은 지켰다).
            val draftLosingThreePeople = "4층에서 신청합니다."
            // 보정 후보: 3명은 되살렸지만 4층을 새로 빠뜨렸다 — 건수는 여전히 1개다.
            val candidateLosingFourthFloor = "3명이 신청합니다."

            val decision =
                decideRepairAdoption(
                    maskedSource = twoFacts,
                    original = draftLosingThreePeople,
                    candidate = candidateLosingFourthFloor,
                    placeholders = emptyList(),
                )

            assertThat(decision.factsMissingBefore).isEqualTo(1)
            assertThat(decision.factsMissingAfter).isEqualTo(1)
            assertThat(decision.accepted)
                .withFailMessage("건수만 보면 1→1 이라 채택됐을 것이다 — 새로 빠진 사실(4층)을 놓쳤다")
                .isFalse()
        }
    }
}

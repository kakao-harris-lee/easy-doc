package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReviewSupportAnalyzerTest {
    @Test
    fun `기존 사실 규칙의 누락을 실제 원문 줄과 연결한다`() {
        val assessment =
            analyzeReviewSupport(
                source = "신청 기한은 2026년 10월 31일입니다.\n문의는 02-1234-5678입니다.",
                easyText = "신청 기한을 확인하세요.",
            )

        val signals = assessment.items.filter { it.kind == ReviewItemKind.MISSING_FACT }
        assertThat(signals).isNotEmpty
        assertThat(signals.flatMap { it.sourceAnchors }.flatMap { it.sourceUnitIndexes })
            .contains(0, 1)
        assertThat(signals.map { it.ruleCode }).contains("missing_date", "missing_phone")
    }

    @Test
    fun `의미 동치를 자동 주장하지 않고 사람 확인 다섯 항목을 항상 제공한다`() {
        val assessment = analyzeReviewSupport("A와 B를 모두 제출하세요.", "A 또는 B를 제출하세요.")

        assertThat(assessment.items.filter { it.kind == ReviewItemKind.RELATION_CHECK }.map { it.ruleCode })
            .containsExactly(
                "target_scope",
                "all_or_one",
                "exception_scope",
                "deadline_action",
                "amount_subject",
            )
        assertThat(assessment.items).allMatch { it.state == ReviewItemState.NEEDS_REVIEW }
    }

    @Test
    fun `누락 신호는 백 개로 제한하고 제한 사유를 남긴다`() {
        val source = (1000..1105).joinToString("\n") { "대상 $it 명" }
        val assessment = analyzeReviewSupport(source, "대상을 확인하세요.")

        assertThat(assessment.items.count { it.kind == ReviewItemKind.MISSING_FACT }).isEqualTo(100)
        assertThat(assessment.limitedReasons).contains(ReviewCoverageLimit.SIGNAL_LIMIT)
        assertThat(assessment.coverage).isEqualTo(ReviewCoverage.LIMITED)
    }
}

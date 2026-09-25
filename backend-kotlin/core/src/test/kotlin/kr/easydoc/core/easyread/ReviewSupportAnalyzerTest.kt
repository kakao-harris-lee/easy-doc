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
    fun `집중 검토는 관계 신호가 없으면 항목을 만들지 않는다`() {
        val assessment =
            analyzeReviewSupport(
                source = "신청서를 제출하세요.",
                easyText = "신청서를 내세요.",
                focusedReview = true,
            )

        assertThat(assessment.items).isEmpty()
        assertThat(assessment.coverage).isEqualTo(ReviewCoverage.SUPPORTED)
    }

    @Test
    fun `집중 검토는 모두와 하나의 관계가 바뀐 문단만 실제 신호로 반환한다`() {
        val assessment =
            analyzeReviewSupport(
                source = "신청서와 동의서를 모두 제출하세요.",
                easyText = "신청서나 동의서 중 하나만 내세요.",
                focusedReview = true,
            )

        val item = assessment.items.single()
        assertThat(item.kind).isEqualTo(ReviewItemKind.RELATION_CHECK)
        assertThat(item.ruleCode).isEqualTo("all_or_one")
        assertThat(item.sourceAnchors.single().sourceUnitIndexes).containsExactly(0)
        assertThat(item.easyUnitIndexes).containsExactly(0)
        assertThat(item.state).isEqualTo(ReviewItemState.NEEDS_REVIEW)
    }

    @Test
    fun `집중 검토는 서로 다른 조건 문단의 반대 표현을 비교하지 않는다`() {
        val source = "18세 이상은 신청서를 모두 제출하세요.\n12세 미만은 동의서 중 하나만 제출하세요."
        val assessment = analyzeReviewSupport(source, source, focusedReview = true)

        assertThat(assessment.items).isEmpty()
    }

    @Test
    fun `집중 검토는 사실 앵커로 대응하는 문단의 반전만 표시한다`() {
        val assessment =
            analyzeReviewSupport(
                source = "18세 이상은 신청서를 모두 제출하세요.\n12세 미만은 동의서를 제출하세요.",
                easyText = "신청 방법입니다.\n18세 이상은 신청서 중 하나만 내세요.\n12세 미만은 동의서를 내세요.",
                focusedReview = true,
            )

        val item = assessment.items.single()
        assertThat(item.ruleCode).isEqualTo("all_or_one")
        assertThat(item.sourceAnchors.single().sourceUnitIndexes).containsExactly(0)
        assertThat(item.easyUnitIndexes).containsExactly(1)
    }

    @Test
    fun `집중 검토는 추정 대응으로 관계 반전을 주장하지 않고 분석 제한을 알린다`() {
        val assessment =
            analyzeReviewSupport(
                source = "서류를 모두 제출하세요.\n문의 방법을 확인하세요.",
                easyText = "서류를 내세요.\n전화 또는 방문으로 문의하세요.",
                focusedReview = true,
            )

        assertThat(assessment.items).isEmpty()
        assertThat(assessment.limitedReasons).contains(ReviewCoverageLimit.MAPPING_UNAVAILABLE)
        assertThat(assessment.coverage).isEqualTo(ReviewCoverage.LIMITED)
    }

    @Test
    fun `집중 검토는 쉬운 글 위치가 없는 실제 누락 신호도 보존한다`() {
        val assessment =
            analyzeReviewSupport(
                source = "문의는 02-1234-5678로 하세요.",
                easyText = "궁금한 점은 담당자에게 물어보세요.",
                focusedReview = true,
            )

        val item = assessment.items.single { it.ruleCode == "missing_phone" }
        assertThat(item.sourceAnchors).isNotEmpty
        assertThat(item.easyUnitIndexes).isEmpty()
    }

    @Test
    fun `누락 신호는 백 개로 제한하고 제한 사유를 남긴다`() {
        val source = (1000..1105).joinToString("\n") { "대상 $it 명" }
        val assessment = analyzeReviewSupport(source, "대상을 확인하세요.")

        assertThat(assessment.items).hasSize(100)
        assertThat(assessment.items.count { it.kind == ReviewItemKind.MISSING_FACT }).isEqualTo(95)
        assertThat(assessment.items.count { it.kind == ReviewItemKind.RELATION_CHECK }).isEqualTo(5)
        assertThat(assessment.limitedReasons).contains(ReviewCoverageLimit.SIGNAL_LIMIT)
        assertThat(assessment.coverage).isEqualTo(ReviewCoverage.LIMITED)
    }
}

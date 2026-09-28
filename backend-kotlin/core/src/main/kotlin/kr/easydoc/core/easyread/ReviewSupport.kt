package kr.easydoc.core.easyread

import java.time.Instant
import java.util.UUID

enum class ReviewItemKind(val wireName: String) {
    MISSING_FACT("missing_fact"),
    RELATION_CHECK("relation_check"),
}

enum class ReviewItemState(val wireName: String) {
    NEEDS_REVIEW("needs_review"),
    CONFIRMED("confirmed"),
    NOT_APPLICABLE("not_applicable"),
}

enum class ReviewCoverage(val wireName: String) {
    SUPPORTED("supported"),
    LIMITED("limited"),
}

enum class ReviewCoverageLimit(val wireName: String) {
    MAPPING_UNAVAILABLE("mapping_unavailable"),
    SIGNAL_LIMIT("signal_limit"),
    AMBIGUOUS_SOURCE("ambiguous_source"),
}

data class SourceAnchor(
    val sourceUnitIndexes: List<Int>,
    val quote: String,
) {
    override fun toString(): String = "SourceAnchor(indexes=$sourceUnitIndexes, quote=[본문] ${quote.length}자)"
}

data class ReviewItem(
    val itemId: UUID,
    val kind: ReviewItemKind,
    val ruleCode: String,
    val sourceAnchors: List<SourceAnchor>,
    val easyUnitIndexes: List<Int>,
    val state: ReviewItemState = ReviewItemState.NEEDS_REVIEW,
    val reason: String? = null,
    val confirmedBy: UUID? = null,
    val confirmedAt: Instant? = null,
) {
    override fun toString(): String =
        "ReviewItem(itemId=$itemId, kind=${kind.wireName}, ruleCode=$ruleCode, " +
            "anchors=${sourceAnchors.size}, easyUnits=${easyUnitIndexes.size}, state=${state.wireName}, " +
            "reason=${if (reason == null) "없음" else "[본문] ${reason.length}자"})"
}

data class ReviewAnalysis(
    val coverage: ReviewCoverage,
    val limitedReasons: List<ReviewCoverageLimit>,
    val items: List<ReviewItem>,
)

/**
 * 기존 사실 보존 규칙의 결정적 누락 신호와 관계 확인 항목을 만든다.
 * [focusedReview]가 꺼지면 롤백을 위해 기존 5종 확인표를 보존하고, 켜지면
 * 원문과 쉬운 글에서 서로 반대되는 관계 표현이 실제로 발견되거나 중요 한정 표현이
 * 누락된 경우만 신호를 낸다. 이 결과는 의미 정확성의 증명이 아니며 신호 0건도 정확성 완료를
 * 뜻하지 않는다.
 * 모든 항목의 초기 상태는 담당자 확인 필요다.
 */
fun analyzeReviewSupport(
    source: String,
    easyText: String,
    focusedReview: Boolean = false,
): ReviewAnalysis = ReviewSupportAnalyzer.analyze(source, easyText, focusedReview)

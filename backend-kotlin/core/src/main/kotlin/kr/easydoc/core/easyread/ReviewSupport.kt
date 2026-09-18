package kr.easydoc.core.easyread

import kr.easydoc.core.segment.splitUnits
import java.nio.charset.StandardCharsets
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
 * 기존 사실 보존 규칙의 결정적 누락 신호와, 자동 의미 판정을 하지 않는 고정 관계 확인표를 만든다.
 * 이 결과는 의미 정확성의 증명이 아니며 모든 항목의 초기 상태는 담당자 확인 필요다.
 */
fun analyzeReviewSupport(
    source: String,
    easyText: String,
): ReviewAnalysis {
    val sourceUnits = splitUnits(source)
    val easyUnits = splitUnits(easyText)
    val missing = findMissingFacts(source, easyText)
    val signalLimitReached = missing.size > MAX_MISSING_SIGNALS
    var ambiguous = false

    val signals =
        missing.take(MAX_MISSING_SIGNALS).map { issue ->
            val sourceIndexes =
                sourceUnits.mapIndexedNotNull { index, unit -> index.takeIf { issue.value in unit } }
            if (sourceIndexes.size > 1) ambiguous = true
            ReviewItem(
                itemId = stableItemId("missing", issue.kind.name, issue.value),
                kind = ReviewItemKind.MISSING_FACT,
                ruleCode = "missing_${issue.kind.name.lowercase()}",
                sourceAnchors =
                    if (sourceIndexes.isEmpty()) emptyList() else listOf(SourceAnchor(sourceIndexes, issue.value)),
                easyUnitIndexes = easyUnits.mapIndexedNotNull { index, unit -> index.takeIf { issue.value in unit } },
            )
        }
    val relations =
        RELATION_RULES.map { rule ->
            ReviewItem(
                itemId = stableItemId("relation", rule, source),
                kind = ReviewItemKind.RELATION_CHECK,
                ruleCode = rule,
                sourceAnchors = emptyList(),
                easyUnitIndexes = emptyList(),
            )
        }
    val limits =
        buildList {
            if (sourceUnits.size > RELIABLE_MAPPING_UNIT_LIMIT || easyUnits.size > RELIABLE_MAPPING_UNIT_LIMIT) {
                add(ReviewCoverageLimit.MAPPING_UNAVAILABLE)
            }
            if (signalLimitReached) add(ReviewCoverageLimit.SIGNAL_LIMIT)
            if (ambiguous) add(ReviewCoverageLimit.AMBIGUOUS_SOURCE)
        }
    return ReviewAnalysis(
        coverage = if (limits.isEmpty()) ReviewCoverage.SUPPORTED else ReviewCoverage.LIMITED,
        limitedReasons = limits,
        items = signals + relations,
    )
}

private fun stableItemId(vararg parts: String): UUID =
    UUID.nameUUIDFromBytes(parts.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))

private const val MAX_MISSING_SIGNALS = 100
private const val RELIABLE_MAPPING_UNIT_LIMIT = 200
private val RELATION_RULES =
    listOf("target_scope", "all_or_one", "exception_scope", "deadline_action", "amount_subject")

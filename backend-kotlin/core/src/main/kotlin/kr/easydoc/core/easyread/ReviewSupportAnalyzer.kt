package kr.easydoc.core.easyread

import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.alignSegments
import kr.easydoc.core.segment.splitUnits
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Stateless helpers for assembling the review-support analysis. */
internal object ReviewSupportAnalyzer {
    fun analyze(
        source: String,
        easyText: String,
        focusedReview: Boolean,
    ): ReviewAnalysis {
        val sourceUnits = splitUnits(source)
        val easyUnits = splitUnits(easyText)
        val missing = findMissingFacts(source, easyText)
        val missingItems = missingReviewItems(missing, sourceUnits, easyUnits)
        val relationTargets = if (focusedReview) relationTargets(sourceUnits, easyUnits) else emptyMap()
        val relations = reviewRelations(source, sourceUnits, easyUnits, relationTargets, focusedReview)
        val items = limitItems(missingItems.items, relations, focusedReview)
        val limits =
            coverageLimits(
                sourceUnits = sourceUnits,
                easyUnits = easyUnits,
                relationMappingLimited =
                    focusedReview && missingRelationMapping(sourceUnits, easyUnits, relationTargets),
                signalCount = missing.size + relations.size,
                ambiguousSource = missingItems.ambiguousSource,
            )
        return ReviewAnalysis(
            coverage = if (limits.isEmpty()) ReviewCoverage.SUPPORTED else ReviewCoverage.LIMITED,
            limitedReasons = limits,
            items = items,
        )
    }

    private fun missingReviewItems(
        missing: List<FactIssue>,
        sourceUnits: List<String>,
        easyUnits: List<String>,
    ): MissingReviewItems {
        var ambiguousSource = false
        val items =
            missing.take(MAX_REVIEW_ITEMS).map { issue ->
                val sourceIndexes =
                    sourceUnits.mapIndexedNotNull { index, unit -> index.takeIf { issue.value in unit } }
                if (sourceIndexes.size > 1) ambiguousSource = true
                ReviewItem(
                    itemId = stableItemId("missing", issue.kind.name, issue.value),
                    kind = ReviewItemKind.MISSING_FACT,
                    ruleCode = "missing_${issue.kind.name.lowercase()}",
                    sourceAnchors =
                        if (sourceIndexes.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(SourceAnchor(sourceIndexes, issue.value))
                        },
                    easyUnitIndexes =
                        easyUnits.mapIndexedNotNull {
                            index,
                            unit,
                            ->
                            index.takeIf { issue.value in unit }
                        },
                )
            }
        return MissingReviewItems(items = items, ambiguousSource = ambiguousSource)
    }

    private fun reviewRelations(
        source: String,
        sourceUnits: List<String>,
        easyUnits: List<String>,
        relationTargets: Map<Int, List<IndexedValue<String>>>,
        focusedReview: Boolean,
    ): List<ReviewItem> =
        if (focusedReview) {
            findRelationSignals(sourceUnits, easyUnits, relationTargets).map { signal ->
                ReviewItem(
                    itemId =
                        stableItemId(
                            "focused-relation",
                            signal.ruleCode,
                            signal.sourceUnitIndex.toString(),
                            signal.quote,
                        ),
                    kind = ReviewItemKind.RELATION_CHECK,
                    ruleCode = signal.ruleCode,
                    sourceAnchors = listOf(SourceAnchor(listOf(signal.sourceUnitIndex), signal.quote)),
                    easyUnitIndexes = signal.easyUnitIndexes,
                )
            }
        } else {
            LEGACY_RELATION_RULES.map { rule ->
                ReviewItem(
                    itemId = stableItemId("relation", rule, source),
                    kind = ReviewItemKind.RELATION_CHECK,
                    ruleCode = rule,
                    sourceAnchors = emptyList(),
                    easyUnitIndexes = emptyList(),
                )
            }
        }

    private fun limitItems(
        missingItems: List<ReviewItem>,
        relations: List<ReviewItem>,
        focusedReview: Boolean,
    ): List<ReviewItem> =
        if (focusedReview) {
            // 관계 반전은 조건 의미가 달라진 직접 신호라 먼저 보존한다. 나머지는 제한 사유로 알린다.
            (relations + missingItems).take(MAX_REVIEW_ITEMS)
        } else {
            // 롤백 모드의 고정 다섯 항목을 보존하되 계약의 응답 상한을 넘기지 않는다.
            missingItems.take(MAX_REVIEW_ITEMS - relations.size) + relations
        }

    private fun coverageLimits(
        sourceUnits: List<String>,
        easyUnits: List<String>,
        relationMappingLimited: Boolean,
        signalCount: Int,
        ambiguousSource: Boolean,
    ): List<ReviewCoverageLimit> =
        buildList {
            if (
                sourceUnits.size > RELIABLE_MAPPING_UNIT_LIMIT ||
                easyUnits.size > RELIABLE_MAPPING_UNIT_LIMIT || relationMappingLimited
            ) {
                add(ReviewCoverageLimit.MAPPING_UNAVAILABLE)
            }
            if (signalCount > MAX_REVIEW_ITEMS) add(ReviewCoverageLimit.SIGNAL_LIMIT)
            if (ambiguousSource) add(ReviewCoverageLimit.AMBIGUOUS_SOURCE)
        }

    private data class MissingReviewItems(
        val items: List<ReviewItem>,
        val ambiguousSource: Boolean,
    )

    private fun stableItemId(vararg parts: String): UUID =
        UUID.nameUUIDFromBytes(parts.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))

    private const val MAX_REVIEW_ITEMS = 100
    private const val RELIABLE_MAPPING_UNIT_LIMIT = 200
    private val LEGACY_RELATION_RULES =
        listOf("target_scope", "all_or_one", "exception_scope", "deadline_action", "amount_subject")

    private data class RelationSignal(
        val ruleCode: String,
        val sourceUnitIndex: Int,
        val quote: String,
        val easyUnitIndexes: List<Int>,
    ) {
        override fun toString(): String =
            "RelationSignal(ruleCode=$ruleCode, sourceUnitIndex=$sourceUnitIndex, " +
                "quote=${quote.length}자, easyUnitIndexes=$easyUnitIndexes)"
    }

    /**
     * 추정 위치나 다른 원문 조건이 섞인 병합 문단은 관계 반전의 근거로 쓰지 않는다.
     * 양쪽 모두 한 문단이거나 기존 사실 앵커로 단일 원문 대응이 확인된 경우만 비교한다.
     */
    private fun relationTargets(
        sourceUnits: List<String>,
        easyUnits: List<String>,
    ): Map<Int, List<IndexedValue<String>>> =
        when {
            sourceUnits.size > RELIABLE_MAPPING_UNIT_LIMIT || easyUnits.size > RELIABLE_MAPPING_UNIT_LIMIT -> {
                emptyMap()
            }

            sourceUnits.size == 1 && easyUnits.size == 1 -> {
                mapOf(0 to easyUnits.withIndex().toList())
            }

            else -> {
                alignSegments(sourceUnits, easyUnits)
                    .units
                    .filter { it.confidence == SegmentConfidence.HIGH && it.sourceUnitIndexes.size == 1 }
                    .groupBy(
                        { it.sourceUnitIndexes.single() },
                        { IndexedValue(it.easyUnitIndex, easyUnits[it.easyUnitIndex]) },
                    )
            }
        }

    private fun missingRelationMapping(
        sourceUnits: List<String>,
        easyUnits: List<String>,
        targets: Map<Int, List<IndexedValue<String>>>,
    ): Boolean =
        sourceUnits.withIndex().any { (index, unit) ->
            RELATION_MARKERS.any { it.containsMatchIn(unit) } && unit !in easyUnits && targets[index].isNullOrEmpty()
        }

    private fun findRelationSignals(
        sourceUnits: List<String>,
        easyUnits: List<String>,
        targets: Map<Int, List<IndexedValue<String>>>,
    ): List<RelationSignal> {
        val signals = mutableListOf<RelationSignal>()
        sourceUnits.forEachIndexed { sourceIndex, sourceUnit ->
            if (sourceUnit in easyUnits) return@forEachIndexed
            val candidates = targets[sourceIndex].orEmpty()
            if (candidates.isEmpty()) return@forEachIndexed
            opposingSignal(sourceUnit, candidates, ALL_MARKER, ONE_MARKER)?.let { easyIndexes ->
                signals += RelationSignal("all_or_one", sourceIndex, sourceUnit, easyIndexes)
            }
            opposingSignal(sourceUnit, candidates, LOWER_BOUND_MARKER, BELOW_BOUND_MARKER)?.let { easyIndexes ->
                signals += RelationSignal("target_scope", sourceIndex, sourceUnit, easyIndexes)
            }
            opposingSignal(sourceUnit, candidates, UPPER_BOUND_MARKER, ABOVE_BOUND_MARKER)?.let { easyIndexes ->
                signals += RelationSignal("target_scope", sourceIndex, sourceUnit, easyIndexes)
            }
            if (
                EXCEPTION_MARKER.containsMatchIn(sourceUnit) &&
                candidates.none { EXCEPTION_MARKER.containsMatchIn(it.value) }
            ) {
                signals += RelationSignal("exception_scope", sourceIndex, sourceUnit, emptyList())
            }
            if (
                DEADLINE_MARKER.containsMatchIn(sourceUnit) &&
                candidates.none { DEADLINE_MARKER.containsMatchIn(it.value) }
            ) {
                signals += RelationSignal("deadline_action", sourceIndex, sourceUnit, emptyList())
            }
        }
        return signals.distinctBy { it.ruleCode to it.sourceUnitIndex }
    }

    private fun opposingSignal(
        sourceUnit: String,
        easyUnits: List<IndexedValue<String>>,
        first: Regex,
        second: Regex,
    ): List<Int>? {
        val sourceHasFirst = first.containsMatchIn(sourceUnit)
        val sourceHasSecond = second.containsMatchIn(sourceUnit)
        val opposite = if (sourceHasFirst) second else first
        val original = if (sourceHasFirst) first else second
        if (sourceHasFirst == sourceHasSecond || easyUnits.any { original.containsMatchIn(it.value) }) return null
        return easyUnits
            .mapNotNull { (index, unit) -> index.takeIf { opposite.containsMatchIn(unit) } }
            .takeIf { it.isNotEmpty() }
    }

    private val ALL_MARKER = Regex("(?:모두|전부|각각|빠짐없이|및)")
    private val ONE_MARKER = Regex("(?:중\\s*(?:하나|한\\s*개)|하나만|한\\s*가지만|또는|혹은)")
    private val LOWER_BOUND_MARKER = Regex("(?:이상|초과)")
    private val BELOW_BOUND_MARKER = Regex("(?:미만|이하)")
    private val UPPER_BOUND_MARKER = Regex("(?:이하|미만)")
    private val ABOVE_BOUND_MARKER = Regex("(?:초과|이상)")
    private val EXCEPTION_MARKER = Regex("(?:제외|예외|다만|(?:^|[.!?]\\s*)단[, ])")
    private val DEADLINE_MARKER = Regex("(?:기한|마감|\\d\\s*(?:일|주|개월)이내|까지)")
    private val RELATION_MARKERS =
        listOf(ALL_MARKER, ONE_MARKER, LOWER_BOUND_MARKER, BELOW_BOUND_MARKER, EXCEPTION_MARKER, DEADLINE_MARKER)
}

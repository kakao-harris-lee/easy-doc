package kr.easydoc.core.actionguide

import kr.easydoc.core.segment.MAX_SOURCE_ANCHORS
import kr.easydoc.core.segment.MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS

/**
 * Reduces only adjacent, fully contiguous anchors to their exact source span.
 *
 * The source span contains exactly the units covered by both input anchors, so
 * this cannot discard evidence or make a gap look supported. Non-contiguous
 * anchors and spans over the quote limit are deliberately left untouched.
 */
internal object ActionGuideCandidateAnchorCompactor {
    fun compact(
        candidate: ActionGuideCandidate,
        sourceUnits: List<String>,
    ): ActionGuideCandidate =
        candidate.copy(
            sections =
                candidate.sections.map { section ->
                    section.copy(
                        items =
                            section.items.map { item ->
                                compactItem(item, sourceUnits)
                            },
                    )
                },
        )

    private fun compactItem(
        item: ActionGuideItem,
        sourceUnits: List<String>,
    ): ActionGuideItem {
        if (item.sourceAnchors.size <= MAX_SOURCE_ANCHORS) return item

        val compacted = mutableListOf<ActionGuideSourceAnchor>()
        item.sourceAnchors.forEach { anchor ->
            val previous = compacted.lastOrNull()
            val merged = previous?.let { mergeIfSafe(it, anchor, sourceUnits) }
            if (merged == null) {
                compacted += anchor
            } else {
                compacted[compacted.lastIndex] = merged
            }
        }
        return if (compacted == item.sourceAnchors) item else item.copy(sourceAnchors = compacted)
    }

    private fun mergeIfSafe(
        left: ActionGuideSourceAnchor,
        right: ActionGuideSourceAnchor,
        sourceUnits: List<String>,
    ): ActionGuideSourceAnchor? {
        val leftIndexes = left.sourceUnitIndexes
        val rightIndexes = right.sourceUnitIndexes
        val hasIndexes = leftIndexes.isNotEmpty() && rightIndexes.isNotEmpty()
        val indexesInSource =
            leftIndexes.all { it in sourceUnits.indices } && rightIndexes.all { it in sourceUnits.indices }
        val contiguous = isContiguous(leftIndexes) && isContiguous(rightIndexes)
        val adjacent =
            leftIndexes.lastOrNull()?.let { leftEnd ->
                rightIndexes.firstOrNull()?.let { rightStart ->
                    leftEnd != Int.MAX_VALUE && rightStart == leftEnd + 1
                }
            } ?: false
        var mergeable = hasIndexes
        if (mergeable) mergeable = indexesInSource
        if (mergeable) mergeable = contiguous
        if (mergeable) mergeable = adjacent
        if (!mergeable) return null

        val indexes = (leftIndexes.first()..rightIndexes.last()).toList()
        val quote = sourceUnits.subList(indexes.first(), indexes.last() + 1).joinToString("\n")
        return quote
            .takeIf { it.codePointCount(0, it.length) <= MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS }
            ?.let { ActionGuideSourceAnchor(indexes, it) }
    }

    private fun isContiguous(indexes: List<Int>): Boolean =
        indexes.zipWithNext().all { (left, right) -> right == left + 1 }
}

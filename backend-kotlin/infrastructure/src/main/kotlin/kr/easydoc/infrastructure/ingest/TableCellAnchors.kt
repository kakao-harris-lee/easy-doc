package kr.easydoc.infrastructure.ingest

private val UNIT_MARKER = Regex("(?:^|[\\s\\[\\(])(?:단위|unit)\\s*[:：]", RegexOption.IGNORE_CASE)
private val INLINE_FOOTNOTE_MARKER = Regex("^※\\s*")

/** 한 셀의 source unit과 명시적 단위·각주 표식을 공통 방식으로 연결한다. */
internal fun attachTableCell(
    cell: MutableTableCell?,
    indexes: List<Int>,
    block: String,
) {
    if (cell == null) return
    indexes.forEach { index -> if (index !in cell.sourceUnitIndexes) cell.sourceUnitIndexes += index }
    val marked = markerIndexes(block, indexes)
    marked.unit.forEach { index -> if (index !in cell.unitAnchorIndexes) cell.unitAnchorIndexes += index }
    marked.footnote.forEach { index -> if (index !in cell.footnoteAnchorIndexes) cell.footnoteAnchorIndexes += index }
}

private data class TableMarkerIndexes(
    val unit: List<Int>,
    val footnote: List<Int>,
)

private fun markerIndexes(
    block: String,
    indexes: List<Int>,
): TableMarkerIndexes {
    val lines =
        block
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    if (lines.size != indexes.size) return TableMarkerIndexes(unit = emptyList(), footnote = emptyList())
    return TableMarkerIndexes(
        unit = lines.zip(indexes).filter { (line, _) -> UNIT_MARKER.containsMatchIn(line) }.map { (_, index) -> index },
        footnote =
            lines
                .zip(indexes)
                .filter { (line, _) -> INLINE_FOOTNOTE_MARKER.containsMatchIn(line) }
                .map { (_, index) -> index },
    )
}

package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.TableCellStructure
import kr.easydoc.core.document.TableStructure
import kr.easydoc.core.document.TableSupportReason
import kr.easydoc.core.document.TableSupportStatus
import org.w3c.dom.Node
import java.util.IdentityHashMap

private const val ROW_ELEMENT = "tr"
private const val CELL_ELEMENT = "tc"
private const val MAX_ROWS = 100
private const val MAX_COLUMNS = 20
private const val MAX_TABLES = 1_000

/** 추출기의 정규화된 source unit을 표 셀에 연결하는 내부 셀. */
internal class MutableTableCell(
    val row: Int,
    val column: Int,
) {
    val sourceUnitIndexes = mutableListOf<Int>()
    var merged: Boolean = false
    val unitAnchorIndexes = mutableListOf<Int>()
    val footnoteAnchorIndexes = mutableListOf<Int>()
}

/** 형식별 순회기가 채우고 공통 판정기가 R4 구조로 내보내는 내부 표. */
internal class MutableTable(
    val tableId: String,
    var nested: Boolean,
) {
    val rows = mutableListOf<MutableList<MutableTableCell>>()
    val explicitHeaderRows = mutableSetOf<Int>()
    val headerCellCounts = mutableMapOf<Int, Int>()
    var declaredColumnCount: Int? = null
    var declaredRowCount: Int? = null
    var irregular: Boolean = false
    var coordinatesLost: Boolean = false
    var currentRow: Int = -1
    var currentColumn: Int = 0
}

/** DOM 순회용 표·셀 좌표 인덱스. 텍스트 substring 검색을 하지 않는다. */
internal class DomTableCollector {
    private val tables = mutableListOf<MutableTable>()
    private val cells = IdentityHashMap<Node, MutableTableCell>()
    private val cellOwners = IdentityHashMap<MutableTableCell, MutableTable>()

    fun startTable(
        node: Node,
        parentCell: MutableTableCell?,
    ) {
        val table = MutableTable("table-${tables.size}", parentCell != null)
        if (parentCell != null) cellOwners[parentCell]?.nested = true
        val rows = OoxmlDom.childElements(node).filter { OoxmlDom.localName(it) == ROW_ELEMENT }
        table.declaredColumnCount = DocxTableMetadata.declaredColumnCount(node)
        rows.forEachIndexed { rowIndex, row ->
            val rowCells = OoxmlDom.childElements(row).filter { OoxmlDom.localName(it) == CELL_ELEMENT }
            val mutableCells =
                rowCells.mapIndexed { column, cellNode ->
                    MutableTableCell(rowIndex, column).also { cell ->
                        cell.merged = DocxTableMetadata.hasMergeMarker(cellNode)
                        if (DocxTableMetadata.containsFootnoteReference(cellNode)) table.coordinatesLost = true
                        cells[cellNode] = cell
                        cellOwners[cell] = table
                    }
                }
            table.rows.add(mutableCells.toMutableList())
            if (DocxTableMetadata.hasHeaderMarker(row)) {
                table.explicitHeaderRows += rowIndex
                table.headerCellCounts[rowIndex] = rowCells.size
            }
            if (DocxTableMetadata.hasRowOffsetMarker(row)) table.irregular = true
        }
        if (DocxTableMetadata.hasFirstRowMarker(node)) table.explicitHeaderRows += 0
        tables += table
    }

    fun cellFor(node: Node): MutableTableCell? = cells[node]

    fun markCoordinatesLost(cell: MutableTableCell?) {
        if (cell != null) cellOwners[cell]?.coordinatesLost = true
    }

    fun attach(
        cell: MutableTableCell?,
        indexes: List<Int>,
        block: String = "",
    ) {
        attachTableCell(cell, indexes, block)
    }

    fun finish(): List<TableStructure> = buildTableStructures(tables)
}

/** HWPX StAX 순회용 표·셀 좌표 인덱스. */
internal class StreamingTableCollector {
    private val tables = mutableListOf<MutableTable>()
    private val tableStack = ArrayDeque<MutableTable>()
    private val cellStack = ArrayDeque<MutableTableCell>()
    private val cellOwners = IdentityHashMap<MutableTableCell, MutableTable>()

    fun startTable(attributes: Map<String, String> = emptyMap()) {
        tableStack.lastOrNull()?.nested = true
        val table = MutableTable("table-${tables.size}", tableStack.isNotEmpty())
        tables += table
        table.declaredRowCount = attributes.intValue("rowCnt")
        table.declaredColumnCount = attributes.intValue("colCnt")
        if (attributes["repeatHeader"] in setOf("1", "true")) table.explicitHeaderRows += 0
        tableStack.addLast(table)
    }

    fun startRow() {
        val table = tableStack.lastOrNull() ?: return
        table.currentRow++
        table.currentColumn = 0
        table.rows.add(mutableListOf())
    }

    fun startCell(attributes: Map<String, String>) {
        val table = tableStack.lastOrNull() ?: return
        if (table.currentRow < 0) {
            table.currentRow = 0
            table.rows.add(mutableListOf())
        }
        if (attributes.intValue("rowAddr")?.let { it != table.currentRow } == true ||
            attributes.intValue("colAddr")?.let { it != table.currentColumn } == true
        ) {
            table.irregular = true
        }
        val cell = MutableTableCell(table.currentRow, table.currentColumn++)
        cell.merged = hasMergeAttribute(attributes)
        table.rows[table.currentRow].add(cell)
        cellOwners[cell] = table
        if (attributes["header"] in setOf("1", "true")) {
            table.explicitHeaderRows += table.currentRow
            table.headerCellCounts[table.currentRow] = (table.headerCellCounts[table.currentRow] ?: 0) + 1
        }
        cellStack.addLast(cell)
    }

    fun readCellMetadata(
        name: String,
        attributes: Map<String, String>,
    ) {
        val table = tableStack.lastOrNull() ?: return
        val cell = cellStack.lastOrNull() ?: return
        when (name.lowercase()) {
            "celladdr" -> {
                if (attributes.intValue("rowAddr")?.let { it != cell.row } == true ||
                    attributes.intValue("colAddr")?.let { it != cell.column } == true
                ) {
                    table.irregular = true
                }
            }

            "cellspan" -> {
                if (attributes.entries.any { (key, value) ->
                        key.substringAfter(':').lowercase().contains("span") && (value.toIntOrNull() ?: 1) > 1
                    }
                ) {
                    cell.merged = true
                }
            }
        }
    }

    fun markCoordinatesLost(cell: MutableTableCell?) {
        if (cell != null) cellOwners[cell]?.coordinatesLost = true
    }

    fun endCell() {
        if (cellStack.isNotEmpty()) cellStack.removeLast()
    }

    fun endTable() {
        if (tableStack.isNotEmpty()) tableStack.removeLast()
    }

    fun currentCell(): MutableTableCell? = cellStack.lastOrNull()

    fun attach(
        cell: MutableTableCell?,
        indexes: List<Int>,
        block: String = "",
    ) {
        attachTableCell(cell, indexes, block)
    }

    fun finish(): List<TableStructure> = buildTableStructures(tables)
}

private fun buildTableStructures(tables: List<MutableTable>): List<TableStructure> =
    if (tables.size > MAX_TABLES) {
        listOf(unsupportedTable("table-0", TableSupportReason.LIMIT_EXCEEDED))
    } else {
        tables.map(::buildTableStructure)
    }

private fun buildTableStructure(table: MutableTable): TableStructure {
    val dimensions = tableDimensions(table)
    val reason = tableSupportReason(table, dimensions)
    val supported = reason == null
    val cells = if (supported) buildCells(table) else emptyList()
    return TableStructure(
        tableId = table.tableId,
        sourceUnitIndexes = cells.flatMap { it.sourceUnitIndexes }.sorted().distinct(),
        rowCount = dimensions.rowCount,
        columnCount = dimensions.columnCount,
        cells = cells,
        unitAnchors = if (supported) tableAnchorIndexes(table, MutableTableCell::unitAnchorIndexes) else emptyList(),
        footnoteAnchors =
            if (supported) tableAnchorIndexes(table, MutableTableCell::footnoteAnchorIndexes) else emptyList(),
        supportStatus = if (supported) TableSupportStatus.SUPPORTED else TableSupportStatus.UNSUPPORTED,
        supportReason = reason,
    )
}

private data class TableDimensions(
    val rowCount: Int,
    val columnCount: Int,
    val irregular: Boolean,
)

private fun tableDimensions(table: MutableTable): TableDimensions {
    val rowCount = table.rows.size
    val observedColumnCount = table.rows.maxOfOrNull { it.size } ?: 0
    val columnCount = table.declaredColumnCount ?: observedColumnCount
    val irregular =
        rowCount == 0 ||
            table.rows.any { it.size != columnCount } ||
            table.declaredRowCount?.let { it != rowCount } == true
    return TableDimensions(rowCount, columnCount, irregular)
}

private fun tableSupportReason(
    table: MutableTable,
    dimensions: TableDimensions,
): TableSupportReason? {
    val multipleHeaders = table.explicitHeaderRows.any { it != 0 } || table.explicitHeaderRows.size > 1
    val headerMissing =
        table.explicitHeaderRows.isEmpty() ||
            table.headerCellCounts.any { (row, count) -> count != table.rows.getOrNull(row)?.size }
    return when {
        table.nested -> TableSupportReason.NESTED_TABLE
        table.rows.any { row -> row.any(MutableTableCell::merged) } -> TableSupportReason.MERGED_CELLS
        dimensions.irregular || table.irregular -> TableSupportReason.IRREGULAR_GRID
        multipleHeaders -> TableSupportReason.MULTIPLE_HEADER_ROWS
        dimensions.rowCount > MAX_ROWS || dimensions.columnCount > MAX_COLUMNS -> TableSupportReason.LIMIT_EXCEEDED
        table.coordinatesLost -> TableSupportReason.COORDINATES_LOST
        headerMissing -> TableSupportReason.HEADER_ROW_MISSING
        else -> null
    }
}

private fun buildCells(table: MutableTable): List<TableCellStructure> {
    val headers = table.rows.firstOrNull().orEmpty()
    return table.rows.flatten().map { cell ->
        TableCellStructure(
            row = cell.row,
            column = cell.column,
            sourceUnitIndexes = cell.sourceUnitIndexes.sorted().distinct(),
            headerRefs =
                if (cell.row ==
                    0
                ) {
                    emptyList()
                } else {
                    headers.getOrNull(cell.column)?.sourceUnitIndexes.orEmpty()
                },
        )
    }
}

private fun tableAnchorIndexes(
    table: MutableTable,
    selector: (MutableTableCell) -> List<Int>,
): List<Int> =
    table.rows
        .flatten()
        .flatMap(selector)
        .sorted()
        .distinct()

private fun unsupportedTable(
    tableId: String,
    reason: TableSupportReason,
): TableStructure =
    TableStructure(
        tableId = tableId,
        sourceUnitIndexes = emptyList(),
        rowCount = 0,
        columnCount = 0,
        cells = emptyList(),
        supportStatus = TableSupportStatus.UNSUPPORTED,
        supportReason = reason,
    )

private fun hasMergeAttribute(attributes: Map<String, String>): Boolean =
    attributes.any { (name, value) ->
        val normalized = name.substringAfter(':').lowercase()
        (normalized.contains("span") || normalized.contains("merge")) && (value.toIntOrNull() ?: 1) > 1
    }

private fun Map<String, String>.intValue(name: String): Int? =
    entries.firstOrNull { (key, _) -> key.substringAfter(':') == name }?.value?.toIntOrNull()

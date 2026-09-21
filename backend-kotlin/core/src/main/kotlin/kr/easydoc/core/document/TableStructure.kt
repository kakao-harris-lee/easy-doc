package kr.easydoc.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** R4 표 구조를 원문 단위 좌표와 함께 표현하는 상태. */
enum class TableSupportStatus(val wireName: String) {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    UNAVAILABLE("unavailable"),
}

/** 지원하지 않는 표 구조를 설명하는 안정적인 사유 코드. */
enum class TableSupportReason(val wireName: String) {
    MERGED_CELLS("merged_cells"),
    NESTED_TABLE("nested_table"),
    IRREGULAR_GRID("irregular_grid"),
    MULTIPLE_HEADER_ROWS("multiple_header_rows"),
    HEADER_ROW_MISSING("header_row_missing"),
    LIMIT_EXCEEDED("limit_exceeded"),
    COORDINATES_LOST("coordinates_lost"),
    HISTORICAL_DOCUMENT("historical_document"),
    UNSUPPORTED_FORMAT("unsupported_format"),
}

/** 한 셀과 그 셀을 구성하는 기존 source unit 좌표. 텍스트 자체는 복제하지 않는다. */
data class TableCellStructure(
    val row: Int,
    val column: Int,
    val sourceUnitIndexes: List<Int>,
    val headerRefs: List<Int>,
) {
    init {
        require(row >= 0)
        require(column >= 0)
        require(sourceUnitIndexes == sourceUnitIndexes.sorted().distinct())
        require(headerRefs.all { it >= 0 })
    }

    /** 좌표 목록은 본문과 같은 민감도이므로 진단 로그에 그대로 남기지 않는다. */
    override fun toString(): String =
        "TableCellStructure(row=$row,column=$column,sourceUnitCount=${sourceUnitIndexes.size}," +
            "headerRefCount=${headerRefs.size})"
}

/** 원문 표 하나의 구조와 원문 단위 연결. 표 안의 평문을 별도 저장하지 않는다. */
data class TableStructure(
    val tableId: String,
    val sourceUnitIndexes: List<Int>,
    val rowCount: Int,
    val columnCount: Int,
    val cells: List<TableCellStructure>,
    val unitAnchors: List<Int> = emptyList(),
    val footnoteAnchors: List<Int> = emptyList(),
    val supportStatus: TableSupportStatus = TableSupportStatus.SUPPORTED,
    val supportReason: TableSupportReason? = null,
) {
    init {
        require(tableId.matches(TABLE_ID_PATTERN))
        require(rowCount >= 0)
        require(columnCount >= 0)
        require(sourceUnitIndexes == sourceUnitIndexes.sorted().distinct())
        require(unitAnchors.all { it >= 0 })
        require(footnoteAnchors.all { it >= 0 })
        require(
            supportStatus != TableSupportStatus.UNSUPPORTED || supportReason != null,
        ) { "unsupported 표는 사유를 가져야 한다" }
        require(supportStatus != TableSupportStatus.SUPPORTED || supportReason == null) { "supported 표에는 사유가 없어야 한다" }
        require(supportStatus != TableSupportStatus.SUPPORTED || (rowCount > 0 && columnCount > 0)) {
            "지원 표는 행·열 수가 있어야 한다"
        }
        require(
            supportStatus != TableSupportStatus.UNAVAILABLE || supportReason == TableSupportReason.HISTORICAL_DOCUMENT,
        ) {
            "unavailable 표의 사유는 historical_document 여야 한다"
        }
    }

    /** 표 구조에는 본문 좌표가 있으므로 generated data-class 문자열을 로그에 쓰지 않는다. */
    override fun toString(): String =
        "TableStructure(tableId=$tableId,status=${supportStatus.wireName},rows=$rowCount," +
            "columns=$columnCount,cellCount=${cells.size})"

    companion object {
        private val TABLE_ID_PATTERN = Regex("table-[0-9]+")
    }
}

/**
 * 표 구조 payload를 암호화하기 전에 쓰는 버전이 있는 바이너리 표현.
 *
 * payload에는 사용자 문장을 넣지 않고 단위 인덱스·상태·사유만 넣는다. Base64는 저장 표현일
 * 뿐이며, 실제 DB에는 이 문자열 전체가 AEAD로 봉인된 뒤 들어간다. 별도 JSON 라이브러리를
 * core에 끌어들이지 않아 application/core 경계를 유지한다.
 */
object TableStructurePayloadCodec {
    private const val VERSION = 1
    private const val MAX_TABLES = 1_000
    private const val MAX_INDEXES_PER_LIST = 20_000

    fun encode(tables: List<TableStructure>): String {
        require(tables.size <= MAX_TABLES)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeInt(tables.size)
            tables.forEach { table -> writeTable(output, table) }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    /** 손상된 파생 payload는 원문 조회를 막지 않도록 null로 접는다. */
    fun decodeOrNull(encoded: String): List<TableStructure>? =
        runCatching {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                require(input.readInt() == VERSION)
                val count = input.readInt().also { require(it in 0..MAX_TABLES) }
                List(count) { readTable(input) }.also { require(input.available() == 0) }
            }
        }.getOrNull()

    private fun writeTable(
        output: DataOutputStream,
        table: TableStructure,
    ) {
        output.writeUTF(table.tableId)
        output.writeInt(table.supportStatus.ordinal)
        output.writeInt(table.supportReason?.ordinal ?: -1)
        output.writeInt(table.rowCount)
        output.writeInt(table.columnCount)
        writeIndexes(output, table.sourceUnitIndexes)
        writeIndexes(output, table.unitAnchors)
        writeIndexes(output, table.footnoteAnchors)
        output.writeInt(table.cells.size)
        table.cells.forEach { cell ->
            output.writeInt(cell.row)
            output.writeInt(cell.column)
            writeIndexes(output, cell.sourceUnitIndexes)
            writeIndexes(output, cell.headerRefs)
        }
    }

    private fun readTable(input: DataInputStream): TableStructure {
        val tableId = input.readUTF()
        val status =
            TableSupportStatus.entries[
                input.readInt().also {
                    require(
                        it in TableSupportStatus.entries.indices,
                    )
                },
            ]
        val reasonOrdinal = input.readInt()
        require(reasonOrdinal == -1 || reasonOrdinal in TableSupportReason.entries.indices)
        val reason = reasonOrdinal.takeUnless { it == -1 }?.let(TableSupportReason.entries::get)
        val rowCount = input.readInt()
        val columnCount = input.readInt()
        val sourceIndexes = readIndexes(input)
        val unitAnchors = readIndexes(input)
        val footnoteAnchors = readIndexes(input)
        val cells =
            List(input.readInt().also { require(it in 0..MAX_INDEXES_PER_LIST) }) {
                TableCellStructure(
                    row = input.readInt(),
                    column = input.readInt(),
                    sourceUnitIndexes = readIndexes(input),
                    headerRefs = readIndexes(input),
                )
            }
        return TableStructure(
            tableId = tableId,
            sourceUnitIndexes = sourceIndexes,
            rowCount = rowCount,
            columnCount = columnCount,
            cells = cells,
            unitAnchors = unitAnchors,
            footnoteAnchors = footnoteAnchors,
            supportStatus = status,
            supportReason = reason,
        )
    }

    private fun writeIndexes(
        output: DataOutputStream,
        values: List<Int>,
    ) {
        require(values.size <= MAX_INDEXES_PER_LIST)
        output.writeInt(values.size)
        values.forEach(output::writeInt)
    }

    private fun readIndexes(input: DataInputStream): List<Int> =
        List(
            input.readInt().also { require(it in 0..MAX_INDEXES_PER_LIST) },
        ) { input.readInt().also { require(it >= 0) } }
}

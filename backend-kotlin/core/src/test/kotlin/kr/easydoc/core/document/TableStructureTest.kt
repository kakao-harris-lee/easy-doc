package kr.easydoc.core.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TableStructureTest {
    @Test
    @DisplayName("R4 payload codec는 표·셀·header source unit 좌표와 unsupported 상태를 보존한다")
    fun `payload codec round trips coordinates and status`() {
        val original =
            listOf(
                TableStructure(
                    tableId = "table-0",
                    sourceUnitIndexes = listOf(1, 2, 3, 4),
                    rowCount = 2,
                    columnCount = 2,
                    cells =
                        listOf(
                            TableCellStructure(0, 0, listOf(1), emptyList()),
                            TableCellStructure(0, 1, listOf(2), emptyList()),
                            TableCellStructure(1, 0, listOf(3), listOf(1)),
                            TableCellStructure(1, 1, listOf(4), listOf(2)),
                        ),
                ),
                TableStructure(
                    tableId = "table-1",
                    sourceUnitIndexes = emptyList(),
                    rowCount = 0,
                    columnCount = 0,
                    cells = emptyList(),
                    supportStatus = TableSupportStatus.UNSUPPORTED,
                    supportReason = TableSupportReason.MERGED_CELLS,
                ),
            )

        assertThat(TableStructurePayloadCodec.decodeOrNull(TableStructurePayloadCodec.encode(original)))
            .containsExactlyElementsOf(original)
    }

    @Test
    fun `damaged payload is ignored rather than exposing a partial structure`() {
        assertThat(TableStructurePayloadCodec.decodeOrNull("not-a-payload")).isNull()
    }
}

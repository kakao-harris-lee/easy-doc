package kr.easydoc.api.document

import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.SegmentUnit
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `SegmentMapResponse.of` 가 [SegmentMap.sourceUnitKinds] 를 계약 wire 값(소문자)으로
 * 옮기는지 잰다 — P0-4 S8-3, `docs/plans/2026-09-06-p0-4-structure-hints.md` §2 S8-3.
 */
class ConversionDtosTest {
    @Test
    @DisplayName("S8-3 — sourceUnitKinds 의 enum 이름을 소문자 wire 값으로 옮긴다")
    fun `SegmentMapResponse of 가 종류를 소문자로 낸다`() {
        val map =
            SegmentMap(
                sourceUnitCount = 3,
                easyUnitCount = 1,
                units = listOf(SegmentUnit(0, listOf(0, 1, 2), SegmentConfidence.HIGH)),
                sourceUnitKinds = listOf(UnitKind.BODY, UnitKind.TABLE_CELL, UnitKind.LIST_ITEM),
            )

        val response = SegmentMapResponse.of(map)

        assertThat(response.sourceUnitKinds).containsExactly("body", "table_cell", "list_item")
    }

    @Test
    @DisplayName("S8-3 — 원본 단위가 없으면 sourceUnitKinds 도 빈 목록이다")
    fun `원본 단위가 없으면 종류도 없다`() {
        val map = SegmentMap(sourceUnitCount = 0, easyUnitCount = 0, units = emptyList())

        val response = SegmentMapResponse.of(map)

        assertThat(response.sourceUnitKinds).isEmpty()
    }
}

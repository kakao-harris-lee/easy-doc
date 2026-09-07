package kr.easydoc.core.easyread

import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `[구조]` 절 렌더링 — P0-4 S8-2 (계획 §1.3, `docs/plans/2026-09-06-p0-4-structure-hints.md`).
 * B1(전부 BODY→null)은 `PromptTextSnapshotTest`/`PromptsTest`가 `buildUserPrompt`를 통해 고정하고,
 * 여기는 [renderStructureSection] 자체의 성질(run 렌더링·인용 구분자·상한 접기)을 고정한다.
 */
class StructureHintsTest {
    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    @Test
    @DisplayName("전부 BODY 면 절 자체가 없다")
    fun `BODY 뿐이면 null 이다`() {
        val structure = SourceStructure.allBody(3)
        val units = listOf("첫째 줄", "둘째 줄", "셋째 줄")

        assertThat(renderStructureSection(structure, units, fixedIds, maxRuns = 40)).isNull()
    }

    @Nested
    @DisplayName("표 run 하나")
    inner class TableRun {
        private val units = listOf("안내문 제목입니다.", "구분", "금액", "비고", "본문이 이어집니다.")
        private val structure =
            SourceStructure(
                listOf(
                    UnitKind.BODY,
                    UnitKind.TABLE_CELL,
                    UnitKind.TABLE_CELL,
                    UnitKind.TABLE_CELL,
                    UnitKind.BODY,
                ),
            )

        @Test
        @DisplayName("1-based 줄 범위와 첫·끝 칸 문구를 싣는다")
        fun `줄 범위와 문구가 실린다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)

            assertThat(section).isNotNull()
            assertThat(section).startsWith("[구조]")
            assertThat(section).contains("2~4째 줄")
            assertThat(section).contains("「구분」")
            assertThat(section).contains("「비고」")
        }

        @Test
        @DisplayName("인용은 난수 id 구분자 안에 있다")
        fun `인용이 구분자 안에 있다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!
            val id = fixedIds.next()
            val openTag = "<$STRUCTURE_TAG_NAME id=\"$id\">"
            val closeTag = "</$STRUCTURE_TAG_NAME id=\"$id\">"

            val openIndex = section.indexOf(openTag)
            val closeIndex = section.indexOf(closeTag)
            assertThat(openIndex).isGreaterThanOrEqualTo(0)
            assertThat(closeIndex).isGreaterThan(openIndex)

            val cellQuoteIndex = section.indexOf("「구분」")
            assertThat(cellQuoteIndex).isBetween(openIndex, closeIndex)
        }

        @Test
        @DisplayName("셀 규칙 셋을 모두 담는다")
        fun `셀 규칙이 실린다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            assertThat(section).contains("칸을 합치거나 나누지")
            assertThat(section).contains("줄을 바꾸지 말고")
            assertThat(section).contains("낱말 하나뿐인 칸")
        }

        @Test
        @DisplayName("한 줄에 하나씩 규칙은 표·목록 밖에서만 적용된다는 문장이 붙는다")
        fun `범위 예외 문장이 붙는다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            assertThat(section).contains("표·목록 밖에서만 적용")
        }
    }

    @Nested
    @DisplayName("목록 run 하나")
    inner class ListRun {
        private val units = listOf("본문입니다.", "① 첫째 항목", "- 둘째 항목", "이어지는 본문")
        private val structure =
            SourceStructure(listOf(UnitKind.BODY, UnitKind.LIST_ITEM, UnitKind.LIST_ITEM, UnitKind.BODY))

        @Test
        @DisplayName("목록 절과 항목 유지 규칙이 실린다")
        fun `목록 규칙이 실린다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            assertThat(section).contains("목록:")
            assertThat(section).contains("2~3째 줄")
            assertThat(section).contains("「① 첫째 항목」")
            assertThat(section).contains("「- 둘째 항목」")
            assertThat(section).contains("항목 수·순서·앞의 기호")
        }
    }

    @Test
    @DisplayName("표·목록 run 이 함께 있으면 절 하나에 둘 다 나열한다")
    fun `표와 목록이 함께 나열된다`() {
        val units = listOf("구분", "금액", "① 항목")
        val structure = SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL, UnitKind.LIST_ITEM))

        val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

        assertThat(section).contains("표:")
        assertThat(section).contains("목록:")
    }

    @Test
    @DisplayName("run 수가 하나뿐이고 시작·끝 줄이 같으면 범위 대신 한 줄로 말한다")
    fun `한 줄짜리 run 은 물결표 없이 말한다`() {
        val units = listOf("본문", "구분", "본문")
        val structure = SourceStructure(listOf(UnitKind.BODY, UnitKind.TABLE_CELL, UnitKind.BODY))

        val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

        assertThat(section).contains("2째 줄")
        assertThat(section).doesNotContain("2~2째 줄")
    }

    @Nested
    @DisplayName("run 수 상한")
    inner class RunCountCap {
        @Test
        @DisplayName("상한을 넘으면 per-run 나열 대신 한 문장으로 접는다")
        fun `상한 초과는 한 문장으로 접힌다`() {
            // TABLE_CELL 과 BODY 를 번갈아 둬 run 을 여러 개(각 1줄) 만든다.
            val kinds = (1..10).flatMap { listOf(UnitKind.TABLE_CELL, UnitKind.BODY) }
            val units = kinds.indices.map { "줄$it" }
            val structure = SourceStructure(kinds)

            // TABLE_CELL run 이 10개다(BODY 사이사이). maxRuns=5 로 상한을 걸어 넘긴다.
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 5)!!

            assertThat(section).isEqualTo("[구조]\n이 문서에는 표·목록이 많습니다. 줄 수와 순서를 그대로 두세요.")
        }

        @Test
        @DisplayName("상한과 정확히 같으면 접지 않는다")
        fun `상한과 같으면 그대로 나열한다`() {
            val kinds = (1..5).flatMap { listOf(UnitKind.TABLE_CELL, UnitKind.BODY) }
            val units = kinds.indices.map { "줄$it" }
            val structure = SourceStructure(kinds)

            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 5)!!

            assertThat(section).doesNotContain("표·목록이 많습니다")
            assertThat(section).contains("표:")
        }
    }

    @Nested
    @DisplayName("인용 문구 길이 상한 — 리뷰 MEDIUM 3")
    inner class QuoteTruncation {
        @Test
        @DisplayName("200자짜리 셀 문구는 40 코드포인트에서 잘리고 … 이 붙는다")
        fun `긴 문구는 잘린다`() {
            val long = "가".repeat(200)
            val units = listOf(long, "금액")
            val structure = SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL))

            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            assertThat(section)
                .withFailMessage("200자 원문이 그대로 실렸다 — 인용은 잘려야 한다")
                .doesNotContain(long)
            val truncated = "가".repeat(QUOTE_SNIPPET_MAX_CODEPOINTS) + "…"
            assertThat(section).contains("「$truncated」")
        }

        @Test
        @DisplayName("상한 이하 문구는 그대로 실린다 — 자르지 않는다")
        fun `짧은 문구는 그대로다`() {
            val short = "가".repeat(QUOTE_SNIPPET_MAX_CODEPOINTS)
            val units = listOf(short, "금액")
            val structure = SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL))

            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            assertThat(section).contains("「$short」")
            assertThat(section).doesNotContain("$short…")
        }
    }

    @Nested
    @DisplayName("원본 단위가 하나뿐인 호출 — 재변환의 단위 하나짜리 변환")
    inner class SingleUnitCall {
        @Test
        @DisplayName("표 칸 하나면 줄 번호·인용 없이 문단 단위 문장을 낸다")
        fun `표 칸 문단 문장`() {
            val structure = SourceStructure(listOf(UnitKind.TABLE_CELL))
            val section = renderStructureSection(structure, listOf("구분"), fixedIds, maxRuns = 40)

            assertThat(section).isNotNull()
            assertThat(section).doesNotContain("째 줄")
            assertThat(section).doesNotContain(STRUCTURE_TAG_NAME)
            assertThat(section).contains("이 문단은 표의 칸입니다")
        }

        @Test
        @DisplayName("목록 항목 하나면 문단 단위 문장을 낸다")
        fun `목록 항목 문단 문장`() {
            val structure = SourceStructure(listOf(UnitKind.LIST_ITEM))
            val section = renderStructureSection(structure, listOf("① 항목"), fixedIds, maxRuns = 40)

            assertThat(section).isNotNull()
            assertThat(section).doesNotContain("째 줄")
            assertThat(section).contains("이 문단은 목록 항목입니다")
        }

        @Test
        @DisplayName("단위 하나가 BODY 면 절이 없다")
        fun `단위 BODY 면 null 이다`() {
            val structure = SourceStructure(listOf(UnitKind.BODY))
            val section = renderStructureSection(structure, listOf("본문"), fixedIds, maxRuns = 40)

            assertThat(section).isNull()
        }
    }
}

package kr.easydoc.infrastructure.export

import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.SegmentUnit
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * (F) 불변식(계획 §10.3 S6-2) — 검수본 줄은 [ReflectionPlan.written]·[ReflectionPlan.inserted]·
 * [ReflectionPlan.appended](옮김·넘침이 함께, 이미 정해진 차례) 중 **정확히 한 곳**에만 있다.
 *
 * 지도가 없는 차례 짝짓기(`ORDINAL`), 지도를 거절한 차례 폴백(`ORDINAL_FALLBACK`), 지도로
 * 짝지은 합침·나눔(`MAPPED`) — 세 자리 맞춤 갈래를 한 번에 훑어 어느 쪽도 줄을 잃거나
 * 중복하지 않는지 확인한다.
 */
class ReflectionPlanInvariantTest {
    private val docx = DocxOriginalReflector()
    private val hwpx = HwpxOriginalReflector()

    @Test
    @DisplayName("(F) 모든 줄은 written+inserted+appended 중 정확히 한 곳에 있다")
    fun `모든 줄이 정확히 한 곳에만 있다`() {
        scenarios().forEach { scenario ->
            val plan = scenario.plan()
            val placed = plan.written.map { it.line } + plan.inserted.flatMap { it.lines } + plan.appended

            assertThat(placed)
                .withFailMessage(
                    "%s: 배치된 줄이 입력 줄과 다르다 — 어딘가에서 검수본이 사라지거나 중복됐다",
                    scenario.name,
                ).containsExactlyInAnyOrderElementsOf(scenario.lines)
        }
    }

    private class Scenario(
        val name: String,
        val lines: List<String>,
        val plan: () -> ReflectionPlan,
    )

    @Suppress("LongMethod")
    private fun scenarios(): List<Scenario> {
        val plainDocx = IngestFixtures.bytes("sample.docx")
        val tableDocx = IngestFixtures.bytes("sample_table.docx")
        val richHwpx = ExportFixtures.richHwpx()

        val split3 = listOf("첫 문장 앞부분입니다.", "첫 문장 뒷부분입니다.", "둘째 문단입니다.")
        val splitMap =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 3,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val merge1 = listOf("합쳐진 문장입니다.")
        val mergeMap =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 1,
                units = listOf(SegmentUnit(0, listOf(0, 1), SegmentConfidence.HIGH)),
            )

        val rejectedLines = listOf("쉬운 문단 하나.", "쉬운 문단 둘.")
        val rejectedMap =
            SegmentMap(
                sourceUnitCount = 99,
                easyUnitCount = 2,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val ordinalShort = listOf("검수한 문단 하나.")
        val ordinalMany = List(11) { "문단 ${it + 1}." }
        val tableLines = listOf("쉬운 제목", "구분은 이렇습니다", "내용은 이렇습니다", "언제까지", "3월 31일까지")
        val hwpxOverflow = List(8) { "문단 ${it + 1}." }

        // 머리말(source 0)이 displaced 로 빠지고, 본문 unit 1 이 1:N 으로 나뉘는 MAPPED 시나리오 —
        // 머리말 대상과 나눔이 함께 있어도 불변식이 깨지지 않는지 잰다(계획 §10.2 3항 리뷰 항목 2).
        val hwpxHeaderSplitLines =
            listOf("쉬운 머리말", "쉬운 첫 문단 앞", "쉬운 첫 문단 뒤", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")
        val hwpxHeaderSplitMap =
            SegmentMap(
                sourceUnitCount = 6,
                easyUnitCount = 7,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(3, listOf(2), SegmentConfidence.HIGH),
                        SegmentUnit(4, listOf(3), SegmentConfidence.HIGH),
                        SegmentUnit(5, listOf(4), SegmentConfidence.HIGH),
                        SegmentUnit(6, listOf(5), SegmentConfidence.HIGH),
                    ),
            )

        return listOf(
            Scenario("docx 1:N 나눔(MAPPED)", split3) {
                docx.outline(plainDocx, split3, splitMap, mapAttempted = true)!!
            },
            Scenario("docx N:1 합침(MAPPED)", merge1) {
                docx.outline(plainDocx, merge1, mergeMap, mapAttempted = true)!!
            },
            Scenario("docx 지도 거절(ORDINAL_FALLBACK)", rejectedLines) {
                docx.outline(plainDocx, rejectedLines, rejectedMap, mapAttempted = true)!!
            },
            Scenario("docx 차례 짝짓기 — 부족(ORDINAL)", ordinalShort) {
                docx.outline(plainDocx, ordinalShort, map = null, mapAttempted = false)!!
            },
            Scenario("docx 차례 짝짓기 — 초과(ORDINAL)", ordinalMany) {
                docx.outline(plainDocx, ordinalMany, map = null, mapAttempted = false)!!
            },
            Scenario("docx 표 fixture 차례 짝짓기(ORDINAL)", tableLines) {
                docx.outline(tableDocx, tableLines, map = null, mapAttempted = false)!!
            },
            Scenario("hwpx 머리말 사이 + 초과(ORDINAL)", hwpxOverflow) {
                hwpx.outline(richHwpx, hwpxOverflow, map = null, mapAttempted = false)!!
            },
            Scenario("hwpx 머리말 대상 + 1:N 나눔(MAPPED)", hwpxHeaderSplitLines) {
                hwpx.outline(richHwpx, hwpxHeaderSplitLines, hwpxHeaderSplitMap, mapAttempted = true)!!
            },
        )
    }
}

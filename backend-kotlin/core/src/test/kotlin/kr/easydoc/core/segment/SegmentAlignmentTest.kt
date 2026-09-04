package kr.easydoc.core.segment

import kr.easydoc.core.segment.SegmentConfidence.HIGH
import kr.easydoc.core.segment.SegmentConfidence.LOW
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * 정렬 알고리즘 — 계획 §6 S1 수용 기준(A2~A6). A3·A4·A5 의 「주민센터」는 계획 문서의
 * 예시 표현이고, 실제 앵커는 [kr.easydoc.core.easyread.extractFacts] 가 잡는 사실
 * (날짜·연락처·백분율)이다 — 앵커가 자리표시자·사실로 한정된다는 계획 §2 규칙을 지키면서
 * 같은 1:N·N:1·순서 역전 구조를 재현한다.
 */
class SegmentAlignmentTest {
    @Nested
    @DisplayName("A2 — 공통 앵커가 없으면 전부 low 로 떨어져 차례 보간만 남는다")
    inner class NoAnchors {
        @Test
        @DisplayName("원본 2단위 / 쉬운 글 3단위, 공통 사실 0개 → 세 단위 모두 low, [0],[0],[1]")
        fun `앵커가 없으면 비례 보간으로만 대응한다`() {
            val result = alignSegments(sourceUnits = listOf("x", "y"), easyUnits = listOf("a", "b", "c"))

            assertThat(result.sourceUnitCount).isEqualTo(2)
            assertThat(result.easyUnitCount).isEqualTo(3)
            assertThat(result.summary())
                .containsExactly(
                    Triple(0, listOf(0), LOW),
                    Triple(1, listOf(0), LOW),
                    Triple(2, listOf(1), LOW),
                )
        }
    }

    @Nested
    @DisplayName("A3 — 1:N 분할: 원본 한 단위가 쉬운 글 여러 단위로 나뉜다")
    inner class SplitOneToMany {
        @Test
        @DisplayName("원본 0에 두 날짜, 원본 1에 연락처 → 세 단위 모두 high, [0],[0],[1]")
        fun `날짜 둘로 나뉜 원본이 두 쉬운 글 단위와 앵커로 이어진다`() {
            val sourceUnits = listOf("3월 2일과 3월 31일 안내", "032-123-4567 문의")
            val easyUnits = listOf("3월 2일 안내", "3월 31일 안내", "032-123-4567 문의")

            val result = alignSegments(sourceUnits, easyUnits)

            assertThat(result.summary())
                .containsExactly(
                    Triple(0, listOf(0), HIGH),
                    Triple(1, listOf(0), HIGH),
                    Triple(2, listOf(1), HIGH),
                )
        }
    }

    @Nested
    @DisplayName("A4 — N:1 병합: 원본 여러 단위가 쉬운 글 한 단위로 합쳐진다")
    inner class MergeManyToOne {
        @Test
        @DisplayName("원본 0에 날짜, 원본 1에 연락처, 쉬운 글 0이 둘을 다 담음 → [0, 1], high")
        fun `두 원본 단위의 앵커가 한 쉬운 글 단위에 모두 남는다`() {
            val sourceUnits = listOf("3월 2일 접수", "032-123-4567 문의")
            val easyUnits = listOf("3월 2일에 032-123-4567로 문의하세요")

            val result = alignSegments(sourceUnits, easyUnits)

            assertThat(result.summary())
                .containsExactly(Triple(0, listOf(0, 1), HIGH))
        }
    }

    @Nested
    @DisplayName("A5 — 순서 역전: 비단조 앵커는 LIS 로 걸러진다")
    inner class ReorderedAnchors {
        @Test
        @DisplayName("앵커 매칭이 (원본2,쉬운0)·(원본1,쉬운1)·(원본0,쉬운2) → 정확히 한 단위만 high")
        fun `역전된 앵커는 하나만 살아남고 나머지는 low 로 보간된다`() {
            // 원본 순서와 쉬운 글 순서가 완전히 뒤집힌 세 앵커 — 백분율·연락처·날짜.
            val sourceUnits = listOf("할인율 45% 안내", "032-999-8888 문의", "10월 5일 접수")
            val easyUnits = listOf("10월 5일 접수", "032-999-8888 문의", "할인율 45% 안내")

            val result = alignSegments(sourceUnits, easyUnits)

            assertThat(result.units.count { it.confidence == HIGH })
                .withFailMessage("비감소 최장 부분수열의 길이는 1이어야 한다 — 세 앵커가 완전히 역전돼 있다.")
                .isEqualTo(1)
            assertThat(result.units).hasSize(3)
            assertThat(result.units.map { it.easyUnitIndex }).containsExactly(0, 1, 2)
        }
    }

    @Nested
    @DisplayName("A6 — 전사성·결정성")
    inner class TotalityAndDeterminism {
        @Test
        @DisplayName("모든 쉬운 글 단위가 정확히 한 번 나오고, 같은 입력을 두 번 정렬하면 결과가 같다")
        fun `전사성과 결정성을 지킨다`() {
            val sourceUnits = listOf("3월 2일과 3월 31일 안내", "032-123-4567 문의")
            val easyUnits = listOf("3월 2일 안내", "3월 31일 안내", "032-123-4567 문의")

            val first = alignSegments(sourceUnits, easyUnits)
            val second = alignSegments(sourceUnits, easyUnits)

            assertThat(first.units.map { it.easyUnitIndex })
                .withFailMessage("모든 쉬운 글 단위 색인이 정확히 한 번씩 나와야 한다.")
                .containsExactlyInAnyOrderElementsOf(easyUnits.indices.toList())
            assertThat(first).isEqualTo(second)
        }
    }

    @Nested
    @DisplayName("경계 — 빈 입력")
    inner class EmptyInputs {
        @Test
        @DisplayName("원본·쉬운 글 모두 빈 텍스트(단위 하나, 빈 문자열) → low, 원본 단위 하나뿐이라 그 자리로만 간다")
        fun `빈 텍스트도 단위 하나로 정렬된다`() {
            val result = alignSegments(splitUnits(""), splitUnits(""))

            assertThat(result.sourceUnitCount).isEqualTo(1)
            assertThat(result.easyUnitCount).isEqualTo(1)
            assertThat(result.summary()).containsExactly(Triple(0, listOf(0), LOW))
        }

        @Test
        @DisplayName("쉬운 글 단위 목록이 비어 있으면(문서 자체가 빈 리스트) 대응표도 비어 있다")
        fun `쉬운 글 단위가 없으면 units 가 비어 있다`() {
            val result = alignSegments(listOf("a", "b"), emptyList())

            assertThat(result.sourceUnitCount).isEqualTo(2)
            assertThat(result.easyUnitCount).isEqualTo(0)
            assertThat(result.units).isEmpty()
        }

        @Test
        @DisplayName("원본 단위 목록이 비어 있으면 모든 쉬운 글 단위가 대응 없음(low, 빈 목록)으로 떨어진다")
        fun `원본 단위가 없으면 대응을 찾지 못한다`() {
            val result = alignSegments(emptyList(), listOf("a", "b"))

            assertThat(result.sourceUnitCount).isEqualTo(0)
            assertThat(result.easyUnitCount).isEqualTo(2)
            assertThat(result.summary())
                .containsExactly(Triple(0, emptyList(), LOW), Triple(1, emptyList(), LOW))
        }
    }

    @Nested
    @DisplayName("경계 — 끝에 개행이 있는 입력도 split 결과 그대로 정렬한다")
    inner class TrailingNewline {
        @Test
        @DisplayName("끝 개행이 만든 빈 줄도 하나의 단위로 정렬된다")
        fun `끝 개행이 만든 빈 단위도 정렬 대상이다`() {
            val sourceUnits = splitUnits("3월 2일 접수\n")
            val easyUnits = splitUnits("3월 2일 접수\n")

            val result = alignSegments(sourceUnits, easyUnits)

            assertThat(sourceUnits).containsExactly("3월 2일 접수", "")
            assertThat(result.easyUnitCount).isEqualTo(2)
            assertThat(result.units.map { it.easyUnitIndex }).containsExactly(0, 1)
        }
    }

    @Nested
    @DisplayName("성능 — 20,000자에 가까운 입력도 2,000ms 안에 끝난다")
    inner class Performance {
        @Test
        @DisplayName("고유 앵커 3,000개가 완전히 뒤섞여도 빠르게 정렬한다")
        fun `대량 입력에서도 정렬이 빠르다`() {
            val unitCount = 3_000
            // 각 줄이 서로 다른 AMOUNT 앵커 하나뿐인 유일 문장 — 원본·쉬운 글 양쪽 20,000자에
            // 가깝다(단위당 6~8자 * 3,000단위). 쉬운 글 쪽 순서를 뒤섞어 LIS 가 진짜 일을
            // 하게 만든다(정렬돼 있으면 전부 그대로 통과해 알고리즘의 분기 대부분을 안 탄다).
            val sourceUnits = (0 until unitCount).map { "${it}원 결제" }
            val easyUnits = sourceUnits.shuffled(Random(42))

            // 첫 호출은 JIT 워밍업으로 버린다(FactPreservationTest 와 같은 방식).
            alignSegments(sourceUnits, easyUnits)

            val elapsedMillis = measureTimeMillis { alignSegments(sourceUnits, easyUnits) }

            assertThat(elapsedMillis)
                .withFailMessage(
                    "alignSegments 가 %d ms 걸렸다 — O(n log n) 이 아니라 이차 시간으로 퇴화했을 " +
                        "가능성이 있다. 경계는 2,000ms.",
                    elapsedMillis,
                ).isLessThan(2_000)
        }
    }

    private fun SegmentMap.summary(): List<Triple<Int, List<Int>, SegmentConfidence>> =
        units.map { Triple(it.easyUnitIndex, it.sourceUnitIndexes, it.confidence) }
}

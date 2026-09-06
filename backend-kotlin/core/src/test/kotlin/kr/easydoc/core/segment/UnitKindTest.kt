package kr.easydoc.core.segment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** 원본 단위 종류 — 인코딩 왕복·연속 구간·목록 항목 휴리스틱(계획 §1.1·§1.2). */
class UnitKindTest {
    @Test
    @DisplayName("encode 가 종류 순서대로 한 글자 코드를 잇는다")
    fun `인코딩이 순서대로 코드를 잇는다`() {
        val structure =
            SourceStructure(listOf(UnitKind.BODY, UnitKind.TABLE_CELL, UnitKind.TABLE_CELL, UnitKind.LIST_ITEM))

        assertThat(structure.encode()).isEqualTo("BTTL")
    }

    @Test
    @DisplayName("decode(encode(x)) == x — 왕복이다")
    fun `인코딩과 디코딩이 왕복이다`() {
        val original = SourceStructure(listOf(UnitKind.LIST_ITEM, UnitKind.BODY, UnitKind.TABLE_CELL))

        val roundTripped = SourceStructure.decode(original.encode())

        assertThat(roundTripped.kinds).isEqualTo(original.kinds)
    }

    @Test
    @DisplayName("빈 구조도 왕복이다")
    fun `빈 구조도 왕복이다`() {
        assertThat(SourceStructure.decode(SourceStructure(emptyList()).encode()).kinds).isEmpty()
    }

    @Test
    @DisplayName("알 수 없는 코드는 예외다 — 우리 자신이 저장한 값이 깨졌다는 뜻이다")
    fun `알 수 없는 코드를 거절한다`() {
        assertThatThrownBy { SourceStructure.decode("BTX") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("allBody(n) 은 전부 BODY 이고 크기가 n 이다")
    fun `allBody 가 전부 BODY 다`() {
        val structure = SourceStructure.allBody(3)

        assertThat(structure.kinds).containsExactly(UnitKind.BODY, UnitKind.BODY, UnitKind.BODY)
    }

    @Test
    @DisplayName("runs() 가 종류별 연속 구간을 경계 그대로 낸다")
    fun `연속 구간을 낸다`() {
        val structure =
            SourceStructure(
                listOf(
                    UnitKind.BODY,
                    UnitKind.TABLE_CELL,
                    UnitKind.TABLE_CELL,
                    UnitKind.BODY,
                    UnitKind.LIST_ITEM,
                ),
            )

        assertThat(structure.runs())
            .containsExactly(
                UnitRun(UnitKind.BODY, 0, 0),
                UnitRun(UnitKind.TABLE_CELL, 1, 2),
                UnitRun(UnitKind.BODY, 3, 3),
                UnitRun(UnitKind.LIST_ITEM, 4, 4),
            )
    }

    @Test
    @DisplayName("빈 구조의 runs() 는 빈 목록이다")
    fun `빈 구조는 구간이 없다`() {
        assertThat(SourceStructure(emptyList()).runs()).isEmpty()
    }

    @Test
    @DisplayName("toString 이 원본 문구를 찍지 않는다 — 크기만 남긴다")
    fun `toString 이 크기만 남긴다`() {
        val structure = SourceStructure(listOf(UnitKind.BODY, UnitKind.LIST_ITEM))

        assertThat(structure.toString()).isEqualTo("SourceStructure(2개)")
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "1. 첫째|LIST_ITEM",
            "가) 둘째|LIST_ITEM",
            "① 신청 방법|LIST_ITEM",
            "①항목|LIST_ITEM",
            "⑳ 항목|LIST_ITEM",
            "- 항목|LIST_ITEM",
            "- 항목입니다|LIST_ITEM",
            "※ 유의사항|LIST_ITEM",
            "2026년 3월 2일부터|BODY",
            "-5도|BODY",
            "그냥 본문 문장입니다.|BODY",
        ],
    )
    @DisplayName("A4 — 줄머리 마커·글머리 기호로 목록 항목을 가른다 (원형 숫자는 공백 없이도 LIST_ITEM)")
    fun `줄머리로 목록 항목을 가른다`(
        line: String,
        expected: UnitKind,
    ) {
        assertThat(inferUnitKinds(listOf(line))).containsExactly(expected)
    }

    @Test
    @DisplayName("빈 줄은 BODY 다")
    fun `빈 줄은 BODY 다`() {
        assertThat(inferUnitKinds(listOf(""))).containsExactly(UnitKind.BODY)
    }

    @Test
    @DisplayName("여러 줄을 한 번에 순서대로 가른다")
    fun `여러 줄을 순서대로 가른다`() {
        val lines = listOf("안내문 제목", "1. 첫째 항목", "본문 설명입니다.", "- 둘째 항목")

        assertThat(inferUnitKinds(lines))
            .containsExactly(UnitKind.BODY, UnitKind.LIST_ITEM, UnitKind.BODY, UnitKind.LIST_ITEM)
    }
}

package kr.easydoc.core.segment

import kr.easydoc.core.easyread.LIST_MARKER

// P0-4 S8-1 — 표·목록 구조 힌트(계획 §1.1·§1.2,
// docs/plans/2026-09-06-p0-4-structure-hints.md). 원본 단위(=splitUnits 의 한 줄)마다
// 종류 하나를 붙인다. 표의 행·열 좌표, 목록의 깊이, 제목 수준은 싣지 않는다 — 계획 §1.1
// 결정 「셀·항목을 합치거나 나누지 말고 그 안만 바꿔라」를 말할 수 있으면 충분하다.

/** 원본 단위 하나의 종류. [code] 는 저장 인코딩([SourceStructure.encode])에 쓰는 한 글자다. */
enum class UnitKind(val code: Char) {
    BODY('B'),
    TABLE_CELL('T'),
    LIST_ITEM('L'),
    ;

    companion object {
        private val byCode: Map<Char, UnitKind> = entries.associateBy { it.code }

        /** [code] 의 역. 알 수 없는 문자는 우리 자신이 저장한 값이 깨졌다는 뜻이라 fail-fast 로 던진다. */
        fun ofCode(code: Char): UnitKind = byCode[code] ?: throw IllegalArgumentException("알 수 없는 원본 단위 종류 코드: $code")
    }
}

/**
 * 원본 단위마다 하나씩 붙는 종류 목록 — `kinds.size` 가 원본 단위 수(=[splitUnits] 결과 크기)와
 * 같다는 것이 불변식이다. 저장은 `documents.source_unit_kinds`(Flyway V13, nullable) 컬럼이고,
 * `null` 은 이 조각 이전에 만든 문서를 뜻하며 전부 [UnitKind.BODY] 로 읽는다(백필하지 않는다).
 */
class SourceStructure(val kinds: List<UnitKind>) {
    /** 저장용 인코딩 — 줄 수만큼의 한 글자 코드 문자열('B'·'T'·'L'). */
    fun encode(): String = kinds.joinToString("") { it.code.toString() }

    /**
     * 종류가 같은 연속 구간 — 프롬프트와 화면이 다루는 단위다(계획 §1.1 「종류가 연속된 구간이
     * 프롬프트와 화면의 단위다」). 비어 있으면 빈 목록.
     */
    fun runs(): List<UnitRun> {
        if (kinds.isEmpty()) return emptyList()
        val result = mutableListOf<UnitRun>()
        var start = 0
        var current = kinds[0]
        for (index in 1 until kinds.size) {
            if (kinds[index] == current) continue
            result += UnitRun(current, start, index - 1)
            start = index
            current = kinds[index]
        }
        result += UnitRun(current, start, kinds.size - 1)
        return result
    }

    /** 크기만 남긴다 — 담는 것이 구조뿐이라도, 그것을 이루는 원본 문구는 절대 찍지 않는다. */
    override fun toString(): String = "SourceStructure(${kinds.size}개)"

    companion object {
        /** [encode] 의 역. 알 수 없는 문자는 저장된 우리 값이 깨졌다는 뜻이라 fail-fast 로 던진다. */
        fun decode(text: String): SourceStructure = SourceStructure(text.map { UnitKind.ofCode(it) })

        /**
         * [size] 개 전부 [UnitKind.BODY]. 옛 문서(컬럼 `null`)의 대체값이자, 추출 결과의 종류 수가
         * 원본 단위 수와 어긋났을 때(계획 §1.2 「종류 수 = 줄 수가 깨지면 예외가 아니라 전부
         * BODY 로 접는다」) 쓰는 안전한 대체값이다.
         */
        fun allBody(size: Int): SourceStructure = SourceStructure(List(size) { UnitKind.BODY })
    }
}

/** 종류가 같은 연속 구간. [startIndex]·[endIndex] 는 둘 다 원본 단위 색인이고 포함(inclusive)이다. */
data class UnitRun(
    val kind: UnitKind,
    val startIndex: Int,
    val endIndex: Int,
)

/** [UnitKind.LIST_ITEM] 으로 볼 줄머리 글머리 기호(계획 §1.2 표) — `LIST_MARKER` 로 잡지 못하는 것들. */
private val BULLET_CHARS: Set<Char> = setOf('•', '·', '-', '○', '□', '※', '▪', '◦')

/**
 * 원형 숫자 목록 마커 — U+2460(①) ~ U+2473(⑳). **뒤에 공백이 없어도** 그 자체로 항목
 * 표시다 — 한국어 문서에서 "①항목"처럼 공백 없이 바로 붙는 표기가 흔하고, 원형 숫자는
 * 다른 글자와 혼동될 여지가 없는 전용 기호라 [BULLET_CHARS]([followsAsMarkerBoundary])와
 * 달리 경계를 요구하지 않는다(2026-09-06 리뷰 지적 3).
 */
private val CIRCLED_NUMBERS: CharRange = '①'..'⑳'

/**
 * 줄마다 [UnitKind.LIST_ITEM] 인지 [UnitKind.BODY] 인지를 순수하게 가른다(계획 §1.2) — I/O 도,
 * DOM 도 보지 않는다. 줄이 (trim 한 뒤) `LIST_MARKER`(`kr.easydoc.core.easyread.StyleRules`,
 * "1.", "가)", "①)")로 시작하거나, 원형 숫자([CIRCLED_NUMBERS])로 시작하거나(뒤에 공백이
 * 없어도 무조건), 글머리 기호([BULLET_CHARS])로 시작하고 그 뒤가 공백이거나 (공백 없이 바로)
 * 또 다른 마커면 [UnitKind.LIST_ITEM]이다. 그 외는 [UnitKind.BODY] — 표 칸 판정
 * ([UnitKind.TABLE_CELL])은 이 함수의 책임이 아니다(추출기가 구조로 정한다).
 */
fun inferUnitKinds(lines: List<String>): List<UnitKind> =
    lines.map { if (looksLikeListItem(it)) UnitKind.LIST_ITEM else UnitKind.BODY }

private fun looksLikeListItem(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return startsWithMarkerAt(trimmed, 0) || startsWithCircledNumber(trimmed) || isBulletFollowedByBoundary(trimmed)
}

/** [trimmed] 가 원형 숫자로 시작하는가 — 뒤가 공백이 아니어도("①항목") 참이다. */
private fun startsWithCircledNumber(trimmed: String): Boolean = trimmed[0] in CIRCLED_NUMBERS

/** [trimmed] 가 글머리 기호([BULLET_CHARS])로 시작하고 그 뒤가 항목 경계인가. */
private fun isBulletFollowedByBoundary(trimmed: String): Boolean {
    val first = trimmed[0]
    return first in BULLET_CHARS && followsAsMarkerBoundary(trimmed, 1)
}

/** [LIST_MARKER] 가 [text] 의 [from] 위치에서 시작하는 매치를 내는가. */
private fun startsWithMarkerAt(
    text: String,
    from: Int,
): Boolean {
    val match = LIST_MARKER.find(text, from) ?: return false
    return match.range.first == from
}

/**
 * 글머리 기호 하나([BULLET_CHARS]) 뒤가 항목 경계인가 — 줄이 거기서 끝나거나, 공백이
 * 오거나, (공백 없이) 또 다른 마커가 바로 이어지면 참이다. "-5도" 처럼 기호 뒤에 숫자가
 * 바로 붙는 경우는 거짓 — 계획 §1.2 예시. **원형 숫자([CIRCLED_NUMBERS])는 이 검사를 타지
 * 않는다** — [startsWithCircledNumber] 가 경계와 무관하게 무조건 항목으로 본다.
 */
private fun followsAsMarkerBoundary(
    text: String,
    from: Int,
): Boolean = from >= text.length || text[from].isWhitespace() || startsWithMarkerAt(text, from)

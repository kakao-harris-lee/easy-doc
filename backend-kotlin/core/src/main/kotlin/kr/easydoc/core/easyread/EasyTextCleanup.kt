package kr.easydoc.core.easyread

import kr.easydoc.core.text.trimText

// LLM 출력이 남기는 잡음 줄 정리 — `Postprocess.kt` 의 비대칭 원칙을 그대로 따른다.
//
// 지우는 대상을 "표식만 있고 본문이 없는 줄"과 "빈 줄 런"으로 좁힌 이유는 같다: 본문을
// 지우는 쪽이 잡음을 남기는 쪽보다 위험하기 때문이다. 그래서 아래 두 정규식은 **줄 전체**가
// 표식만으로 이뤄졌을 때만 매치한다 — 표식 뒤에 글자 하나라도 있으면 매치하지 않는다.

/**
 * 기호만으로 이뤄진 줄. [DocumentMarkers] 의 `DOCUMENT_MARKER` 와 기호 집합은 겹치지만
 * 용도가 다르다 — 그쪽은 "줄 앞의 표식 하나"를 찾고(뒤에 본문이 있어야 표식으로 센다),
 * 여기는 "줄 전체가 표식뿐인가"를 판정한다(⁠`matches`, 부분 매치가 아니다). 그래서 정규식을
 * 공유하지 않고 이 파일에 따로 둔다. `-`(표의 빈 칸), `*`(글머리 기호)도 포함한다 — 표 셀이
 * 비어 단독 `-` 줄만 남는 경우가 실제로 있고, 본문 하이픈(전화번호·금액)과는 숫자가 섞여
 * 있는지로 구분되므로 혼동되지 않는다.
 *
 * **`※` 는 이 집합에 넣지 않는다.** `DOCUMENT_MARKER` 의 `|※` 갈래에는 "같은 줄에 본문이
 * 따라온다"는 lookahead 가 없어, `※` 는 혼자 있는 줄이어도 무조건 표식으로 센다. 그런데
 * `postprocess` 는 `cleanEasyText` 를 먼저 거친 뒤에야 `hasMarkerChanges(source, draft)` 로
 * 비교되므로(`ConvertDocumentUseCase.finish`), 여기서 단독 `※` 줄을 지우면 원문에 있던
 * 표식이 초안에서만 사라진 것처럼 보여 불필요한 보정 호출(`repairOnce`)을 부른다. 다른
 * 기호들은 전부 "같은 줄에 본문이 있어야" 표식으로 세므로 단독 줄로 남아도 안전하다.
 */
private val SYMBOL_ONLY_LINE = Regex("""^[○●□■◎◇◆▶▷•◦①-⑳*·–—=_-]+$""")

/**
 * 나열자만 있고 뒤에 본문이 없는 줄 — `1.`·`2)`·`(3)`·`가.`·`(나)`. 숫자는 1~3자리로 좁힌다
 * (연도 `2026`처럼 4자리 이상인 숫자는 나열자가 아니다). 한글 나열자 집합은
 * [DocumentMarkers] 의 `DOCUMENT_MARKER` 가 쓰는 것과 같다 — 실제로 문서에서 나열자로
 * 쓰이는 14글자(가~하)뿐이고, 그 밖의 한글 음절은 나열자로 오인하지 않는다.
 */
private val ENUMERATOR_ONLY_LINE =
    Regex(
        """^(?:(?:\d{1,3}|[가나다라마바사아자차카타파하])[.)]|\((?:\d{1,3}|[가나다라마바사아자차카타파하])\))$""",
    )

/** 공백류(스페이스·탭·NBSP 등)만 있거나 아예 빈 줄인가. [trimText] 와 같은 공백 판정을 쓴다. */
private fun isBlankLine(line: String): Boolean = line.trimText().isEmpty()

/** 표식만 남고 본문이 없는 줄인가 — 기호 전용이거나 나열자 전용일 때만 참이다. */
private fun isMarkerOnlyLine(line: String): Boolean {
    val trimmed = line.trimText()
    if (trimmed.isEmpty()) return false
    return SYMBOL_ONLY_LINE.matches(trimmed) || ENUMERATOR_ONLY_LINE.matches(trimmed)
}

/** 빈 줄이 2개 이상 연속되면 정확히 한 줄로 접는다. 1개짜리 빈 줄은 그대로 둔다. */
private fun collapseBlankRuns(lines: List<String>): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!isBlankLine(line)) {
            result.add(line)
            index++
            continue
        }
        var end = index
        while (end < lines.size && isBlankLine(lines[end])) end++
        val runLength = end - index
        result.add(if (runLength >= MIN_RUN_TO_COLLAPSE) "" else line)
        index = end
    }
    return result
}

private const val MIN_RUN_TO_COLLAPSE = 2

/**
 * 표식만 남은 줄을 지우고, 그 제거로 새로 생긴 것까지 포함해 빈 줄 런을 한 줄로 접는다.
 * `Postprocess.kt` 의 비대칭 원칙을 그대로 따른다 — 본문이 조금이라도 섞인 줄은 절대
 * 건드리지 않는다.
 */
fun cleanEasyText(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.split("\n").filterNot(::isMarkerOnlyLine)
    return collapseBlankRuns(lines).joinToString("\n").trimText()
}

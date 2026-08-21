package kr.easydoc.core.text

/**
 * [Char.isWhitespace] 가 놓치는 공백. 눈으로 구분되지 않는 문자라 리터럴로 적지 않고
 * 코드포인트로 적는다 — 소스에 그대로 넣으면 diff 에서 보이지 않고 편집기가 조용히 지워도 모른다.
 */
private val EXTRA_WHITESPACE: Set<Char> =
    setOf(
        '\u00A0', // NO-BREAK SPACE
        '\u2007', // FIGURE SPACE
        '\u202F', // NARROW NO-BREAK SPACE
        '\u0085', // NEXT LINE
    )

/** 텍스트 처리에서 공백으로 볼 문자인가. */
internal fun Char.isTextWhitespace(): Boolean = isWhitespace() || this in EXTRA_WHITESPACE

/** [isTextWhitespace] 기준으로 양끝을 다듬는다. */
internal fun String.trimText(): String = trim { it.isTextWhitespace() }

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

/**
 * 텍스트 처리에서 공백으로 볼 문자인가.
 *
 * Java 의 [Char.isWhitespace] 는 NBSP 류(U+00A0·U+2007·U+202F)와 NEL(U+0085)을 공백으로
 * 보지 않는다. 이 문자들은 hwpx/pdf 추출본과 LLM 출력에 실제로 섞여 들어오고, 다듬지 않으면
 * 두 자리에서 조용히 판정을 뒤집는다.
 *
 * 1. **문장 길이 검사** — 문장 앞뒤에 남아 길이 검사(50자 초과)를 경계에서 뒤집는다.
 * 2. **후처리** — 코드 펜스나 머리말 앞에 NBSP 가 하나 붙으면 입력 시작 앵커가 어긋나
 *    껍데기가 그대로 남는다.
 *
 * 두 곳이 같은 공백 집합을 써야 하므로 여기 한 곳에 둔다. 각자 사본을 들면 한쪽만 늘어난
 * 순간 같은 입력이 경로에 따라 다르게 판정된다.
 */
internal fun Char.isTextWhitespace(): Boolean = isWhitespace() || this in EXTRA_WHITESPACE

/**
 * [isTextWhitespace] 기준으로 양끝을 다듬는다.
 *
 * Python `str.strip()` 과 같은 공백 집합이다 — Python 의 `str.isspace()` 는 NBSP 류를
 * 공백으로 보므로, Kotlin 기본 [String.trim] 을 쓰면 같은 입력에서 결과가 갈린다.
 */
internal fun String.trimText(): String = trim { it.isTextWhitespace() }

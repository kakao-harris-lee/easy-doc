package kr.easydoc.core.segment

// 원문 앵커 규칙 — ER-06 행동 안내 후보(`ActionGuideCandidateValidator`)와 R7 ER-17 그림
// 제안(`IllustrationSuggestionValidator`)이 **같은 규칙**을 쓴다
// (`docs/plans/2026-09-24-r7-er17-suggestion-spec.md` §2 마지막 문단). 두 기능의 앵커
// **타입**은 각자의 것이고(공통화는 그 명세가 범위 밖으로 둔다) 판정과 상한만 공유한다.

/**
 * 한 항목·제안이 달 수 있는 앵커 수 상한. 근거를 여러 줄에 걸쳐 달 수는 있어도, 문서
 * 전체를 근거라고 적는 것은 근거가 아니다.
 */
const val MAX_SOURCE_ANCHORS: Int = 10

/** 앵커 인용 하나의 길이 상한(코드 포인트). 긴 문단 통째 인용을 막는 마지막 벽이다. */
const val MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS: Int = 1_000

/**
 * 앵커 한 건의 **형식**만 본다 — 원문 없이 결정할 수 있는 부분이다. 인용이 비어 있지 않고
 * 상한 안이며, 줄 번호가 하나 이상 있고 중복 없이 오름차순인가.
 *
 * 줄 번호 순서를 규칙으로 두는 이유는 [isSourceAnchorSupported] 의 「연속 구간 인용」이
 * 오름차순을 전제로 이어 붙이기 때문이다 — 순서가 섞이면 같은 앵커가 원문과 다른 문장을
 * 가리킬 수 있다.
 */
fun isSourceAnchorShapeValid(
    sourceUnitIndexes: List<Int>,
    quote: String,
): Boolean =
    quote.isNotBlank() &&
        quote.codePointCount(0, quote.length) <= MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS &&
        sourceUnitIndexes.isNotEmpty() &&
        sourceUnitIndexes == sourceUnitIndexes.distinct().sorted()

/**
 * 원문 앵커 하나가 **실제 원문과 맞는지**만 본다 — 줄 번호가 원문 범위 안이고, 인용이 그
 * 줄에 정말 있는가. 인용이 그 제안·항목의 충분한 근거인지, 의미가 맞는지는 판단하지 않는다.
 * 형식([isSourceAnchorShapeValid])은 원문 없이 먼저 거른다.
 *
 * 인정하는 형태는 둘뿐이다.
 * - **반복 인용**: 지정한 모든 줄에 같은 인용이 들어 있다.
 * - **연속 구간 인용**: 지정한 줄 번호가 빈틈없이 이어지고, 그 줄들을 원문과 같은
 *   순서·같은 줄바꿈으로 이었을 때([joinUnits]) 인용이 그 안에 있다.
 *
 * 줄 번호가 하나도 없으면 근거가 없으므로 `false` 다. 행동 안내 쪽은 구조 검사가 빈 목록을
 * 먼저 거르므로 이 갈래에 닿지 않는다 — 추출로 동작이 달라지지 않는다.
 */
fun isSourceAnchorSupported(
    sourceUnitIndexes: List<Int>,
    quote: String,
    sourceUnits: List<String>,
): Boolean {
    if (sourceUnitIndexes.isEmpty() || sourceUnitIndexes.any { it !in sourceUnits.indices }) return false
    val selectedUnits = sourceUnitIndexes.map(sourceUnits::get)
    val repeatedQuote = selectedUnits.all { quote in it }
    val spanningQuote =
        sourceUnitIndexes.zipWithNext().all { (left, right) -> right == left + 1 } &&
            quote in joinUnits(selectedUnits)
    return repeatedQuote || spanningQuote
}

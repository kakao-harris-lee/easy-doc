package kr.easydoc.core.easyread

import kr.easydoc.core.privacy.MaskCategory

// 사실 보존 기계 검사 — backlog §1.3.
//
// checkStyle 이 문체를 검사하듯, 이 파일은 "숫자·연락처·날짜 등 원문의 사실이 변환문에
// 남아 있는가"를 결정적으로 검사한다. 판정은 보수적이어야 한다 — 오탐(false positive)은
// 유료 보정 호출을 하나 태우고, 모델이 이미 잘 바꿔 쓴 표현을 억지로 "복원"시킬 수도 있다.
// 미탐(false negative)은 오늘의 현상 유지일 뿐이다. 그래서 이 파일은 "확실히 같은 값"만
// 보존으로 인정하고, 애매하면(단위 없는 한 자리 숫자 등) 아예 사실로 세지 않는다.
//
// 이 파일은 추출 파이프라인 오케스트레이션만 맡는다. 배수·수사 낱말 해석은
// `KoreanAmountWords.kt`, 날짜·시각 구성요소 해석은 `TemporalFacts.kt`, 숫자·백분율
// 정체성(단위·소수점 정규화)은 `NumberIdentity.kt` 에 있다(한 파일 함수 수 상한 —
// detekt `TooManyFunctions`).

/** 원문에서 놓치면 안 되는 사실의 종류. */
enum class FactKind {
    NUMBER,
    PHONE,
    TIME,
    DATE,
    AMOUNT,
    PERCENT,
    EMAIL_OR_URL,
}

/** 원문에는 있었는데 변환문에서 사라진 사실 하나. */
data class FactIssue(
    val kind: FactKind,
    val value: String,
) {
    /** **값을 찍지 않는다.** [SentenceIssue] 와 같은 이유(개인정보는 아니지만 사용자 본문 조각이다). */
    override fun toString(): String = "FactIssue(kind=$kind)"
}

/**
 * [source] 에 있던 사실 중 [draft] 에 하나도 남아 있지 않은 것을 찾는다.
 *
 * [source] 에는 **마스킹을 거친 뒤 실제로 LLM 에 나간 텍스트**를 넘긴다 — 마스킹
 * 자리표시자(`[[주민등록번호1]]` 등)는 사실이 아니므로 추출 전에 걷어낸다.
 *
 * 규칙 기반 추출이며 LLM 을 부르지 않는다. 같은 추출 규칙을 [source] 와 [draft] 양쪽에
 * 적용해 비교한다 — 값이 같으면 표기가 달라도(구분자·전각·오전오후·한글 수사 등) 보존으로 본다.
 * [FactKind.DATE] 만 예외로 **부분 비교**다: 한쪽에 연도가 없으면 월·일만 맞으면 된다([sameDate] 참고).
 */
fun findMissingFacts(
    source: String,
    draft: String,
): List<FactIssue> {
    val sourceFacts = extractFacts(source)
    val draftFacts = extractFacts(draft)
    val draftKeys = draftFacts.mapTo(HashSet()) { it.kind to it.compareKey }

    return sourceFacts
        .filterNot { fact ->
            if (fact.kind == FactKind.DATE) {
                draftFacts.any { it.kind == FactKind.DATE && sameDate(fact, it) }
            } else {
                (fact.kind to fact.compareKey) in draftKeys
            }
        }.distinctBy { it.kind to it.compareKey }
        .map { FactIssue(it.kind, it.displayValue) }
}

/**
 * 같은 날짜인가 — [ExtractedFact.compareKey] 는 항상 `MMDD` 라 월·일은 이미 비교된 것이고,
 * 연도 비교는 **비대칭**이다(리뷰 MEDIUM-5). **원문에 연도가 있었으면 변환문도 같은 연도를
 * 적어야 한다** — 원문이 "2026년 9월 4일"인데 변환문이 "9월 4일"로 연도를 빼먹었으면 그
 * 자체가 사실 누락이다. 원문에 애초에 연도가 없었을 때만("9월 4일까지" 같은 표기) 월·일만
 * 맞으면 되고, 그때는 변환문이 연도를 붙이든 안 붙이든 상관없다 — 원문에 없던 정보를
 * 판정 대상으로 삼지 않는다는 원칙과 같다.
 */
private fun sameDate(
    sourceFact: ExtractedFact,
    draftFact: ExtractedFact,
): Boolean {
    if (sourceFact.compareKey != draftFact.compareKey) return false
    return sourceFact.year?.let { it == draftFact.year } ?: true
}

/** 추출된 사실 하나. [compareKey] 가 같으면 같은 사실로 본다(표기가 달라도). [year] 는 [FactKind.DATE] 전용. */
internal data class ExtractedFact(
    val kind: FactKind,
    val compareKey: String,
    val displayValue: String,
    val year: Int? = null,
) {
    /** [displayValue] 는 원문·변환문 조각이다 — [SentenceIssue] 와 같은 이유로 값을 찍지 않는다. */
    override fun toString(): String = "ExtractedFact(kind=$kind)"
}

/** 정규식 매칭 하나 — 아직 비교 키로 정규화되지 않은 원시 결과. 구간은 점유 판정에만 쓰이고 남지 않는다. */
private data class RawMatch(
    val kind: FactKind,
    val text: String,
) {
    /** [text] 는 원문·변환문 조각이다 — [SentenceIssue] 와 같은 이유로 값을 찍지 않는다. */
    override fun toString(): String = "RawMatch(kind=$kind)"
}

// 우선순위 순서. 먼저 처리된 종류가 구간을 점유하면 뒤 종류는 그 구간을 다시 쓰지 못한다
// (`Masking.kt` 의 구간 점유 방식과 같은 발상). 금액·백분율이 숫자보다 먼저인 것은
// "1원"·"3%" 처럼 단위 없는 한 자리 숫자도 그 종류로는 사실로 세기 위해서다 — NUMBER 의
// 한 자리 단위 목록에서 원·%를 빼도 되는 이유가 이것이다(더 구체적인 종류가 먼저 가져간다).
// 한글 수사 패턴(WORD_NUMBER·WORD_AMOUNT, `KoreanAmountWords.kt`)은 Arabic 숫자와 겹치는
// 구간이 없어(다른 문자라) 우선순위가 문제되지 않는다 — 각자의 Arabic 짝 옆에 둔다.
private val PATTERNS: List<Pair<FactKind, Regex>> =
    listOf(
        FactKind.EMAIL_OR_URL to Regex("""[\w.+-]+@[\w-]+\.[\w.-]+|https?://\S+|www\.\S+"""),
        FactKind.PHONE to Regex("""(?<!\d)(?:0\d{1,2}-\d{3,4}-\d{4}|1\d{3}-\d{4})(?!\d)"""),
        FactKind.TIME to Regex("""(?:오전|오후)?\s*\d{1,2}시(?:\s*\d{1,2}분)?|\d{1,2}:\d{2}"""),
        FactKind.DATE to Regex("""\d{4}[.\-]\d{1,2}[.\-]\d{1,2}|(?:\d{4}년\s*)?\d{1,2}월\s*\d{1,2}일"""),
        // 배수 단위(만·억·천·백·십)가 하나도 없는 순수 Arabic 숫자 + 원. 배수 단위가 있는
        // 경우는 전부 WORD_AMOUNT(합성 파서, KoreanAmountWords.kt)가 맡는다 — 부분 매치
        // 사고(리뷰 HIGH-2, "5천만원"이 "만원"=10,000 으로 잘못 잡히던 문제)를 막으려면
        // 배수 단위가 있는 구간은 그 파서가 **통째로** 소비해야 한다.
        FactKind.AMOUNT to Regex("""(?:\d{1,3}(?:,\d{3})+|\d+)\s*원"""),
        FactKind.AMOUNT to WORD_AMOUNT,
        FactKind.PERCENT to Regex("""\d+(?:\.\d+)?\s*%"""),
        // 2자리 이상 숫자(구분자 포함), 또는 단위가 붙은 한 자리 숫자. 원·%는 위에서 이미
        // 더 구체적인 종류로 가져가므로 이 목록에 넣지 않는다. 단위 문자를 **소비한다**
        // (전에는 lookahead 로 흘려보내 raw.text 에 단위가 안 남았다 — 리뷰 HIGH-1 재현
        // 사례: "3명"과 "3층"이 둘 다 raw.text="3"이 되어 같은 사실로 오판됐다).
        FactKind.NUMBER to Regex("""\d{1,3}(?:,\d{3})+|\d{2,}|\d(?:명|개|일|시|분|세|살|회|건|층|호|번)"""),
        FactKind.NUMBER to WORD_NUMBER,
    )

/** 마스킹 자리표시자 모양(`[[주민등록번호1]]` 등) — [MaskCategory] 라벨에서 만든다. */
private val PLACEHOLDER: Regex =
    run {
        val categories = MaskCategory.entries.joinToString("|") { Regex.escape(it.label) }
        Regex("""\[\[(?:$categories)[0-9]+]]""")
    }

/** 전각 숫자(０-９) → 반각. 길이를 바꾸지 않아 뒤 정규식의 오프셋에 영향이 없다. */
private fun normalizeFullWidthDigits(text: String): String =
    buildString(text.length) {
        for (ch in text) {
            append(if (ch in '０'..'９') '0' + (ch - '０') else ch)
        }
    }

/** 자리표시자를 같은 길이의 공백으로 지운다 — 사실 추출 대상에서 빼되 오프셋은 유지한다. */
private fun stripPlaceholders(text: String): String = PLACEHOLDER.replace(text) { " ".repeat(it.value.length) }

internal fun digitsOnly(text: String): String = text.filter { it.isDigit() }

/** [range] 가 비어 있지 않고 아직 아무도 점유하지 않았으면 점유하고 `true` 를 돌려준다. */
private fun claim(
    claimed: BooleanArray,
    range: IntRange,
): Boolean {
    if (range.isEmpty() || range.any { claimed[it] }) return false
    for (index in range) claimed[index] = true
    return true
}

/** 우선순위 순서로 구간을 점유하며 겹치지 않는 매칭만 남긴다. */
private fun extractRawMatches(text: String): List<RawMatch> {
    val claimed = BooleanArray(text.length)
    val results = mutableListOf<RawMatch>()
    for ((kind, regex) in PATTERNS) {
        for (match in regex.findAll(text)) {
            if (claim(claimed, match.range)) {
                results += RawMatch(kind, match.value)
            }
        }
    }
    return results
}

private fun compareKeyOf(raw: RawMatch): String =
    when (raw.kind) {
        FactKind.AMOUNT -> amountValue(raw.text).toString()
        FactKind.EMAIL_OR_URL -> raw.text.trim().lowercase()
        FactKind.TIME -> timeMinutes(raw.text)?.toString().orEmpty()
        FactKind.DATE -> dateCompareKey(raw.text).orEmpty()
        FactKind.NUMBER -> numberCompareKey(raw.text)
        FactKind.PHONE -> digitsOnly(raw.text)
        FactKind.PERCENT -> percentCompareKey(raw.text)
    }

private fun extractFacts(text: String): List<ExtractedFact> {
    val normalized = normalizeFullWidthDigits(stripPlaceholders(text))
    return extractRawMatches(normalized)
        .map { raw ->
            val year = if (raw.kind == FactKind.DATE) dateComponents(raw.text)?.first else null
            ExtractedFact(raw.kind, compareKeyOf(raw), raw.text.trim(), year)
        }.filter { it.compareKey.isNotEmpty() }
}

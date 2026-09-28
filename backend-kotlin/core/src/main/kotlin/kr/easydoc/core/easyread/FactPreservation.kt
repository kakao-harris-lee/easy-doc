package kr.easydoc.core.easyread

// 사실 보존 기계 검사.
//
// 원문의 숫자·연락처·날짜 등이 변환문에 남았는지 결정적으로 확인한다. 확실히 같은 값만
// 보존으로 인정해, 애매한 매치는 오탐으로 보정하지 않는다. 추출 파이프라인만 이 파일에
// 두고, 금액·수사·날짜·숫자 정규화는 각 전용 파일에 둔다.

/** 원문에서 놓치면 안 되는 사실의 종류. */
enum class FactKind {
    DOCUMENT_NAME,
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
 * 원문 사실 총수와 그중 [draft] 에서 사라진 것을 함께 담는다.
 *
 * [sourceFactCount] 와 [missing] 은 **같은 중복 제거 기준**(`kind`·`compareKey`)으로 낸
 * 값이지만, 적용 순서는 다르다. [sourceFactCount] 는 원문 사실을 먼저 중복 제거하고,
 * [missing] 은 중복 제거 전 각 인스턴스를 판정한 뒤 결과를 중복 제거한다. 이 순서를
 * 지켜야 날짜의 연도 유무를 포함한 비대칭 비교가 사라지지 않는다.
 *
 * [sourceFactCount] 는 원문 전체에서 규칙 기반으로 추출한 사실 수이며, 관측용으로만 쓰고
 * 보존 판정에는 사용하지 않는다.
 */
data class FactCoverage(
    /** 원문에서 뽑은 사실의 수 — 중복 제거 후([factCoverage] KDoc). */
    val sourceFactCount: Int,
    /** [sourceFactCount] 중 [draft] 에 하나도 남지 않은 것. */
    val missing: List<FactIssue>,
) {
    /** [sourceFactCount] 에서 [missing] 을 뺀, 변환문에 남은 사실 수. */
    val keptCount: Int get() = sourceFactCount - missing.size

    /** 보존율 = [keptCount] / [sourceFactCount]. 원문에 사실이 하나도 없으면(`0`) `null` — 0%로 채우지 않는다. */
    val ratio: Double? get() = sourceFactCount.takeIf { it > 0 }?.let { keptCount.toDouble() / it }
}

/**
 * [source] 사실 보존 현황을 [FactCoverage] 로 낸다.
 *
 * [FactCoverage.sourceFactCount] 는 `kind`·`compareKey` 로 중복 제거한 수다. [missing] 은
 * 중복 제거 전에 `filterNot` 을 적용해야 [FactKind.DATE] 의 연도 비교를 각 원본 인스턴스에
 * 적용할 수 있다. 결과만 `distinctBy` 로 정리한다. [findMissingFacts] 는 이 함수에 위임한다.
 */
fun factCoverage(
    source: String,
    draft: String,
): FactCoverage {
    val sourceFactsRaw = extractFacts(source)
    val sourceFactCount = sourceFactsRaw.distinctBy { it.kind to it.compareKey }.size
    val draftFacts = extractFacts(draft)
    val draftKeys = draftFacts.mapTo(HashSet()) { it.kind to it.compareKey }
    val normalizedDraft = normalizeFullWidthDigits(draft).filterNot(Char::isWhitespace)

    val missing =
        sourceFactsRaw
            .filterNot { fact ->
                if (fact.kind == FactKind.DOCUMENT_NAME) {
                    fact.compareKey in normalizedDraft
                } else if (fact.kind == FactKind.DATE) {
                    draftFacts.any { it.kind == FactKind.DATE && sameDate(fact, it) }
                } else {
                    (fact.kind to fact.compareKey) in draftKeys || untypedAmountKept(fact, draftKeys)
                }
            }.distinctBy { it.kind to it.compareKey }
            .map { FactIssue(it.kind, it.displayValue) }

    return FactCoverage(sourceFactCount, missing)
}

/**
 * [source] 에 있던 사실 중 [draft] 에 하나도 남아 있지 않은 것을 찾는다.
 *
 * [source] 에는 **실제로 LLM 에 나간 문서 원문**을 넘긴다.
 *
 * 규칙 기반 추출이며 LLM 을 부르지 않는다. 같은 추출 규칙을 [source] 와 [draft] 양쪽에
 * 적용해 비교한다 — 값이 같으면 표기가 달라도(구분자·전각·오전오후·한글 수사 등) 보존으로 본다.
 * [FactKind.DATE] 만 예외로 **부분 비교**다: 한쪽에 연도가 없으면 월·일만 맞으면 된다([sameDate] 참고).
 *
 * [factCoverage] 로 위임한다 — 비교 규칙은 한 곳(그 함수)에만 있다.
 */
fun findMissingFacts(
    source: String,
    draft: String,
): List<FactIssue> = factCoverage(source, draft).missing

/**
 * 같은 날짜인가 — [ExtractedFact.compareKey] 는 항상 `MMDD` 라 월·일은 이미 비교된 것이고,
 * 연도 비교는 **비대칭**이다. **원문에 연도가 있었으면 변환문도 같은 연도를
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
    val untypedGroupedNumber: Boolean = false,
) {
    /** [displayValue] 는 원문·변환문 조각이다 — [SentenceIssue] 와 같은 이유로 값을 찍지 않는다. */
    override fun toString(): String = "ExtractedFact(kind=$kind)"
}

/** 정규식 매칭 하나 — 아직 비교 키로 정규화되지 않은 원시 결과. 구간은 점유 판정에만 쓰이고 남지 않는다. */
private data class RawMatch(
    val kind: FactKind,
    val text: String,
    val untypedGroupedNumber: Boolean = false,
    val comparisonText: String = text,
) {
    /** [text] 는 원문·변환문 조각이다 — [SentenceIssue] 와 같은 이유로 값을 찍지 않는다. */
    override fun toString(): String = "RawMatch(kind=$kind)"
}

// 우선순위 순서. 먼저 처리된 종류가 구간을 점유하면 뒤 종류는 그 구간을 다시 쓰지 못한다.
// 금액·백분율이 숫자보다 먼저인 것은
// "1원"·"3%" 처럼 단위 없는 한 자리 숫자도 그 종류로는 사실로 세기 위해서다 — NUMBER 의
// 한 자리 단위 목록에서 원·%를 빼도 되는 이유가 이것이다(더 구체적인 종류가 먼저 가져간다).
// 한글 수사 패턴(WORD_NUMBER·WORD_AMOUNT, `KoreanAmountWords.kt`)은 Arabic 숫자와 겹치는
// 구간이 없어(다른 문자라) 우선순위가 문제되지 않는다 — 각자의 Arabic 짝 옆에 둔다.
//
// 끝까지 소비하는 possessive 수량자에는 lookbehind 를 함께 써서 숫자열마다 한 번만
// 시도한다. 그렇지 않으면 `findAll` 이 각 시작 위치에서 다시 시도해 O(n²) 이 될 수 있다.
// 공고의 양쪽 작은따옴표와 수집 원문에 HTML 엔티티로 남은 표지도 같은 연도 표지다.
private const val YEAR_APOSTROPHE = "(?:['‘’]|&rsquo;)"

/**
 * 공식 파일명은 줄 전체에 있거나 인용부호 안에 있다. 인용부호 안의 형태도 문서명 사실로
 * 인식해야 인라인 안내문과 줄 단위 원문 인용이 같은 사실로 비교된다. 인용부호 밖의 일반
 * 숫자는 이 패턴에 들어오지 않으므로 파일명 연도가 무관한 숫자를 대신하지 않는다.
 */
private val DOCUMENT_NAME_PATTERN =
    Regex(
        """(?m)^[\h*•-]*+(?:\[[^\r\n\]]{1,40}\]\h*+)?[\p{L}0-9(][^\r\n<>:/]{0,160}\.(?:hwpx|hwp|pdf|docx|xlsx)\h*+$""" +
            """|(?<=[\"'‘“「『])(?:\[[^\r\n\]]{1,40}\]\h*+)?""" +
            """[\p{L}0-9(][^\r\n<>:/\[\]'‘’“”「」『』]{0,160}""" +
            """\.(?:hwpx|hwp|pdf|docx|xlsx)(?=[\"'’”」』])""" +
            """|(?<![\p{L}0-9])\[[^\r\n\]]{1,40}\]\h*+""" +
            """[\p{L}0-9(][^\r\n<>:/\[\]'‘’“”「」『』]{0,160}""" +
            """\.(?:hwpx|hwp|pdf|docx|xlsx)(?=$|[\s,.;:!?\"'’”」』])""",
    )

private val PATTERNS: List<Pair<FactKind, Regex>> =
    listOf(
        FactKind.DOCUMENT_NAME to DOCUMENT_NAME_PATTERN,
        FactKind.EMAIL_OR_URL to
            Regex("""(?<![\w.+-])[\w.+-]++@[\w-]++\.[\w.-]++|https?://\S++|www\.\S++"""),
        FactKind.PHONE to Regex("""(?<!\d)(?:0\d{1,2}+-\d{3,4}+-\d{4}+|1\d{3}+-\d{4}+)(?!\d)"""),
        FactKind.TIME to Regex("""(?:오전|오후)?\s*+\d{1,2}+시(?:\s*+\d{1,2}+분)?|\d{1,2}+:\d{2}+"""),
        // 선택적 연도 그룹은 4자리와 아포스트로피 축약 두 자리 연도를 모두 받는다.
        // 날짜가 먼저 구간을 점유해 뒤의 연도 전용 패턴과 중복되지 않게 한다.
        FactKind.DATE to
            Regex(
                """\d{4}+[.\-]\h*+\d{1,2}+[.\-]\h*+\d{1,2}+|""" +
                    """(?:(?:\d{4}+|$YEAR_APOSTROPHE\d{2}+)년\s*+)?\d{1,2}+월\s*+\d{1,2}+일""",
            ),
        // 아포스트로피가 있는 축약 연도만 날짜로 인식해 일반 두 자리 숫자와 구분한다.
        FactKind.DATE to Regex("""$YEAR_APOSTROPHE\d{2}+[.\-]\d{1,2}+[.\-]\d{1,2}+"""),
        // 일이 없는 축약 연도는 구분자만 lookahead 로 확인해 뒤의 월 숫자를 별도 사실로 남긴다.
        FactKind.NUMBER to Regex("""$YEAR_APOSTROPHE\d{2}+(?=[.\-])"""),
        // 날짜가 아닌 축약 연도도 NUMBER 로 잡아, 변환문이 4자리로 펼친 값과 비교한다.
        FactKind.NUMBER to Regex("""$YEAR_APOSTROPHE\d{2}+년"""),
        // 배수 단위가 있는 금액은 WORD_AMOUNT 가 전체 구간을 소비해 부분 매치를 막는다.
        FactKind.AMOUNT to Regex("""\d{1,3}+(?:,\d{3}+)++\s*+원|(?<!\d)\d++\s*+원"""),
        FactKind.AMOUNT to WORD_AMOUNT,
        FactKind.AMOUNT to BARE_WORD_AMOUNT,
        FactKind.PERCENT to Regex("""(?<!\d)\d++(?:\.\d++)?\s*+(?:%|퍼센트|프로)"""),
        // '100분의 50'과 '50%'는 같은 비율이다. 분모가 100인 경우만 통째로 점유한다.
        FactKind.PERCENT to Regex("""(?<![\d.])100\h*+분의\h*+\d++(?:\.\d++)?"""),
        // 2~4자리 숫자와 단위가 붙은 한 자리 숫자를 잡는다. 원·%는 앞의 더 구체적인
        // 패턴이 먼저 소비한다. 단위 대체는 긴 단위부터 시도해 부분 매치를 막는다.
        // 단위나 구분자가 없는 5자리 이상 숫자열은 오탐을 줄이기 위해 제외한다. 따라서
        // 계좌번호·문서번호 같은 긴 맨 숫자열은 미탐으로 남을 수 있다. 단위가 뒤따르는
        // 경우만 lookahead 로 인정하고 소비하지 않아 비교 키를 유지한다. 양쪽 경계는
        // 숫자열을 한 번만 시도하게 한다.
        FactKind.NUMBER to
            Regex(
                """\d{1,3}+(?:,\d{3}+)++|(?<!\d)\d{2,4}+(?!\d)|""" +
                    """(?<!\d)\d{5,}+(?=\s*+(?:$ARABIC_UNIT_ALTERNATION))|\d(?:$ARABIC_UNIT_ALTERNATION)""",
            ),
        FactKind.NUMBER to WORD_NUMBER,
        FactKind.NUMBER to COMPACT_WORD_NUMBER,
    )

/**
 * 전각 숫자(０-９) → 반각. 길이를 바꾸지 않아 뒤 정규식의 오프셋에 영향이 없다.
 * [kr.easydoc.core.quality.GoldenEvaluation] 의 표기 별칭 정규화도 이 함수를 재사용한다.
 */
internal fun normalizeFullWidthDigits(text: String): String =
    buildString(text.length) {
        for (ch in text) {
            append(if (ch in '０'..'９') '0' + (ch - '０') else ch)
        }
    }

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
    var rangesAdded = false
    for ((kind, regex) in PATTERNS) {
        if (kind == FactKind.DATE && !rangesAdded) {
            results += claimedDateRangeMatches(text, claimed)
            rangesAdded = true
        }
        for (match in regex.findAll(text)) {
            val value = if (kind == FactKind.EMAIL_OR_URL) trimReferenceSuffix(match.value) else match.value
            val range = match.range.first until (match.range.first + value.length)
            if (claim(claimed, range)) {
                // 콤마 NUMBER 패턴은 뒤 단위를 소비하지 않는다. '125,300명'에 돈 단위를
                // 붙여도 된다고 오인하지 않도록, 같은 줄의 뒤 문자가 글자인지 따로 확인한다.
                val untyped =
                    kind == FactKind.NUMBER && ',' in match.value && !followedByWord(text, match.range.last + 1)
                results += RawMatch(kind, value, untyped)
            }
        }
    }
    return results
}

private fun claimedDateRangeMatches(
    text: String,
    claimed: BooleanArray,
): List<RawMatch> =
    dateRangeParts(text)
        .filter { claim(claimed, it.range) }
        .map { RawMatch(FactKind.DATE, text.substring(it.range), comparisonText = it.canonical) }

private fun compareKeyOf(raw: RawMatch): String =
    when (raw.kind) {
        FactKind.DOCUMENT_NAME -> {
            raw.text
                .trim()
                .trimStart('-', '*', '•')
                .filterNot(Char::isWhitespace)
        }

        FactKind.AMOUNT -> {
            amountValue(raw.text).toString()
        }

        FactKind.EMAIL_OR_URL -> {
            raw.text.trim().lowercase()
        }

        FactKind.TIME -> {
            timeMinutes(raw.text)?.toString().orEmpty()
        }

        FactKind.DATE -> {
            dateCompareKey(expandAbbreviatedYear(raw.comparisonText)).orEmpty()
        }

        FactKind.NUMBER -> {
            numberCompareKey(expandAbbreviatedYear(raw.text))
        }

        FactKind.PHONE -> {
            digitsOnly(raw.text)
        }

        FactKind.PERCENT -> {
            percentCompareKey(raw.text)
        }
    }

/** [expandAbbreviatedYear] 가 인정하는 축약 연도의 상한 — 그 함수 KDoc 참고. */
private const val ABBREVIATED_YEAR_MAX = 49

/** [expandAbbreviatedYear] 가 확정하는 세기 — 이 서비스가 다루는 공공 안내문은 2000년대만 다룬다. */
private const val ABBREVIATED_YEAR_CENTURY = 2000

/** 아포스트로피(ASCII `'` 또는 U+2019 `’`) + 두 자리 숫자로 시작하는 접두부. */
private val ABBREVIATED_YEAR_PREFIX = Regex("""^$YEAR_APOSTROPHE(\d{2}+)""")

/**
 * 공문 관행 연도 축약을 20NN 으로 편다 — [matchText] 가 아포스트로피(ASCII `'` 또는 U+2019
 * `’`) + 두 자리 숫자로 시작하면 그 자리를 4자리 연도로 바꾸고 나머지는 그대로 이어 돌려준다
 * (예: `"’26.9.1"` → `"2026.9.1"`, `"’26년"` → `"2026년"`). 그렇지 않으면(아포스트로피가 없거나
 * 경계를 넘으면) [matchText] 를 그대로 돌려준다 — 무해한 항등 변환이라 [compareKeyOf] ·
 * [extractFacts] 가 모든 NUMBER·DATE 원시 매치에 조건 없이 걸어도 안전하다.
 *
 * **세기는 항상 20NN 으로 고정한다.** 이 제품이 다루는 공공 안내문의 축약 연도는 전부
 * 2000년대이므로 19NN 판별 로직을 따로 두지 않는다.
 *
 * **경계를 00~[ABBREVIATED_YEAR_MAX](49) 로 좁힌다.** 50~99 는 관례상(Y2K 이후 널리 쓰는
 * windowing 규칙과 같은 방향) 1950~1999 로도 읽힐 수 있어 애매하다 — 애매한 값을 20NN 으로
 * 확정하면 옛 연도가 엉뚱한 미래 연도로 오판될 위험이 오탐 하나를 고치는 이득보다 크다. 그
 * 경계 밖은 손대지 않고 그대로 둔다 — 결과는 기존 동작(아포스트로피 없이 두 자리 숫자만
 * 남는 것)과 같다.
 *
 * [matchText] 는 항상 [RawMatch.text](원시 매치)에만 적용한다 — [ExtractedFact.displayValue]
 * 는 이 확장 전 원문 그대로 남는다.
 */
private fun expandAbbreviatedYear(matchText: String): String {
    val prefix = ABBREVIATED_YEAR_PREFIX.find(matchText)
    val twoDigitYear = prefix?.groupValues?.get(1)?.toInt()
    return if (prefix == null || twoDigitYear == null || twoDigitYear > ABBREVIATED_YEAR_MAX) {
        matchText
    } else {
        val fourDigitYear = ABBREVIATED_YEAR_CENTURY + twoDigitYear
        fourDigitYear.toString() + matchText.substring(prefix.range.last + 1)
    }
}

// 단위 정렬도 같은 추출 규칙을 앵커로 재사용한다. 공개 API가 아닌 core 내부 공유다.

internal fun extractFacts(text: String): List<ExtractedFact> {
    val normalized = normalizeFullWidthDigits(text)
    return extractRawMatches(normalized)
        .map { raw ->
            val year =
                if (raw.kind == FactKind.DATE) {
                    dateComponents(expandAbbreviatedYear(raw.comparisonText))?.first
                } else {
                    null
                }
            ExtractedFact(raw.kind, compareKeyOf(raw), raw.text.trim(), year, raw.untypedGroupedNumber)
        }.filter { it.compareKey.isNotEmpty() }
}

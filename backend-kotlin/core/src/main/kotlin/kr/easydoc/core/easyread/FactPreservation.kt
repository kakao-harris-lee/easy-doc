package kr.easydoc.core.easyread

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
 * 원문 사실 총수와 그중 [draft] 에서 사라진 것을 함께 담는다.
 *
 * [sourceFactCount] 와 [missing] 은 **같은 중복 제거 기준**(`kind`·`compareKey`)으로 낸
 * 값이지만, 적용 순서는 다르다 — [sourceFactCount] 는 원문 사실을 먼저 중복 제거한 수이고,
 * [missing] 은 중복 제거 전 원본 인스턴스 각각을 먼저 판정한 뒤에야 중복 제거로 마무리한다
 * ([factCoverage] KDoc, 리뷰 blocker 2026-09-09 — 순서를 뒤바꾸면 DATE 의 연도 비대칭
 * 비교가 조용히 사라진다). `missing` 의 모든 키가 [sourceFactCount] 를 낸 집합의 키
 * 부분집합이므로 `missing.size <= sourceFactCount` 는 여전히 성립하고 [ratio] 가 1.0 을
 * 넘지 않는다.
 *
 * 관측 슬라이스 S1(`docs/plans/2026-09-09-content-loss.md`)이 만든 값이다. 지금까지
 * 레인은 문서당 3~6개인 큐레이션 `required_facts` 만으로 사실 보존을 쟀는데, 그 목록 밖의
 * 수치(예: 요율표의 개월·퍼센트 나열)가 통째로 사라져도 게이트가 못 잡는 간극이 있었다
 * (`022` 문서, 6차 실측). 이 값은 원문 전체의 규칙 기반 추출 사실을 분모로 써서 그 간극을
 * 관측한다 — 판정에는 쓰지 않는다.
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
 * [FactCoverage.sourceFactCount] 는 [source] 에서 뽑은 사실을 `kind`·`compareKey` 로 중복
 * 제거한([distinctBy]) 수다. [FactCoverage.missing] 은 **중복 제거 전** 전체 목록에
 * `filterNot` 을 먼저 적용한 뒤에야 `distinctBy` 로 마무리한다 — 이 순서가 뒤바뀌면 안 된다
 * (리뷰 blocker, 2026-09-09 재현). 이유는 [FactKind.DATE] 의 비대칭 비교([sameDate])에
 * 있다: `compareKey` 는 항상 `MMDD` 뿐이고 [ExtractedFact.year] 는 `distinctBy` 키에 없다.
 * 그래서 같은 월-일이 연도 유무를 달리해 원문에 두 번 나오면(공문에 흔하다) 먼저
 * `distinctBy` 를 적용해 하나만 남길 경우 **어느 표기가 대표로 남는지에 따라 결과가
 * 갈린다** — 연도 없는 쪽이 대표로 뽑히면 연도 있는 쪽의 `sameDate()` 검사(원문에 연도가
 * 있었으면 변환문도 같은 연도를 적어야 한다)가 통째로 사라져, 변환문이 연도를 빼먹어도
 * 못 잡는다(항상 적게 잡는 방향으로만 새는 미탐). 그래서 `filterNot` 은 **중복 제거 전
 * 원본 인스턴스 각각**에 적용해 개별 판정을 보존하고, 결과를 표시할 때만(`missing` 안에서도
 * 같은 사실이 두 번 보고되지 않도록) `distinctBy` 로 마무리한다. `missing` 의 모든 키가
 * `sourceFacts`(중복 제거된 분모) 의 키 부분집합이므로 `missing.size <= sourceFactCount`
 * 는 여전히 성립한다. [findMissingFacts] 는 이 함수의 [FactCoverage.missing] 만 돌려주는
 * 얇은 위임이다 — 비교 규칙(사실 정체성 판정, [FactKind.DATE] 부분 비교)이 이 함수 한
 * 곳에만 있다.
 */
fun factCoverage(
    source: String,
    draft: String,
): FactCoverage {
    val sourceFactsRaw = extractFacts(source)
    val sourceFactCount = sourceFactsRaw.distinctBy { it.kind to it.compareKey }.size
    val draftFacts = extractFacts(draft)
    val draftKeys = draftFacts.mapTo(HashSet()) { it.kind to it.compareKey }

    val missing =
        sourceFactsRaw
            .filterNot { fact ->
                if (fact.kind == FactKind.DATE) {
                    draftFacts.any { it.kind == FactKind.DATE && sameDate(fact, it) }
                } else {
                    (fact.kind to fact.compareKey) in draftKeys
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

// 우선순위 순서. 먼저 처리된 종류가 구간을 점유하면 뒤 종류는 그 구간을 다시 쓰지 못한다.
// 금액·백분율이 숫자보다 먼저인 것은
// "1원"·"3%" 처럼 단위 없는 한 자리 숫자도 그 종류로는 사실로 세기 위해서다 — NUMBER 의
// 한 자리 단위 목록에서 원·%를 빼도 되는 이유가 이것이다(더 구체적인 종류가 먼저 가져간다).
// 한글 수사 패턴(WORD_NUMBER·WORD_AMOUNT, `KoreanAmountWords.kt`)은 Arabic 숫자와 겹치는
// 구간이 없어(다른 문자라) 우선순위가 문제되지 않는다 — 각자의 Arabic 짝 옆에 둔다.
//
// **모든 `+`·`{n,}`·`{n,m}` 수량자를 possessive 로 적었다**(리뷰 재검토 HIGH-3, 1차 재현:
// 20,000 자리 숫자열에서 21.6초). possessive 는 한 조각 **안에서** 되무르는 것만 막는다 —
// 그 조각을 담은 바깥 `(?:...)?`(선택)나 상위 대체가 이 조각 전체를 통째로 "없음"으로
// 다시 시도하면, `findAll` 이 매 시작 위치마다 그 통째 재시도를 반복해 여전히 O(n²) 가
// 될 수 있다(2차 재현: possessive 만으로는 6.6초·1초). 그래서 **뒤에 아무것도 안 이어지면
// 실패하는 무한정 수량자**(`\d++` 같은 것)에는 `(?<!\d)` 류 lookbehind 를 앞에 더해
// "숫자·콤마가 아닌 자리에서만 시도"하게 만든다 — 그러면 한 숫자열 뭉치당 시도가 정확히
// 한 번이라 전체가 O(n) 이다. 정확히 고정 자릿수(`{4}`·`{3,4}`·`{2}` 등)는 되무를 자리가
// 없어 애초에 대상이 아니다.
private val PATTERNS: List<Pair<FactKind, Regex>> =
    listOf(
        FactKind.EMAIL_OR_URL to
            Regex("""(?<![\w.+-])[\w.+-]++@[\w-]++\.[\w.-]++|https?://\S++|www\.\S++"""),
        FactKind.PHONE to Regex("""(?<!\d)(?:0\d{1,2}+-\d{3,4}+-\d{4}+|1\d{3}+-\d{4}+)(?!\d)"""),
        FactKind.TIME to Regex("""(?:오전|오후)?\s*+\d{1,2}+시(?:\s*+\d{1,2}+분)?|\d{1,2}+:\d{2}+"""),
        FactKind.DATE to Regex("""\d{4}+[.\-]\d{1,2}+[.\-]\d{1,2}+|(?:\d{4}+년\s*+)?\d{1,2}+월\s*+\d{1,2}+일"""),
        // 배수 단위(만·억·천·백·십)가 하나도 없는 순수 Arabic 숫자 + 원. 배수 단위가 있는
        // 경우는 전부 WORD_AMOUNT(합성 파서, KoreanAmountWords.kt)가 맡는다 — 부분 매치
        // 사고(리뷰 HIGH-2, "5천만원"이 "만원"=10,000 으로 잘못 잡히던 문제)를 막으려면
        // 배수 단위가 있는 구간은 그 파서가 **통째로** 소비해야 한다. `\d{1,3}+(?:,\d{3}+)++`
        // 는 콤마가 없으면 즉시 실패해(고정 최대 3자리 뒤 콤마 검사) 원래도 O(n) 이었다 —
        // lookbehind 가 필요한 것은 뒤가 안 이어져도 끝까지 삼키는 `\d++` 뿐이다.
        FactKind.AMOUNT to Regex("""\d{1,3}+(?:,\d{3}+)++\s*+원|(?<!\d)\d++\s*+원"""),
        FactKind.AMOUNT to WORD_AMOUNT,
        FactKind.PERCENT to Regex("""(?<!\d)\d++(?:\.\d++)?\s*+%"""),
        // 2자리 이상 숫자(구분자 포함), 또는 단위가 붙은 한 자리 숫자. 원·%는 위에서 이미
        // 더 구체적인 종류로 가져가므로 이 목록에 넣지 않는다. 단위 문자를 **소비한다**
        // (전에는 lookahead 로 흘려보내 raw.text 에 단위가 안 남았다 — 리뷰 HIGH-1 재현
        // 사례: "3명"과 "3층"이 둘 다 raw.text="3"이 되어 같은 사실로 오판됐다). 단위
        // 대체는 [ARABIC_UNIT_ALTERNATION](`KoreanAmountWords.kt`) 을 그대로 쓴다 — 긴
        // 단위(개월·시간·분기)가 짧은 단위(개·시·분)보다 먼저 와야 "3개월"이 "3개"로
        // 잘못 잘리지 않는다(리뷰 재검토 HIGH-1 재현 사례). `\d{2,}+` 는 뒤가 안 이어져도
        // 최소 2자리만 확인되면 바로 성패가 갈려(최솟값 미달 시 그 자리에서 실패) lookbehind
        // 가 필요 없다 — `\d++` 처럼 "끝까지 삼킨 뒤에" 실패하는 모양이 아니다.
        FactKind.NUMBER to Regex("""\d{1,3}+(?:,\d{3}+)++|\d{2,}+|\d(?:$ARABIC_UNIT_ALTERNATION)"""),
        FactKind.NUMBER to WORD_NUMBER,
    )

/** 전각 숫자(０-９) → 반각. 길이를 바꾸지 않아 뒤 정규식의 오프셋에 영향이 없다. */
private fun normalizeFullWidthDigits(text: String): String =
    buildString(text.length) {
        for (ch in text) {
            append(if (ch in '０'..'９') '0' + (ch - '０') else ch)
        }
    }

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

// P0-4 단위 정렬(`core/segment/SegmentAlignment.kt`, 2026-09-05)이 같은 추출 규칙을 앵커로
// 재사용한다 — 공개 API 확대가 아니라 같은 core 모듈 안에서만 보이는 `internal` 좁히기다.

internal fun extractFacts(text: String): List<ExtractedFact> {
    val normalized = normalizeFullWidthDigits(text)
    return extractRawMatches(normalized)
        .map { raw ->
            val year = if (raw.kind == FactKind.DATE) dateComponents(raw.text)?.first else null
            ExtractedFact(raw.kind, compareKeyOf(raw), raw.text.trim(), year)
        }.filter { it.compareKey.isNotEmpty() }
}

package kr.easydoc.core.privacy

import kr.easydoc.core.security.Secret
import kr.easydoc.core.text.unicodeRegex

/** 마스킹 대상 개인정보 분류 — 주민등록번호(외국인등록번호 포함)·카드번호 **2종**. */
enum class MaskCategory(val label: String) {
    RRN("주민등록번호"),
    CARD("카드번호"),
}

/** 마스킹 파이프라인을 통과한 텍스트. */
@JvmInline
value class MaskedText private constructor(val value: String) {
    /** 길이만 남긴다. 사유는 아래 「value class 와 toString」 절. */
    override fun toString(): String = "MaskedText(${value.length}자)"

    companion object {
        /**
         * [MaskedText] 를 만드는 **유일한** 경로. 임의 문자열을 감쌀 수 없다 —
         * 반드시 마스킹을 수행하고 그 결과만 감싼다.
         */
        internal fun mask(text: String): MaskingResult {
            val (masked, items) = maskParts(text)
            return MaskingResult(maskedText = MaskedText(masked), items = items)
        }

        /**
         * 이미 마스킹된 [source] 를 줄 단위로 쪼갠 뒤 [index] 번째 줄만 [MaskedText] 로
         * 감싼다 — 재변환 전용 통로다(`docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md`
         * §4 결정 3 「입력은 문서 전체를 마스킹한 뒤의 n번째 줄」).
         *
         * **[String] 이 아니라 [MaskedText] 를 받는다** — 1ffaf93 에서 없앤
         * `wrap(masked: String)` 과 이 함수가 다른 지점이 정확히 여기다: 그 함수는 임의
         * 문자열을 감쌌고, 이 함수는 이미 [mask] 를 거친 값만 조각낸다. `MaskedText` 는
         * 생성자가 `private` 이라 호출자가 위조할 수 없으므로, 이 함수로 마스킹되지 않은
         * 문자열이 새어 들어올 길이 없다 — [source] 의 줄 나눔이 여전히 마스킹된 문자열이라는
         * 사실은 [source] 자체가 이미 증명하고 있다.
         *
         * 단위만 따로 [mask] 에 다시 넣지 않는 이유는 그 계획 문서가 적어 두었다 — 범주별
         * 자리표시자 번호가 문서 순서로 붙는데, 단위를 떼어 다시 마스킹하면 그 번호가 처음부터
         * 다시 매겨져 저장된 대응표와 어긋난다.
         */
        internal fun unitOf(
            source: MaskedText,
            index: Int,
        ): MaskedText = MaskedText(source.value.split("\n")[index])
    }
}

// 본문 래퍼는 마스킹 여부와 무관하게 로그에 본문이 노출되지 않도록 길이만 출력한다.

/** 마스킹된 개별 항목 (검수 화면 표시용). */
data class MaskedItem(
    val category: MaskCategory,
    val placeholder: String,
    val original: Secret,
)

/** 마스킹 결과. */
data class MaskingResult(
    val maskedText: MaskedText,
    val items: List<MaskedItem>,
) {
    /**
     * 길이·건수만 남긴다. 사유는 아래 「value class 와 toString」 절과 **같지만**,
     * 이 타입만은 이유가 하나 더 있다.
     */
    override fun toString(): String = "MaskingResult(maskedText=${maskedText.value.length}자, items=${items.size})"
}

/** [restoreForExport] 의 결과. */
data class PlaceholderRestoration(
    val text: String,
    val missing: List<String>,
    val ambiguous: List<String>,
    val foreign: List<String>,
    val withheld: List<String>,
) {
    /**
     * 길이·건수만 남긴다. [text] 는 **자리표시자가 진짜 주민등록번호로 되돌아간 최종 본문**이라,
     * 이 저장소에서 평문 개인정보가 담기는 값 중 가장 위험한 축에 든다.
     */
    override fun toString(): String =
        "PlaceholderRestoration(text=${text.length}자, missing=${missing.size}, " +
            "ambiguous=${ambiguous.size}, foreign=${foreign.size}, withheld=${withheld.size})"
}

// 전화번호·이메일·계좌번호는 정책상 마스킹 대상이 아니며, 범주 변경은 계약과 함께 이뤄져야 한다.
// 패턴은 유니코드 숫자·구분자를 인식하되 복원을 위해 원문 자체를 정규화하지 않는다.

/** 구분자로 인정하는 하이픈류. */
private const val HYPHEN_CHARS =
    // 눈으로 구별하기 어려운 대시류는 코드포인트로 열거한다.
    """\u002D\u2010\u2013\u2014\u2212\uFF0D"""

/** 구분자로 인정하는 공백류. */
private const val SPACE_CHARS =
    // TAB은 열 경계이므로 구분자 공백에 포함하지 않는다.
    """\u0020\u00A0\u2007\u202F\u3000"""

private const val SPACE_CLASS = "[$SPACE_CHARS]"

private const val HYPHEN_CLASS = "[$HYPHEN_CHARS]"

/** **구분자 문법. RRN 과 CARD 가 이 하나를 공유한다.** */
private const val SEP = "(?:$SPACE_CLASS?$HYPHEN_CLASS$SPACE_CLASS?|$SPACE_CLASS?)"

/** RRN 성별코드로 인정하는 값. 5~8 은 외국인등록번호(고유식별정보)다. */
private val RRN_GENDER_CODES = 1..8

/** [Character.digit] 의 진법. 성별코드는 십진 한 자리다. */
private const val DECIMAL_RADIX = 10

/** 마스킹 대상 패턴 하나. */
private class MaskPattern(
    val category: MaskCategory,
    val regex: Regex,
    /** 매치를 채택할지 판정한다. 거부한 매치는 구간을 **점유하지 않는다**. */
    val accept: (MatchResult) -> Boolean = { true },
)

/** 성별코드 자리의 값이 1~8 인지 본다. 값 판정이라 표기 체계와 무관하다. */
private fun acceptsRrnGenderCode(match: MatchResult): Boolean {
    val genderCode = match.groupValues[1]
    // 보충 평면의 십진 숫자도 한 글자로 처리하도록 UTF-16 Char가 아닌 코드포인트로 센다.
    if (genderCode.codePointCount(0, genderCode.length) != 1) return false
    return Character.digit(genderCode.codePointAt(0), DECIMAL_RADIX) in RRN_GENDER_CODES
}

/** 카드번호 자릿수. 이 패턴이 보는 4×4 표기의 숫자 개수다. */
private const val CARD_DIGITS = 16

/** Luhn 은 **뒤에서 짝수 번째** 자리를 두 배 한다. 0-기반 인덱스에서 홀수 위치다. */
private const val LUHN_DOUBLE_EVERY = 2

/** 두 배 해서 한 자리를 넘으면 자릿수 합으로 되돌린다 — `12 → 1+2 = 3` 은 `12 - 9` 와 같다. */
private const val LUHN_WRAP = 9

/** 카드번호의 Luhn 체크디짓이 맞는지 본다. **RRN 성별코드 검사와 같은 종류·같은 훅이다.** */
private fun acceptsLuhn(match: MatchResult): Boolean {
    // Character.digit으로 유니코드 십진 숫자를 같은 값으로 판정한다.
    val digits = mutableListOf<Int>()
    var index = 0
    while (index < match.value.length) {
        val codePoint = match.value.codePointAt(index)
        val value = Character.digit(codePoint, DECIMAL_RADIX)
        if (value >= 0) digits.add(value)
        index += Character.charCount(codePoint)
    }
    // 패턴 변경에도 Luhn 판정은 16자리에서만 닫히도록 재확인한다.
    if (digits.size != CARD_DIGITS) return false

    var sum = 0
    for ((position, value) in digits.asReversed().withIndex()) {
        var contribution = value
        if (position % LUHN_DOUBLE_EVERY == 1) {
            contribution *= LUHN_DOUBLE_EVERY
            if (contribution > LUHN_WRAP) contribution -= LUHN_WRAP
        }
        sum += contribution
    }
    return sum % DECIMAL_RADIX == 0
}

/** 우선순위 순서 — 먼저 매칭된 구간이 이후 패턴보다 우선한다. */
private val PATTERNS: List<MaskPattern> =
    listOf(
        MaskPattern(
            category = MaskCategory.RRN,
            regex = unicodeRegex("""(?<!\d)\d{6}$SEP(\d)\d{6}(?!\d)"""),
            accept = ::acceptsRrnGenderCode,
        ),
        MaskPattern(
            category = MaskCategory.CARD,
            regex = unicodeRegex("""(?<!\d)\d{4}$SEP\d{4}$SEP\d{4}$SEP\d{4}(?!\d)"""),
            accept = ::acceptsLuhn,
        ),
    )

// 입력에 이미 있는 자리표시자 모양은 `!`로 가역 탈출해 생성 토큰과의 충돌을 막는다.

/** 계약이 못박은 자리표시자 형태(`^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`)를 범주 enum 에서 만든다. */
private val CATEGORY_ALTERNATION: String =
    MaskCategory.entries.joinToString(separator = "|") { Regex.escape(it.label) }

/**
 * 자리표시자 토큰. `unicodeRegex` 를 쓰지 않는다 — 축약 클래스가 없고 번호 자리는 계약이
 * `[0-9]` 로 못박았다. 전각 숫자(`１`)는 우리가 만드는 자리표시자와 문자열이 다르므로
 * 충돌 대상이 아니다.
 */
private val PLACEHOLDER: Regex = Regex("""\[\[(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/** 자리표시자 모양 + 이미 탈출된 모양. 탈출은 이 둘 모두에 한 겹을 더한다. */
private val PLACEHOLDER_LOOKALIKE: Regex = Regex("""\[\[!*(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/** 탈출된 모양만. 복원 끝에서 한 겹을 벗긴다. */
private val ESCAPED_LOOKALIKE: Regex = Regex("""\[\[!+(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/** 입력에 있던 자리표시자 모양에 탈출 한 겹을 씌운다. */
private fun escapeLookalikes(text: String): String =
    PLACEHOLDER_LOOKALIKE.replace(text) { match -> "[[!" + match.value.removePrefix("[[") }

/** [escapeLookalikes] 의 역. 한 겹만 벗긴다. */
private fun unescapeLookalikes(text: String): String =
    ESCAPED_LOOKALIKE.replace(text) { match -> "[[" + match.value.removePrefix("[[!") }

// 보이지 않는 문자를 제거한 탐색 뷰로 회피를 막되, 원문 좌표로 잘라 정확한 복원을 보장한다.
// 줄·페이지 경계는 서로 다른 숫자열을 합치지 않도록 제거 대상에서 제외한다.

/** 원본: `app/privacy/masking.py::_INVISIBLE_RANGES`. */
private val INVISIBLE_RANGES: List<IntRange> =
    listOf(
        // TAB, LF, VT, FF, CR은 의도적으로 제외한 줄·페이지 경계다.
        0x0000..0x0008,
        0x000E..0x001F,
        0x007F..0x007F, // DEL
        0x00AD..0x00AD, // 소프트하이픈 — 실문서에서 실제로 검출된 것
        0x200B..0x200F, // 폭 없는 공백·비연결자·방향 표시
        0x202A..0x202E, // 방향 재정의
        0x2060..0x2060, // word joiner
        0xFEFF..0xFEFF, // BOM / zero-width no-break space
    )

private val INVISIBLE: Set<Char> =
    INVISIBLE_RANGES
        // Char 집합이므로 BMP 밖 범위를 조용히 잘라 내지 못하게 한다.
        .onEach { range ->
            check(range.last <= Char.MAX_VALUE.code) {
                "INVISIBLE_RANGES 에 BMP 밖 코드포인트가 들어왔다: $range — Char 집합으로는 담을 수 없다."
            }
        }.flatMap { range -> range.map { it.toChar() } }
        .toSet()

private val INVISIBLE_RE: Regex =
    Regex(
        INVISIBLE_RANGES.joinToString(
            separator = "",
            prefix = "[",
            postfix = "]",
        ) { "\\u%04x-\\u%04x".format(it.first, it.last) },
    )

/** 보이지 않는 문자를 뺀 탐색용 뷰와 `뷰 인덱스 → 원문 인덱스` 대응표를 만든다. */
private fun searchView(text: String): Pair<String, IntArray?> {
    val view = INVISIBLE_RE.replace(text, "")
    if (view.length == text.length) return text to null

    val offsets = IntArray(view.length)
    var cursor = 0
    text.forEachIndexed { index, char ->
        if (char !in INVISIBLE) {
            offsets[cursor] = index
            cursor++
        }
    }
    return view to offsets
}

/** `accept` 를 통과한 매치만 낸다. **거부된 매치는 구간을 점유하지 않는다.** */
private fun acceptedMatches(
    pattern: MaskPattern,
    text: String,
): Sequence<MatchResult> =
    sequence {
        var from = 0
        while (from <= text.length) {
            val match = pattern.regex.find(text, from) ?: break
            if (pattern.accept(match)) {
                yield(match)
                // 미래의 빈 패턴도 무한 루프를 만들지 못하게 최소 한 칸 전진한다.
                from = maxOf(match.range.last + 1, match.range.first + 1)
            } else {
                from = match.range.first + 1
            }
        }
    }

/** 원문 직접 매칭 + 뷰 매칭(원문 좌표로 환원)의 **합집합**을 돌려준다. */
private fun candidateSpans(
    pattern: MaskPattern,
    text: String,
    view: String,
    offsets: IntArray?,
): List<Pair<Int, Int>> {
    val spans = LinkedHashSet<Pair<Int, Int>>()
    acceptedMatches(pattern, text).forEach { spans += it.range.first to it.range.last + 1 }

    if (offsets != null) {
        acceptedMatches(pattern, view).forEach { match ->
            // 마지막 매칭 문자의 원문 인덱스를 써서 뒤의 보이지 않는 문자를 삼키지 않는다.
            spans += offsets[match.range.first] to offsets[match.range.last] + 1
        }
    }
    return spans.sortedWith(compareBy({ it.first }, { -it.second }))
}

/**
 * 마스킹의 실제 구현. 문자열만 다루고 [MaskedText] 를 만들지 않는다 —
 * 만드는 것은 [MaskedText.Companion.mask] 하나뿐이다(생성 통로를 하나로 묶는 이유는
 * [MaskedText] KDoc).
 */
private fun maskParts(text: String): Pair<String, List<MaskedItem>> {
    // 입력 토큰을 먼저 탈출해 이후 자리표시자는 모두 이 함수가 만든 것으로 한정한다.
    val source = escapeLookalikes(text)
    val (view, offsets) = searchView(source)

    val spans = mutableListOf<Triple<Int, Int, MaskCategory>>()
    for (pattern in PATTERNS) {
        for ((start, end) in candidateSpans(pattern, source, view, offsets)) {
            // 채택된 앞선 패턴만 구간을 점유한다.
            if (spans.any { (taken, takenEnd, _) -> start < takenEnd && taken < end }) continue
            spans += Triple(start, end, pattern.category)
        }
    }
    spans.sortWith(compareBy({ it.first }, { it.second }))

    val counters = mutableMapOf<MaskCategory, Int>()
    val items = mutableListOf<MaskedItem>()
    val masked = StringBuilder()
    var cursor = 0

    for ((start, end, category) in spans) {
        val ordinal = (counters[category] ?: 0) + 1
        counters[category] = ordinal
        val placeholder = "[[${category.label}$ordinal]]"

        items +=
            MaskedItem(
                category = category,
                placeholder = placeholder,
                original = Secret(source.substring(start, end)),
            )
        masked.append(source, cursor, start).append(placeholder)
        cursor = end
    }
    masked.append(source, cursor, source.length)

    return masked.toString() to items.toList()
}

// 경계·복원 검증: `MaskingTest`.

/** 우선순위 패턴 순서로 개인정보를 찾아 자리표시자로 치환한다. */
fun maskText(text: String): MaskingResult = MaskedText.mask(text)

// 검수본이 없으면 자리표시자를 복원하지 않는다. 개수만으로는 모델이 옮긴 위치를 검증할 수 없다.
// ReviewedBody는 실제 사용자 제출에서만 만들고, ModelDraft는 모델 출력·저장 초안에만 사용한다.
// 새 생성 지점은 ProvenanceCreationSitesTest의 인구조사로 드러나야 한다.

/** 검수를 거치지 않은 모델 초안 (`easy_text`). */
@JvmInline
value class ModelDraft(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ModelDraft(${value.length}자)"
}

/** 사람이 검수 화면에서 **제출한** 본문 (`edited_text`). 제출 전에는 `null` 이다. */
@JvmInline
value class ReviewedBody(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ReviewedBody(${value.length}자)"
}

/**
 * 내보낼 최종 본문을 고르고, **사람 검수를 거친 경우에만** 자리표시자를 원문으로
 * 되돌린다 (**내보내기 전용**). HTTP 계약 GET export 의 복원 규칙과 같다.
 */
fun restoreForExport(
    draft: ModelDraft,
    reviewed: ReviewedBody?,
    items: List<MaskedItem>,
): PlaceholderRestoration {
    val body = reviewed?.value ?: draft.value
    val originals = items.associate { it.placeholder to it.original }
    val found = PLACEHOLDER.findAll(body).map { it.value }.toList()
    val occurrences = found.groupingBy { it }.eachCount()

    val restored =
        if (reviewed == null) {
            body
        } else {
            PLACEHOLDER.replace(body) { match ->
                val original = originals[match.value]
                if (original != null && occurrences[match.value] == 1) original.reveal() else match.value
            }
        }

    return PlaceholderRestoration(
        text = unescapeLookalikes(restored),
        missing = items.map { it.placeholder }.filter { occurrences[it] == null },
        ambiguous = items.map { it.placeholder }.filter { (occurrences[it] ?: 0) > 1 },
        foreign = found.filterNot { it in originals }.distinct(),
        withheld =
            if (reviewed == null) {
                items.map { it.placeholder }.filter { occurrences[it] == 1 }
            } else {
                emptyList()
            },
    )
}

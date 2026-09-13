package kr.easydoc.core.easyread

import java.math.BigDecimal

// FactPreservation.kt 의 일부 — 숫자·백분율의 비교 키만 이 파일에 모은다
// (파일 함수 수 상한 — detekt `TooManyFunctions`).
//
// **단위·소수점도 값의 일부다(리뷰 HIGH-1).** 값만 보고 비교하면 "3명"과 "3층"이 같은
// 숫자 3이라는 이유로 같은 사실로 오판되고, "1.5%"에서 소수점을 버리면 "15%"와 같은 값
// (digitsOnly="15")이 되어 다른 값인데 보존으로 오판된다. 그래서 NUMBER 의 정체성은
// (정규화된 값, 단위) 쌍이고, PERCENT 는 [BigDecimal] 로 소수점까지 정규화한다.

/**
 * 단위 뒤에 붙는 낱말 — `KoreanAmountWords.kt` 의 [ARABIC_UNIT_ALTERNATION](Arabic 숫자용,
 * "개월"처럼 긴 단위가 먼저 온다) 에 `달`(한글 수사 전용 단위)을 더한다. 문자열 끝(`$`)에
 * 고정돼 있어 대체 순서와 무관하게 가장 긴 단위가 선택된다 — 짧은 대체가 먼저 시도돼도
 * `$` 앞에서 실패하면 정규식이 다음 대체(더 긴 단위)로 되돌아가 결국 맞는 만큼 잡는다.
 */
private val NUMBER_UNIT_SUFFIX = Regex("""($ARABIC_UNIT_ALTERNATION|달)$""")

/**
 * NUMBER 의 정체성은 **(정규화된 값, 단위)** 쌍이다 — 값만 보면 "3명"과 "3층"이 같은 사실로
 * 오판된다(리뷰 HIGH-1). 단위가 없는 순수 숫자(2자리 이상 Arabic 숫자)는 단위 자리를 빈
 * 문자열로 둔다. [canonicalUnit] 으로 "달"↔"개"(개월의 두 표기)만 같은 단위로 맞춘다 —
 * 그 밖의 단위는 있는 그대로 다른 단위로 남는다.
 */
internal fun numberCompareKey(matchText: String): String {
    val unitMatch = NUMBER_UNIT_SUFFIX.find(matchText) ?: return digitsOnly(matchText)
    val unit = canonicalUnit(unitMatch.value)
    val valuePart = matchText.removeSuffix(unitMatch.value)
    val value = digitsOnly(valuePart).ifEmpty { countWordValue(valuePart.trim())?.toString().orEmpty() }
    return if (value.isEmpty()) "" else "$value:$unit"
}

/** `1.5%` 를 "15" 로 뭉개지 않도록 소수점을 보존해 [BigDecimal] 로 정규화한다(리뷰 HIGH-1). */
internal fun percentCompareKey(matchText: String): String {
    val numeric = Regex("""\d++(?:\.\d++)?""").find(matchText)?.value ?: return ""
    val value = BigDecimal(numeric).stripTrailingZeros()
    return if (value.compareTo(BigDecimal.ZERO) == 0) "0" else value.toPlainString()
}

package kr.easydoc.core.easyread

import java.math.BigInteger

// FactPreservation.kt 의 일부 — 한글 수사·금액 낱말 등가 판정만 이 파일에 모은다
// (파일 함수 수 상한 — detekt `TooManyFunctions`).
//
// **제한된 등가만 인정한다.** 원문이 "3개월"·"1,000원"이라 적고 변환문이 "세 달"·"천 원"으로
// 자연스럽게 고쳐 썼을 때, Arabic 숫자만 보는 추출은 이것을 "사실 누락"으로 잘못 잡는다
// (오탐 — 리뷰 HIGH-2). 그렇다고 임의의 큰 한글 수사를 다 풀어 낼 수는 없다: 11 이상은
// "열둘"처럼 합성형이 되고, 합성 규칙을 안전하게 다 처리하려다 잘못 계산하면 그 오류가
// 오탐보다 나쁘다(엉뚱한 값을 "같다"고 잘못 판단할 수 있다). 그래서 **1~10** 과 배수
// 단위(천·만·억) 하나만 인정하고, 그 밖은 오탐 가능성으로 남긴다 — 문서화는
// `docs/kotlin-redevelopment-backlog.md` §1.3 「사실 보존의 기계 검증」.

/** 고유어 수사 1~10. 11 이상(스물·서른 및 "열둘" 같은 합성형)은 다루지 않는다 — 위 파일 KDoc. */
private val NATIVE_ONES: Map<String, Int> =
    mapOf(
        "한" to 1,
        "하나" to 1,
        "두" to 2,
        "둘" to 2,
        "세" to 3,
        "셋" to 3,
        "네" to 4,
        "넷" to 4,
        "다섯" to 5,
        "여섯" to 6,
        "일곱" to 7,
        "여덟" to 8,
        "아홉" to 9,
        "열" to 10,
    )

/** 한자어 수사 1~10. */
private val SINO_ONES: Map<String, Int> =
    mapOf(
        "일" to 1,
        "이" to 2,
        "삼" to 3,
        "사" to 4,
        "오" to 5,
        "육" to 6,
        "칠" to 7,
        "팔" to 8,
        "구" to 9,
        "십" to 10,
    )

/** 개수 단위 앞에 오는 수사 — 고유어·한자어 모두 받는다(계약: "두 명"·"세 달"·"이 층" 모두 인정). */
private val COUNT_WORDS: Map<String, Int> = NATIVE_ONES + SINO_ONES

/**
 * 개수를 세는 단위. `FactPreservation.kt` [PATTERNS] 의 NUMBER 항(Arabic 숫자 + 단위)과 같은
 * 목록에 `달`(개월의 고유어)을 더한다 — "세 달"이 "3개월"과 같은 사실이 되려면 이 목록에 있어야 한다.
 */
private const val COUNT_UNIT_ALTERNATION = "명|개|일|시|분|세|살|회|건|층|호|번|달"

/**
 * "두 명"·"세 달"처럼 **수사 낱말 + 공백 + 단위**. 공백을 반드시 요구한다 — 공백이 없으면
 * "세금"·"세계"처럼 수사와 무관한 낱말의 접두부(예: "세")를 오탐할 위험이 커진다. 공백을
 * 요구해도 "한 개인정보"처럼 우연히 겹치는 사례는 남는다(위 파일 KDoc의 오탐 여지와 같은 종류).
 */
internal val WORD_NUMBER: Regex =
    run {
        val words = COUNT_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("""(?:$words)\s(?:$COUNT_UNIT_ALTERNATION)""")
    }

/**
 * "천 원"·"이천 원"·"삼만 원"처럼 **한자어 수사(생략 가능) + 천/만/억 + 원**. 배수 단위
 * 앞의 수사는 관용적으로 한자어만 쓰이므로([COUNT_WORDS] 와 달리) [SINO_ONES] 만 받는다.
 * "오천만원"처럼 배수 단위가 두 겹인 합성 금액은 이 정규식이 구조적으로 매치하지 못한다
 * (다음 배수 앞의 `\s*원` 이 어긋난다) — 잘못된 값을 계산하기보다 그냥 매치를 포기한다.
 */
internal val WORD_AMOUNT: Regex =
    run {
        val sino = SINO_ONES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("""(?:$sino)?(?:천|만|억)\s*원""")
    }

/** 억·만·천 배수. */
private val WON_PER_CHEON: BigInteger = BigInteger.valueOf(1_000L)
private val WON_PER_MAN: BigInteger = BigInteger.valueOf(10_000L)
private val WON_PER_EOK: BigInteger = BigInteger.valueOf(100_000_000L)

/** [matchText] 가 [SINO_ONES] 낱말로 시작하면 그 값을, 아니면 `null` 을 낸다(배수 단위 앞 수사). */
private fun sinoLeadingCount(matchText: String): Int? =
    SINO_ONES.entries.firstOrNull { (word, _) -> matchText.startsWith(word) }?.value

/** [matchText] 가 [COUNT_WORDS] 낱말로 시작하면 그 값을 낸다(개수 단위 앞 한글 수사). */
internal fun countWordValue(matchText: String): Int? =
    COUNT_WORDS.entries.firstOrNull { (word, _) -> matchText.startsWith(word) }?.value

/**
 * "10,000원"·"1만 원"·"5억원"·"천 원"·"삼만 원"을 같은 축(원 단위 정수)으로 비교하기
 * 위한 값. Arabic 숫자가 있으면 그 숫자가 배수 앞의 수량이고, 없으면(한글 수사 형태)
 * [sinoLeadingCount] 로 찾되 아무 수사도 없으면(예: "천 원") 1로 본다.
 */
internal fun amountValue(matchText: String): BigInteger {
    val digits = digitsOnly(matchText)
    val magnitude =
        when {
            "억" in matchText -> WON_PER_EOK
            "만" in matchText -> WON_PER_MAN
            "천" in matchText -> WON_PER_CHEON
            else -> null
        } ?: return BigInteger(digits.ifEmpty { "0" })
    val leadingCount = digits.ifEmpty { (sinoLeadingCount(matchText) ?: 1).toString() }
    return BigInteger(leadingCount) * magnitude
}

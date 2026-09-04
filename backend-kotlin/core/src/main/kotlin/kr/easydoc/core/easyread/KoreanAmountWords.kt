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
 * 단위 별칭 — **같은 뜻, 다른 낱말**만 정규화한다(리뷰 HIGH-1 이후, NUMBER 정체성이 단위를
 * 포함하게 되면서 필요해졌다). "3개월"은 이 파일의 단위 목록에서 "개"까지만 매칭되고(달의
 * 뒷글자 "월"은 잡히지 않는다), "세 달"의 단위는 "달"이다 — 정규화 없이 그대로 두면 값은
 * 같아도(3) 단위 문자가 달라(개≠달) 서로 다른 사실로 갈린다. "개"는 "3개"(낱개)처럼 진짜
 * 다른 뜻으로도 쓰이지만, 그 모호함은 이 파일이 새로 만든 것이 아니라 애초에 "개월"의 뒷글자
 * "월"을 잡지 못하는 기존 설계의 결과다 — 여기서는 그 기존 결과를 "달"과 맞춰 주기만 한다.
 */
private val UNIT_ALIASES: Map<String, String> = mapOf("달" to "개")

/** [unit] 이 [UNIT_ALIASES] 에 있으면 그 별칭으로, 없으면 그대로 돌려준다. */
internal fun canonicalUnit(unit: String): String = UNIT_ALIASES[unit] ?: unit

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

/** 배수 단위 — 억·만·천·백·십. 한 자리 문자 클래스라 겹치는 접두부 걱정이 없다. */
private const val MAGNITUDE_CLASS = "[억만천백십]"

/** [MAGNITUDE_CLASS] 각 글자의 크기. */
private val MAGNITUDE_VALUES: Map<Char, BigInteger> =
    mapOf(
        '억' to BigInteger.valueOf(100_000_000L),
        '만' to BigInteger.valueOf(10_000L),
        '천' to BigInteger.valueOf(1_000L),
        '백' to BigInteger.valueOf(100L),
        '십' to BigInteger.valueOf(10L),
    )

/** 배수 단위 앞에 올 수 있는 선행 수량 — Arabic 숫자(콤마 포함) 또는 한자어 수사 하나. */
private val LEADING_COUNT: String =
    SINO_ONES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }.let { sino ->
        """\d[\d,]*|$sino"""
    }

/**
 * "천 원"·"이천 원"·"삼만 원"·"5천만원"·"3,650천원"·"1억5천만원"처럼 **선행 수량(생략
 * 가능) + 배수 단위**가 하나 이상 이어진 뒤 "원"으로 끝나는 전체 구간. `+` 로 이어 붙여야
 * "5천만원"에서 "만원"만(=10,000) 부분 매치되는 사고를 막는다(리뷰 HIGH-2 재현 사례) — 반드시
 * 앞의 "5천"까지 포함한 전체 구간이 한 매치로 소비된다.
 */
internal val WORD_AMOUNT: Regex =
    run {
        val term = """(?:$LEADING_COUNT)?$MAGNITUDE_CLASS"""
        Regex("""(?:$term)+\s*원""")
    }

/**
 * [WORD_AMOUNT] 가 잡은 전체 구간을 다시 훑어 **선행 수량 + 연속한 배수 단위 묶음**(런) 하나씩을
 * 찾는다. "천만"처럼 배수 단위가 연달아 나오면 그 자릿값을 곱해서 한 런으로 묶는다(예:
 * "5천만" = 5 × (1,000 × 10,000) = 50,000,000) — 런을 단순히 각자 따로 더하면(5,000 + 10,000)
 * 틀린다. 런과 런 사이(예: "1억" 런과 "5천만" 런)는 **더한다**("1억5천만원" = 1억 + 5천만).
 */
private val RUN_REGEX: Regex = Regex("""($LEADING_COUNT)?($MAGNITUDE_CLASS+)""")

/** [units] 의 각 글자 크기를 곱한다 — "천만" = 1,000 × 10,000. */
private fun runMagnitude(units: String): BigInteger =
    units.fold(BigInteger.ONE) { acc, ch ->
        acc *
            MAGNITUDE_VALUES.getValue(ch)
    }

/** [countText] 가 비었으면 1, Arabic 숫자면 그 값, 한자어 수사 한 글자면 그 값. */
private fun leadingCountValue(countText: String): BigInteger =
    when {
        countText.isEmpty() -> BigInteger.ONE
        countText.first().isDigit() -> BigInteger(countText.replace(",", ""))
        else -> BigInteger.valueOf((SINO_ONES[countText] ?: 1).toLong())
    }

/** [matchText] 가 [COUNT_WORDS] 낱말로 시작하면 그 값을 낸다(개수 단위 앞 한글 수사). */
internal fun countWordValue(matchText: String): Int? =
    COUNT_WORDS.entries.firstOrNull { (word, _) -> matchText.startsWith(word) }?.value

/**
 * "10,000원"·"1만 원"·"5억원"·"천 원"·"삼만 원"·"5천만원"·"3,650천원"·"1억5천만원"을
 * 모두 같은 축(원 단위 정수)으로 비교하기 위한 값. 배수 단위가 하나도 없으면(순수 Arabic
 * 숫자 + 원) 그 숫자를 그대로 쓰고, 있으면 [RUN_REGEX] 로 찾은 런들을 합산한다.
 */
internal fun amountValue(matchText: String): BigInteger {
    val runs = RUN_REGEX.findAll(matchText).toList()
    if (runs.isEmpty()) {
        return BigInteger(digitsOnly(matchText).ifEmpty { "0" })
    }
    return runs.fold(BigInteger.ZERO) { sum, run ->
        sum + leadingCountValue(run.groupValues[1]) * runMagnitude(run.groupValues[2])
    }
}

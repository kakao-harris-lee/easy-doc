package kr.easydoc.core.easyread

import java.math.BigInteger

// FactPreservation.kt 의 일부 — 한글 수사·금액 낱말 등가 판정만 이 파일에 모은다.
// 제한된 등가만 인정한다. 1~10과 배수 단위(천·만·억)만 해석해 잘못된 값의 동일 판정을
// 피하고, 그 밖의 합성 수사는 보수적으로 남겨 둔다.

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

/**
 * 개수를 세는 단위. `FactPreservation.kt` [PATTERNS] 의 NUMBER 항(Arabic 숫자 + 단위)과 같은
 * 목록에 `달`(개월의 고유어)을 더한다 — "세 달"이 "3개월"과 같은 사실이 되려면 이 목록에 있어야 한다.
 */
private const val COUNT_UNIT_ALTERNATION = "명|개|일|시|분|세|살|회|건|층|호|번|달"

/**
 * Arabic 숫자 뒤에 붙어 사실로 인정하는 단위 — **긴 단위가 짧은 단위보다 먼저** 와야 한다
 * 여러 글자 단위를 먼저 시도해야 "3개월"이 "3개"로 부분 매치되지 않는다. 정규식 대체도
 * 길이 내림차순으로 만들어 이 순서를 유지한다.
 */
private val ARABIC_UNITS: Set<String> =
    setOf("개월", "시간", "분기", "명", "개", "일", "시", "분", "세", "살", "회", "건", "층", "호", "번")

/** [ARABIC_UNITS] 를 길이 내림차순으로 이은 정규식 대체 — 긴 단위가 먼저 시도된다. */
internal val ARABIC_UNIT_ALTERNATION: String =
    ARABIC_UNITS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

/**
 * 단위 별칭은 같은 뜻의 쉬운 표현을 원문 표현으로 정규화한다. "달"→"개월", "번"→"회",
 * "살"→"세"만 묶고, "개"처럼 의미가 다른 단위는 그대로 둔다.
 *
 * 적용 범위는 [numberCompareKey] 가 단위를 추출하는 매치로 제한된다. 두 자리 이상 숫자의
 * 단위는 소비하지 않고, 5자리 이상 숫자의 단위도 lookahead 로만 확인한다. 단위를 소비하면
 * 값:단위 비교 키가 달라져 기존 판정이 바뀐다.
 */
private val UNIT_ALIASES: Map<String, String> =
    mapOf(
        "달" to "개월",
        "번" to "회",
        "살" to "세",
    )

/** [unit] 이 [UNIT_ALIASES] 에 있으면 그 별칭으로, 없으면 그대로 돌려준다. */
internal fun canonicalUnit(unit: String): String = UNIT_ALIASES[unit] ?: unit

/**
 * "두 명"·"세 달"처럼 수사 낱말과 단위 사이에 공백이 있는 표현을 찾는다. 왼쪽 경계를
 * 요구하고 고유어 수사만 받아 "세금"·"세계" 같은 접두부 오탐을 줄인다. 오른쪽 경계는
 * 조사·어미가 붙은 정상 표현을 막을 수 있어 요구하지 않는다. 한자어 수사는 배수 단위
 * 금액([LEADING_COUNT])에서만 사용한다.
 */
internal val WORD_NUMBER: Regex =
    run {
        val words = NATIVE_ONES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("""(?<![가-힣A-Za-z0-9])(?:$words)\s(?:$COUNT_UNIT_ALTERNATION)""")
    }

/**
 * 공백을 생략한 고유어 수사·단위 표기 중, 문서에서 수사로 안전하게 읽을 수 있는 형태.
 *
 * [WORD_NUMBER] 는 `세금`·`세계`처럼 수사 접두부가 다른 낱말에 붙은 경우를 피하려고 공백을
 * 요구한다. 실제 공문에는 `한명`처럼 명사 단위 앞의 공백만 빠진 표기도 있으므로, 그 표기만
 * 별도 허용한다. `일`·`시`·`분`은 일반 낱말과의 충돌 여지가 커서 이 보정의 대상에서 뺀다.
 * 오른쪽에는 조사·서술어 어미만 허용해 `한명사` 같은 더 긴 낱말의 접두부를 사실로 세지 않는다.
 */
internal val COMPACT_WORD_NUMBER: Regex =
    run {
        val words = NATIVE_ONES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val units = "개월|명|개|세|살|회|건|층|호|번|달"
        val suffixes =
            "은|는|이|가|을|를|도|만|의|에|와|과|으로|로|부터|까지|에서|에게|한테|처럼|보다|마다|씩|" +
                "뿐|조차|이나|나|라도|이며|이고|이라고|입니다|이에요|예요|이다|인|이라는|이란"
        Regex(
            """(?<![가-힣A-Za-z0-9])(?:$words)(?:$units)(?=$|[\s\p{Punct}]|(?:$suffixes))""",
        )
    }

/** 배수 단위 — 억·만·천·백·십. 한 자리 문자 클래스라 겹치는 접두부 걱정이 없다. */
private const val MAGNITUDE_CLASS = "[억만천백십]"

/** `원`을 생략해도 금액 기준으로 읽을 수 있는 뒤따름말. */
private const val BARE_AMOUNT_QUALIFIERS = "이상|이하|미만|초과|이내|미달|정도|가량|내외|까지|부터"

/** [MAGNITUDE_CLASS] 각 글자의 크기. */
private val MAGNITUDE_VALUES: Map<Char, BigInteger> =
    mapOf(
        '억' to BigInteger.valueOf(100_000_000L),
        '만' to BigInteger.valueOf(10_000L),
        '천' to BigInteger.valueOf(1_000L),
        '백' to BigInteger.valueOf(100L),
        '십' to BigInteger.valueOf(10L),
    )

/**
 * 배수 단위 앞에 올 수 있는 선행 수량 — Arabic 숫자(콤마 포함) 또는 한자어 수사 하나.
 *
 * 선택적 수량이 숫자열 안에서 매 시작 위치마다 재시도되지 않도록 경계를 둔다.
 * `(?<![\d,])` 는 숫자·콤마 뒤의 재시도를 막아 숫자열마다 한 번만 시도하게 한다.
 * 따라서 배수 단위가 없는 긴 입력도 O(n)으로 탐색한다.
 */
private val LEADING_COUNT: String =
    SINO_ONES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }.let { sino ->
        """(?<![\d,])\d[\d,]*+|$sino"""
    }

/**
 * "천 원"·"이천 원"·"삼만 원"·"5천만원"·"1억 5천만원"·"3,650천원"·"1억5천만원"처럼 **선행
 * 수량(생략 가능) + 배수 단위**가 하나 이상 이어진 뒤 "원"으로 끝나는 전체 구간.
 *
 * `+` 로 이어 붙여 "5천만원"에서 "만원"만 부분 매치되지 않고 앞의 "5천"까지 한 매치로
 * 소비한다.
 *
 * 각 항 뒤의 `\s*` 는 "1억 5천만원"처럼 항 사이 공백을 허용한다. 항 자체에는 공백을
 * 넣지 않아 서로 무관한 숫자와 금액을 이어 붙이지 않는다.
 *
 * 바깥 `+` 도 possessive(`++`)다 — 이미 소비한 항을 되무를 이유가 없다("원"이 항 뒤에
 * 안 나오면 애초에 항을 하나 덜 삼켰어야 할 여지가 없다, 항과 "원"은 겹치는 문자가 없다).
 */
internal val WORD_AMOUNT: Regex =
    run {
        val term = """(?:$LEADING_COUNT)?$MAGNITUDE_CLASS"""
        Regex("""(?:$term\s*)++원""")
    }

/** `10억 이상`처럼 억 단위 뒤에 `원`을 쓰지 않은 금액 비교·기준 표현. */
internal val BARE_WORD_AMOUNT: Regex =
    run {
        val term = """(?<![가-힣A-Za-z0-9.,+-])(?:$LEADING_COUNT)억"""
        Regex("""$term(?=$|[.!?。！？]|\s*(?:$BARE_AMOUNT_QUALIFIERS))""")
    }

/**
 * [WORD_AMOUNT] 가 잡은 전체 구간을 다시 훑어 **선행 수량 + 연속한 배수 단위 묶음**(런) 하나씩을
 * 찾는다. "천만"처럼 배수 단위가 연달아 나오면 그 자릿값을 곱해서 한 런으로 묶는다(예:
 * "5천만" = 5 × (1,000 × 10,000) = 50,000,000) — 런을 단순히 각자 따로 더하면(5,000 + 10,000)
 * 틀린다. 런과 런 사이(예: "1억" 런과 "5천만" 런)는 **더한다**("1억5천만원" = 1억 + 5천만).
 */
private val RUN_REGEX: Regex = Regex("""($LEADING_COUNT)?($MAGNITUDE_CLASS++)""")

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

/**
 * [matchText] 가 [NATIVE_ONES] 낱말로 시작하면 그 값을 낸다(개수 단위 앞 한글 수사).
 *
 * [WORD_NUMBER] 가 고유어만 허용하므로 이 함수도 고유어만 해석한다. [SINO_ONES] 는
 * [LEADING_COUNT] 의 배수 단위 금액에 사용한다.
 */
internal fun countWordValue(matchText: String): Int? =
    NATIVE_ONES.entries.firstOrNull { (word, _) -> matchText.startsWith(word) }?.value

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

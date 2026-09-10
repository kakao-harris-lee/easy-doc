package kr.easydoc.core.privacy

import java.time.LocalDate

// 개인정보 경고용 검출 — 계획 `docs/plans/2026-09-10-personal-data-warning.md`.
//
// 등록한 뒤가 아니라 등록 **전에** 한 번 멈춰 세우는 경고다. 값을 바꾸지 않는다 —
// 2026-09-07에 제거한 자동 마스킹(PR #58)을 되살리는 것이 아니다.

/**
 * 경고용 검출 종류. 계약 `X-Personal-Data-Kinds` 헤더 값의 어휘(정렬된 소문자,
 * 쉼표 구분)와 같다 — [wireName] 이 그 토큰이다.
 *
 * **전화번호·이메일은 대상이 아니다**(계획 §2.1) — 공공 안내문에는 담당 부서 연락처와
 * 문의 메일이 정상적으로 들어 있어, 그것을 경고하면 거의 모든 문서에서 경고가 뜨고
 * 소음이 되어 정작 중요한 경우에 무시된다.
 */
enum class PersonalDataKind(val wireName: String) {
    /** 카드번호 — 15~16자리, 첫 자리 3·4·5·6, Luhn 통과. */
    CARD("card"),

    /** 주민등록번호 — 13자리(하이픈 허용), 생년월일·성별 코드·검증식(모듈러스 11) 통과. */
    RRN("rrn"),
}

/**
 * 등록 전 개인정보 경고용 검출 — **순수 함수**다(Spring도 DB도 없이 테스트된다, 계획 §2.4).
 *
 * 반환은 **발견된 종류의 집합뿐이다** — 값도 위치도 건수도 돌려주지 않는다(돌려주면
 * 어딘가에 남는다). **검증식을 통과한 것만 경고한다** — 자릿수만 보면 사업자등록번호·
 * 전화번호·날짜 조합 같은 오탐이 쏟아진다(같은 계획 §2.1).
 *
 * [text] 는 저장 경계에서 이미 정규화된 본문이다(`normalizeLineEndings` 이후) — 호출하는
 * 쪽(`DocumentService.store`)이 그 정규화된 값을 넘긴다.
 */
fun detectPersonalData(text: String): Set<PersonalDataKind> {
    val kinds = mutableSetOf<PersonalDataKind>()
    if (RESIDENT_NUMBER_PATTERN.findAll(text).any(::isValidResidentNumber)) {
        kinds += PersonalDataKind.RRN
    }
    if (CARD_NUMBER_PATTERN.findAll(text).any(::isValidCardNumber)) {
        kinds += PersonalDataKind.CARD
    }
    return kinds
}

/**
 * 주민등록번호 모양 — 6자리(생년월일) + 하이픈(선택) + 7자리, **앞뒤로 다른 숫자가
 * 붙지 않은** 정확히 13자리. 어깨 조회(lookaround)로 경계를 두는 이유: 14자리 이상의
 * 숫자열(사업자등록번호+전화번호를 이어 붙인 것 등)의 부분 문자열을 13자리로 잘못
 * 잘라 검증식에 넣지 않기 위해서다.
 */
private val RESIDENT_NUMBER_PATTERN = Regex("""(?<!\d)\d{6}-?\d{7}(?!\d)""")

/**
 * 카드번호 모양 — 4-4-4-(1~7)자리, 구분자는 하이픈·공백(그룹마다 선택). 정확한 길이·첫
 * 자리 판정은 [isValidCardNumber] 가 한다 — 여기서는 후보를 넓게 잡는다.
 *
 * 그룹을 4자리 단위로 고정하는 이유는 오탐 억제다 — 전화번호(3-4-4·2-4-4)를 공백 하나로
 * 이어 붙인 문자열은 이 모양에 맞지 않아 걸리지 않는다. "숫자 하나마다 구분자 하나"
 * 식의 느슨한 패턴은 인접한 두 전화번호를 하나의 후보로 잘못 묶는다.
 */
private val CARD_NUMBER_PATTERN = Regex("""(?<!\d)\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{1,7}(?!\d)""")

/**
 * 주민등록번호 판정 — **검증식만으로는 부족하다**(실측: 법인등록번호가 모양이 완전히
 * 같아 모듈러스-11 검증식을 10.2% 확률로 우연히 통과한다, 리뷰 2026-09-10). 법인등록번호에는
 * 없고 주민등록번호에만 있는 구조 둘을 검증식 **앞에** 더한다 — 이것은 오탐 회피용
 * 꼼수가 아니라 주민등록번호를 더 정확히 판정하는 것이다.
 *
 * 1. [hasPlausibleBirthDate] — 앞 6자리(YYMMDD)와 7번째 자리(성별·세기 코드)가 실제
 *    생년월일을 이룬다. 법인등록번호의 같은 자리는 등기소·법인종류 코드·일련번호라
 *    날짜가 아니다.
 * 2. [isValidResidentNumber] — 위를 통과한 것만 모듈러스-11 검증식을 본다.
 *
 * 날짜 조건 하나만으로도 무작위 13자리의 통과율이 크게 떨어진다(월 1/12, 유효한 일수
 * 조합까지 더하면 그보다도 낮다) — 검증식과 독립인 추가 관문이라 둘 다 통과해야 한다.
 */
private fun isValidResidentNumber(match: MatchResult): Boolean {
    val digits = match.value.filter(Char::isDigit)
    if (digits.length != RESIDENT_NUMBER_LENGTH) return false
    return hasPlausibleBirthDate(digits) && hasValidChecksum(digits)
}

/** 모듈러스-11 검증식 본체 — [isValidResidentNumber] 가 날짜 조건을 통과한 것만 이곳에 넘긴다. */
private fun hasValidChecksum(digits: String): Boolean {
    val sum = RESIDENT_NUMBER_WEIGHTS.indices.sumOf { (digits[it] - '0') * RESIDENT_NUMBER_WEIGHTS[it] }
    val check = (RESIDENT_NUMBER_MODULUS - (sum % RESIDENT_NUMBER_MODULUS)) % DECIMAL_BASE
    return check == digits[RESIDENT_NUMBER_LENGTH - 1] - '0'
}

/**
 * 앞 12자리가 실제 생년월일을 이루는가 — **YYMMDD + 성별·세기 코드**(7번째 자리)를
 * 함께 해석해야 판정할 수 있다. 세기 코드가 실제 연도(1900년대·2000년대)를 정하므로
 * `LocalDate.of` 가 윤년 2월 29일까지 정확히 걸러 준다(무효 날짜는 예외를 던진다).
 *
 * 세기 코드는 **1~8만** 유효하다(9·0은 1800년대라 사실상 쓰이지 않는다) — 이 판정이
 * 곧 "7번째 자리는 1~8만 유효하다"는 별도 조건까지 함께 만족시킨다.
 */
private fun hasPlausibleBirthDate(digits: String): Boolean {
    val centuryDigit = digits[CENTURY_DIGIT_INDEX] - '0'
    val centuryBase = CENTURY_BASE_BY_CODE[centuryDigit] ?: return false
    val twoDigitYear = digits.substring(YEAR_START, YEAR_END).toInt()
    val month = digits.substring(YEAR_END, MONTH_END).toInt()
    val day = digits.substring(MONTH_END, DAY_END).toInt()
    return runCatching { LocalDate.of(centuryBase + twoDigitYear, month, day) }.isSuccess
}

/**
 * 카드번호 판정 — **길이·Luhn만으로는 부족하다**(실측: 무작위 13~19자리 숫자의 Luhn
 * 통과율이 10.0%다, 리뷰 2026-09-10). 접수번호·관리번호 같은 긴 숫자 식별자가 안내문에
 * 실리면 같은 확률로 오검출된다.
 *
 * **15~16자리, 첫 자리 3·4·5·6으로 좁힌다** — 대부분의 카드가 16자리(Amex 는 15),
 * 주요 카드 브랜드(Visa 4·Mastercard 5·Amex 3·discover 계열 6)가 이 대역에서 시작한다.
 * 드문 길이·대역의 카드번호는 놓친다 — **이 기능은 경고 도구이지 차단 장치가 아니므로**
 * 오탐을 줄이는 쪽이 놓치는 쪽보다 낫다는 판단이다(계획 §2.1과 같은 축의 트레이드오프).
 */
private fun isValidCardNumber(match: MatchResult): Boolean {
    val digits = match.value.filter(Char::isDigit)
    val plausibleLength = digits.length in CARD_NUMBER_MIN_LENGTH..CARD_NUMBER_MAX_LENGTH
    val plausiblePrefix = digits[0] in CARD_FIRST_DIGITS
    if (!plausibleLength || !plausiblePrefix) return false
    var sum = 0
    var doubleDigit = false
    for (index in digits.length - 1 downTo 0) {
        var value = digits[index] - '0'
        if (doubleDigit) {
            value *= 2
            if (value > MAX_SINGLE_DIGIT) value -= MAX_SINGLE_DIGIT
        }
        sum += value
        doubleDigit = !doubleDigit
    }
    return sum % DECIMAL_BASE == 0
}

private const val RESIDENT_NUMBER_LENGTH = 13
private const val RESIDENT_NUMBER_MODULUS = 11
private val RESIDENT_NUMBER_WEIGHTS = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5)

/** 주민등록번호 7번째 자리(0-based index 6) — 성별·세기 코드가 사는 자리. */
private const val CENTURY_DIGIT_INDEX = 6

/** YYMMDD 부분 문자열 경계 — YY[0,2) · MM[2,4) · DD[4,6). */
private const val YEAR_START = 0
private const val YEAR_END = 2
private const val MONTH_END = 4
private const val DAY_END = 6

/**
 * 성별·세기 코드 → 태어난 세기의 기준 연도. 1·2(1900년대 내국인)·5·6(1900년대 외국인)은
 * 1900, 3·4(2000년대 내국인)·7·8(2000년대 외국인)은 2000이다. 9·0은 이 표에 없어
 * [hasPlausibleBirthDate] 가 곧바로 거절한다 — 1800년대생은 사실상 없다.
 */
private val CENTURY_BASE_BY_CODE: Map<Int, Int> =
    mapOf(1 to 1900, 2 to 1900, 3 to 2000, 4 to 2000, 5 to 1900, 6 to 1900, 7 to 2000, 8 to 2000)

/** 15자리(Amex)·16자리(대부분의 카드)로 좁힌다 — [isValidCardNumber] KDoc. */
private const val CARD_NUMBER_MIN_LENGTH = 15
private const val CARD_NUMBER_MAX_LENGTH = 16

/** 주요 카드 브랜드가 시작하는 첫 자리 — [isValidCardNumber] KDoc. */
private val CARD_FIRST_DIGITS = setOf('3', '4', '5', '6')

/** 두 검증식이 함께 쓰는 10진 자리값 — 검산의 나머지 연산이 이 값을 쓴다. */
private const val DECIMAL_BASE = 10

/** Luhn 알고리즘에서 두 배로 만든 값이 한 자리를 넘을 때 빼는 값(= 두 자리 수의 자릿수 합). */
private const val MAX_SINGLE_DIGIT = 9

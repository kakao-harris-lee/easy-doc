package kr.easydoc.core.easyread

// FactPreservation.kt 의 일부 — 날짜·시각을 문자열이 아니라 구성요소로 비교하기 위한
// 파싱만 이 파일에 모은다(파일 함수 수 상한 — detekt `TooManyFunctions`).
//
// **왜 문자열 이어붙이기가 아닌가(리뷰 HIGH-1).** "2026.09.04"와 "2026년 9월 4일"을 그냥
// 숫자만 뽑아 이어붙이면 "20260904" 대 "202694"가 되어 다른 값으로 보인다 — 09월의 앞자리
// 0이 한글 표기("9월")에는 없기 때문이다. "10:00"과 "오전 10시"도 같은 문제가 시각에서
// 재현된다. 그래서 이 파일은 문자열을 이어붙이지 않고 **연·월·일**, **자정 기준 분**처럼
// 의미 있는 구성요소로 각각 파싱한 뒤 그 값으로 비교한다.

private const val MINUTES_PER_HOUR = 60
private const val NOON_HOUR = 12

/** `HH:MM` 표기를 분으로 환산한다. 이미 24시간제로 보고 그대로 계산한다. */
private fun colonTimeMinutes(matchText: String): Int? {
    val colon = Regex("""(\d{1,2}):(\d{2})""").find(matchText) ?: return null
    val (hour, minute) = colon.destructured
    return hour.toInt() * MINUTES_PER_HOUR + minute.toInt()
}

/**
 * "오전 10시"·"오후 3시"·"15시" 표기를 분으로 환산한다. 오전/오후 표기가 없는 시(예:
 * "15시")는 24시간제 값 그대로 쓴다. 12 이하인데 표기가 없으면(예: "3시") 오전·오후를 가릴
 * 수 없어 적은 그대로 쓴다 — 조용히 오후로 가정해 다른 시각과 잘못 동일시하지 않기
 * 위해서다(오탐 방지가 미탐보다 우선 — 파일 상단 KDoc과 같은 원칙).
 */
private fun clockTimeMinutes(matchText: String): Int? {
    val hourMatch = Regex("""(\d{1,2})시""").find(matchText) ?: return null
    var hour = hourMatch.groupValues[1].toInt()
    val minute =
        Regex("""(\d{1,2})분""")
            .find(matchText)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
    when {
        "오전" in matchText -> if (hour == NOON_HOUR) hour = 0
        "오후" in matchText -> if (hour != NOON_HOUR) hour += NOON_HOUR
    }
    return hour * MINUTES_PER_HOUR + minute
}

/** 자정 기준 분으로 정규화한다 — "오후 3시" = "15시" = "15:00" = 900. */
internal fun timeMinutes(matchText: String): Int? = colonTimeMinutes(matchText) ?: clockTimeMinutes(matchText)

/** ISO 표기(`YYYY[.-]MM[.-]DD`)의 `(연도, 월, 일)`. */
private fun isoDateComponents(matchText: String): Triple<Int?, Int, Int>? {
    val iso = Regex("""(\d{4})[.\-](\d{1,2})[.\-](\d{1,2})""").find(matchText) ?: return null
    val (year, month, day) = iso.destructured
    return Triple(year.toInt(), month.toInt(), day.toInt())
}

/** 한글 표기(`(YYYY년)? M월 D일`)의 `(연도?, 월, 일)` — 연도는 "YYYY년"이 있을 때만 채워진다. */
private fun koreanDateComponents(matchText: String): Triple<Int?, Int, Int>? {
    val korean = Regex("""(?:(\d{4})년\s*)?(\d{1,2})월\s*(\d{1,2})일""").find(matchText) ?: return null
    val (yearRaw, monthRaw, dayRaw) = korean.destructured
    val year = yearRaw.takeIf { it.isNotEmpty() }?.toInt()
    return Triple(year, monthRaw.toInt(), dayRaw.toInt())
}

/** `(연도?, 월, 일)` — 연도는 ISO 표기나 "YYYY년" 표기가 있을 때만 채워진다. */
internal fun dateComponents(matchText: String): Triple<Int?, Int, Int>? =
    isoDateComponents(matchText) ?: koreanDateComponents(matchText)

/** `MMDD` — [dateComponents] 의 월·일을 고정 두 자리로 적어 `sameDate` 의 1차 비교 키로 쓴다. */
internal fun dateCompareKey(matchText: String): String? =
    dateComponents(matchText)?.let { (_, month, day) -> "%02d%02d".format(month, day) }

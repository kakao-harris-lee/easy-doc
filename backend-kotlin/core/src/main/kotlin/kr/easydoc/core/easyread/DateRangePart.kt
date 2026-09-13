package kr.easydoc.core.easyread

import java.time.DateTimeException
import java.time.LocalDate

/** 날짜 범위 안에서만 생략된 연·월을 보충한다. 원문 오프셋과 표시 문자열은 유지한다. */
internal class DateRangePart(
    val range: IntRange,
    val canonical: String,
)

private const val RANGE_JOIN = """\h*+\.?\h*+(?:\([월화수목금토일]\)|[월화수목금토일]요일)?\h*+(?:부터|[~～–—])\h*+"""
private val KOREAN_DATE_RANGE =
    Regex(
        """(?<!\d)(?<left>(?<year>\d{4})년\h*+(?<month>\d{1,2})월\h*+(?<day>\d{1,2})일)""" + RANGE_JOIN +
            """(?<right>(?:(?<endYear>\d{4})년\h*+)?(?:(?<endMonth>\d{1,2})월\h*+)?(?<endDay>\d{1,2})일)""",
    )
private val NUMERIC_DATE_RANGE =
    Regex(
        """(?<![\d.])(?<left>(?<year>\d{4})[.\-]\h*+(?<month>\d{1,2})[.\-]\h*+(?<day>\d{1,2}))""" + RANGE_JOIN +
            """(?<right>(?:(?<endYear>\d{4})[.\-]\h*+)?(?<endMonth>\d{1,2})[.\-]\h*+(?<endDay>\d{1,2}))(?!\d)""",
    )

internal fun dateRangeParts(text: String): List<DateRangePart> =
    listOf(KOREAN_DATE_RANGE, NUMERIC_DATE_RANGE).flatMap { regex ->
        regex.findAll(text).flatMap(::partsOfDateRange).toList()
    }

private fun partsOfDateRange(match: MatchResult): List<DateRangePart> {
    fun number(name: String): Int? = match.groups[name]?.value?.toIntOrNull()
    val year = checkNotNull(number("year"))
    val month = checkNotNull(number("month"))
    val day = checkNotNull(number("day"))
    val endMonth = number("endMonth") ?: month
    val endDay = checkNotNull(number("endDay"))
    // 12월→1월처럼 연도가 넘어갈 수 있는 범위는 생략된 연도를 추측하지 않는다.
    val endYear = number("endYear") ?: year.takeIf { endMonth > month || (endMonth == month && endDay >= day) }
    if (!validDate(year, month, day) || !validDate(endYear ?: year, endMonth, endDay)) return emptyList()
    val left = checkNotNull(match.groups["left"])
    val right = checkNotNull(match.groups["right"])
    val endPrefix = endYear?.let { "${it}년 " }.orEmpty()
    return listOf(
        DateRangePart(left.range, "${year}년 ${month}월 ${day}일"),
        DateRangePart(right.range, "$endPrefix${endMonth}월 ${endDay}일"),
    )
}

private fun validDate(
    year: Int,
    month: Int,
    day: Int,
): Boolean =
    try {
        LocalDate.of(year, month, day)
        true
    } catch (_: DateTimeException) {
        false
    }

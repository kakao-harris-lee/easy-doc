package kr.easydoc.application.usage

import kr.easydoc.core.exceptions.InvalidInputException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** `from`/`to` 가 `YYYY-MM-DD` 로 읽히지 않는다. */
internal const val MALFORMED_USAGE_DATE_MESSAGE = "from·to는 YYYY-MM-DD 형식이어야 합니다"

/** `to`가 `from`보다 앞이다. */
internal const val USAGE_TO_BEFORE_FROM_MESSAGE = "to는 from보다 앞일 수 없습니다"

/** 조회 기간이 366일을 넘는다. */
internal const val USAGE_RANGE_TOO_WIDE_MESSAGE = "조회 기간은 366일을 넘을 수 없습니다"

/**
 * `[from, toExclusive)` 구간 — 이미 zone 자정 기준으로 변환된 `Instant` 쌍. U2
 * ([UsageQueryService])·U3([UsageReportService])가 같은 형식·범위 검증 규칙을 공유한다 —
 * 기본 기간(U2는 이번 달 1일~오늘, U3는 지난달 전체)만 호출자가 각자 정한다.
 */
internal data class UsagePeriod(
    val fromInstant: Instant,
    val toExclusiveInstant: Instant,
)

/**
 * `from`/`to` 날짜 파싱·검증 공용 로직 — U2가 정한 규칙(계획
 * `docs/plans/2026-09-07-usage-ledger-and-report.md` §2 결정 5·6)을 U3가 그대로 재사용한다.
 */
internal object UsagePeriodResolver {
    private const val MAX_RANGE_DAYS = 366L

    /** `DateTimeFormatter.ISO_LOCAL_DATE`는 엄격하다 — 자리 수·구분자·달력상 없는 날짜를 전부 거절한다. */
    fun parseDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            throw InvalidInputException(MALFORMED_USAGE_DATE_MESSAGE)
        }

    /**
     * 포함 상한 [fromDate]~[toDate]를 [zone] 자정 기준 `[from, toExclusive)`로 바꾼다. `to`가
     * `from`보다 앞이거나 구간이 366일을 넘으면 [InvalidInputException].
     */
    fun resolve(
        fromDate: LocalDate,
        toDate: LocalDate,
        zone: ZoneId,
    ): UsagePeriod {
        if (toDate.isBefore(fromDate)) throw InvalidInputException(USAGE_TO_BEFORE_FROM_MESSAGE)
        // 포함 상한이므로 날짜 수는 (일수 차이 + 1)이다 — from·to가 같은 날이면 1일이다.
        val inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        if (inclusiveDays > MAX_RANGE_DAYS) {
            throw InvalidInputException(USAGE_RANGE_TOO_WIDE_MESSAGE)
        }
        return UsagePeriod(
            fromInstant = fromDate.atStartOfDay(zone).toInstant(),
            // 포함 상한 `to`의 다음날 자정을 배타 상한으로 쓴다 — `to` 날짜 23:59:59.999...는
            // 포함되고 다음날 00:00:00은 제외된다.
            toExclusiveInstant = toDate.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }
}

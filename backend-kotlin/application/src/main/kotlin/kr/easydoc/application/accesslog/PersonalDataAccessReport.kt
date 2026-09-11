package kr.easydoc.application.accesslog

import kr.easydoc.application.usage.UsagePeriodResolver
import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 접속기록 한 행 — 점검 보고서(§3.5)의 원시 목록에 쓴다. */
data class PersonalDataAccessLogRow(
    val id: UUID,
    val actorUserId: UUID,
    val accessedAt: Instant,
    val clientIp: String,
    val operation: String,
    val subjectScope: String?,
    val outcome: PersonalDataAccessOutcome,
) {
    /** [clientIp]는 접속지 정보라 가린다 — `PersonalDataAccessLogEntry`와 같은 규약. */
    override fun toString(): String =
        "PersonalDataAccessLogRow(id=$id, actorUserId=$actorUserId, accessedAt=$accessedAt, " +
            "clientIp=$CONTENT_MASK, operation=$operation, subjectScope=$subjectScope, outcome=$outcome)"
}

/** 점검 보고서(§3.5) 집계 읽기 포트 — `[fromInstant, toExclusiveInstant)` 구간의 행 전부. */
interface PersonalDataAccessLogRepository {
    fun findBetween(
        fromInstant: Instant,
        toExclusiveInstant: Instant,
    ): List<PersonalDataAccessLogRow>
}

/**
 * 월 1회 점검용 보고서(계획 §3.5) — 기간, 취급자별 접속 횟수, 업무별 횟수, 거절된 접속,
 * 점검자가 눈으로 볼 수 있는 원시 목록을 담는다. **점검을 수행했다는 사실 자체는 여기서
 * 만들지 않는다** — 이 보고서 파일이 그 증거다.
 */
data class PersonalDataAccessReport(
    val from: LocalDate,
    val to: LocalDate,
    val totalCount: Int,
    val rejectedCount: Int,
    val countsByActor: Map<UUID, Int>,
    val countsByOperation: Map<String, Int>,
    val rows: List<PersonalDataAccessLogRow>,
)

/**
 * 점검 보고서 유스케이스 — `access-log-report` 프로필이 부른다. 기본 기간은 [zone] 기준
 * **지난달 전체**다(`UsageReportService`와 같은 기본값 — 월 1회 점검은 지난달 접속기록을
 * 본다). `from`·`to` 형식·범위 검증은 [UsagePeriodResolver]를 그대로 재사용한다
 * (`application.usage`, 같은 모듈 — U2·U3가 공유하는 것과 같은 이유).
 */
class PersonalDataAccessReportService(
    private val repository: PersonalDataAccessLogRepository,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    fun generate(
        from: String?,
        to: String?,
    ): PersonalDataAccessReport {
        val today = LocalDate.now(clock.withZone(zone))
        val previousMonth = today.minusMonths(1)
        val fromDate = from?.let(UsagePeriodResolver::parseDate) ?: previousMonth.withDayOfMonth(1)
        val toDate =
            to?.let(UsagePeriodResolver::parseDate)
                ?: previousMonth.withDayOfMonth(previousMonth.lengthOfMonth())
        val period = UsagePeriodResolver.resolve(fromDate, toDate, zone)

        val rows = repository.findBetween(period.fromInstant, period.toExclusiveInstant).sortedBy { it.accessedAt }
        return PersonalDataAccessReport(
            from = fromDate,
            to = toDate,
            totalCount = rows.size,
            rejectedCount = rows.count { it.outcome == PersonalDataAccessOutcome.REJECTED },
            countsByActor = rows.groupingBy { it.actorUserId }.eachCount(),
            countsByOperation = rows.groupingBy { it.operation }.eachCount(),
            rows = rows,
        )
    }
}

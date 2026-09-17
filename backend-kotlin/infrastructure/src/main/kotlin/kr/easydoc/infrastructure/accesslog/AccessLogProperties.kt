package kr.easydoc.infrastructure.accesslog

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Period
import java.time.ZoneId

/**
 * 접속기록(`personal_data_access_logs`, V22) 경계 설정. 바인딩 접두사는
 * `easydoc.access-log`.
 *
 * [zone]은 점검 보고서(§3.5) `from`/`to` 날짜 경계를 해석하는 시간대다 — `UsageProperties`
 * 와 같은 기본값(`Asia/Seoul`).
 *
 * [retention]은 파기 배치가 읽는다(계획 `docs/plans/2026-09-17-access-log-purge.md`) —
 * `PersonalDataAccessLogPurgePolicy`가 법정 최소 보관기간(1년) 미만을 거부한다. 기본값
 * 1년은 고시(개인정보의 안전성 확보조치 기준)가 요구하는 최소 보관기간이다.
 *
 * [purgeEnabled]·[purgeBatchSize]는 파기 배치(worker `access-log` 단계) 전용 값이다.
 */
@ConfigurationProperties(prefix = "easydoc.access-log")
data class AccessLogProperties(
    val zone: String = DEFAULT_ZONE,
    val retention: Period = Period.ofYears(1),
    val purgeEnabled: Boolean = true,
    val purgeBatchSize: Int = DEFAULT_PURGE_BATCH_SIZE,
) {
    fun zoneId(): ZoneId = ZoneId.of(zone)

    companion object {
        const val DEFAULT_ZONE: String = "Asia/Seoul"
        const val DEFAULT_PURGE_BATCH_SIZE: Int = 200
    }
}

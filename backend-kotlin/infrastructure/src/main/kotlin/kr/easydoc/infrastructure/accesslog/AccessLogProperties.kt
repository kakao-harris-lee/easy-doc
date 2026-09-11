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
 * [retention]은 **이 조각에서 쓰지 않는다.** 보관기간이 지난 뒤의 파기는 범위 밖이다
 * (계획 `docs/plans/2026-09-11-access-log-retention.md` §3.3) — 나중에 파기 배치를 붙일
 * 자리를 구성값으로 미리 남겨 둔다. 기본값 1년은 고시(개인정보의 안전성 확보조치 기준)가
 * 요구하는 최소 보관기간이다.
 */
@ConfigurationProperties(prefix = "easydoc.access-log")
data class AccessLogProperties(
    val zone: String = DEFAULT_ZONE,
    val retention: Period = Period.ofYears(1),
) {
    fun zoneId(): ZoneId = ZoneId.of(zone)

    companion object {
        const val DEFAULT_ZONE: String = "Asia/Seoul"
    }
}

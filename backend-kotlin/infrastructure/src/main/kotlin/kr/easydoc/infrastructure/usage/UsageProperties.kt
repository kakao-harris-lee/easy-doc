package kr.easydoc.infrastructure.usage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

/**
 * 사용량 집계(U2) 경계 설정. 바인딩 접두사는 `easydoc.usage`.
 *
 * [zone] 은 `from`/`to` 날짜 경계를 해석하는 시간대다 — 공공기관 대상 서비스라 기본값은
 * `Asia/Seoul`이다(계획 §2 결정 5). [zoneId]가 문자열을 실제 [ZoneId]로 바꾼다 — 잘못된
 * 시간대 이름은 기동 시점에 [java.time.DateTimeException]으로 드러난다(운영 중 조용히
 * 무시되지 않는다).
 */
@ConfigurationProperties(prefix = "easydoc.usage")
data class UsageProperties(val zone: String = DEFAULT_ZONE) {
    fun zoneId(): ZoneId = ZoneId.of(zone)

    companion object {
        const val DEFAULT_ZONE: String = "Asia/Seoul"
    }
}

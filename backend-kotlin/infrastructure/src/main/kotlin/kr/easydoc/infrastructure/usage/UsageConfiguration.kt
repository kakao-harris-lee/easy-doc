package kr.easydoc.infrastructure.usage

import kr.easydoc.application.usage.UsageQueryService
import kr.easydoc.application.usage.UsageReadRepository
import kr.easydoc.application.usage.UsageReportRepository
import kr.easydoc.application.usage.UsageReportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 사용량 조회 유스케이스(U2) + 운영 리포트(U3) 조립. `usageReportRepository`/`usageReportService`는
 * `usage-report` 프로필로 gate 하지 않는다 — `usageQueryService`와 같은 이유로 JdbcClient·설정
 * 값만 있으면 되는 값싼 객체라 상시 조립해도 부담이 없고(비교: `KeyRotationConfiguration`은
 * 무거운 배치 객체라 `rotate-keys`로 gate 한다), `api` 모듈은 `infrastructure`를 `runtimeOnly`로만
 * 붙여 `JdbcUsageReportRepository`를 컴파일 시점에 볼 수 없다 — `usage-report` 프로필 전용
 * `UsageReportConfiguration`(`api`)은 이미 여기서 조립된 [UsageReportService] 빈을 그대로 받아
 * `UsageReportRunner`만 배선한다.
 */
@Configuration(proxyBeanMethods = false)
class UsageConfiguration {
    @Bean
    fun usageReadRepository(jdbcClient: JdbcClient): UsageReadRepository = JdbcUsageReadRepository(jdbcClient)

    /** `easydoc.usage.zone` 은 [UsageProperties] 가 문다 — 기본 `Asia/Seoul`. */
    @Bean
    fun usageQueryService(
        repository: UsageReadRepository,
        properties: UsageProperties,
    ): UsageQueryService = UsageQueryService(repository, properties.zoneId(), Clock.systemUTC())

    @Bean
    fun usageReportRepository(jdbcClient: JdbcClient): UsageReportRepository = JdbcUsageReportRepository(jdbcClient)

    @Bean
    fun usageReportService(
        repository: UsageReportRepository,
        properties: UsageProperties,
    ): UsageReportService = UsageReportService(repository, properties.zoneId(), Clock.systemUTC())
}

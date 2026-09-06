package kr.easydoc.infrastructure.usage

import kr.easydoc.application.usage.UsageQueryService
import kr.easydoc.application.usage.UsageReadRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/** 사용량 조회 유스케이스(U2) 조립. */
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
}

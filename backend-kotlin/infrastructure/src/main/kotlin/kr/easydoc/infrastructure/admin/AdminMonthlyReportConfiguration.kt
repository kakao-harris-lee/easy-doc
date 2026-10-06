package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminMonthlyReportRepository
import kr.easydoc.application.admin.AdminMonthlyReportService
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class AdminMonthlyReportConfiguration {
    @Bean
    fun adminMonthlyReportRepository(
        jdbc: JdbcClient,
        manager: PlatformTransactionManager,
    ): AdminMonthlyReportRepository = JdbcAdminMonthlyReportRepository(jdbc, manager)

    @Bean
    fun adminMonthlyReportService(
        repository: AdminMonthlyReportRepository,
        properties: UsageProperties,
    ): AdminMonthlyReportService = AdminMonthlyReportService(repository, properties.zoneId(), Clock.systemUTC())
}

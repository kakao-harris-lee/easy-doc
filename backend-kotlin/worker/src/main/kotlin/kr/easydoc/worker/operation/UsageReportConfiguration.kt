package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.usage.UsageReportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** `usage-report` 프로필에서 사용량 보고서 명령을 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(USAGE_REPORT_PROFILE)
class UsageReportConfiguration {
    @Bean
    fun usageReportRunner(
        service: UsageReportService,
        users: UserRepository,
        accessLog: RecordPersonalDataAccess,
    ): UsageReportRunner = UsageReportRunner(service, users, accessLog)
}

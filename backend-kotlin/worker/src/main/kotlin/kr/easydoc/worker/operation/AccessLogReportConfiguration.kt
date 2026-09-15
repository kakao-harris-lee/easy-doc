package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** `access-log-report` 프로필에서 접속기록 보고서 명령을 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(ACCESS_LOG_REPORT_PROFILE)
class AccessLogReportConfiguration {
    @Bean
    fun accessLogReportRunner(service: PersonalDataAccessReportService): AccessLogReportRunner =
        AccessLogReportRunner(service)
}

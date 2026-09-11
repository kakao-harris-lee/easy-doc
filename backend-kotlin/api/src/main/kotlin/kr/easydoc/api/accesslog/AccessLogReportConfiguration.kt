package kr.easydoc.api.accesslog

import kr.easydoc.api.ACCESS_LOG_REPORT_PROFILE
import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * `access-log-report` profile 전용 조립. [PersonalDataAccessReportService] 는
 * `infrastructure`의 `AccessLogConfiguration`이 프로필과 무관하게 이미 조립한 빈이다
 * (`UsageReportConfiguration` KDoc과 같은 이유). 이 클래스는 그 빈을 받아 CLI 실행부
 * ([AccessLogReportRunner])만 배선한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(ACCESS_LOG_REPORT_PROFILE)
class AccessLogReportConfiguration {
    @Bean
    fun accessLogReportRunner(service: PersonalDataAccessReportService): AccessLogReportRunner =
        AccessLogReportRunner(service)
}

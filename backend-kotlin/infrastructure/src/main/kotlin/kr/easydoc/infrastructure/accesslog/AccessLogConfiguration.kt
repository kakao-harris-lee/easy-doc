package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogRepository
import kr.easydoc.application.accesslog.PersonalDataAccessLogWriter
import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 접속기록 조립 — `UsageConfiguration`과 같은 이유로 프로필과 무관하게 상시 조립한다.
 * [RecordPersonalDataAccess]는 `AdminAccessInterceptor`(`api`, 항상 등재)가 관리자 API
 * 요청마다 부르므로 프로필로 gate 할 수 없다. `access-log-report` 프로필 전용
 * `AccessLogReportConfiguration`(`api`)은 여기서 조립된 [PersonalDataAccessReportService]
 * 빈을 받아 CLI 실행부만 배선한다 — `UsageReportConfiguration`과 같은 형태.
 */
@Configuration(proxyBeanMethods = false)
class AccessLogConfiguration {
    @Bean
    fun personalDataAccessLogWriter(jdbcClient: JdbcClient): PersonalDataAccessLogWriter =
        JdbcPersonalDataAccessLogWriter(jdbcClient)

    @Bean
    fun recordPersonalDataAccess(writer: PersonalDataAccessLogWriter): RecordPersonalDataAccess =
        RecordPersonalDataAccess(writer, Clock.systemUTC())

    @Bean
    fun personalDataAccessLogRepository(jdbcClient: JdbcClient): PersonalDataAccessLogRepository =
        JdbcPersonalDataAccessLogRepository(jdbcClient)

    @Bean
    fun personalDataAccessReportService(
        repository: PersonalDataAccessLogRepository,
        properties: AccessLogProperties,
    ): PersonalDataAccessReportService =
        PersonalDataAccessReportService(repository, properties.zoneId(), Clock.systemUTC())
}

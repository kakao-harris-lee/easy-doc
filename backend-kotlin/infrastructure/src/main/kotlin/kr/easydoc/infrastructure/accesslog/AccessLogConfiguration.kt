package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogRepository
import kr.easydoc.application.accesslog.PersonalDataAccessLogWriter
import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/** API 기록과 worker 보고 명령이 공유하는 접속기록 조립 지점. */
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

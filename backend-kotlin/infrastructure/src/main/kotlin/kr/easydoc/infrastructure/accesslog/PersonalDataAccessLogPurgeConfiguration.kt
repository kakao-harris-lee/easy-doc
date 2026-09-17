package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.LoggingPersonalDataAccessLogPurgeObserver
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurge
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgeObserver
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgePolicy
import kr.easydoc.application.accesslog.PurgePersonalDataAccessLogs
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.exceptions.ConfigurationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 접속기록(`personal_data_access_logs`, V22) 파기 조립 — worker만 지운다
 * (`SignupGrantRecordPurgeConfiguration`과 같은 판단). `AccessLogConfiguration`(api·worker
 * 공용, 삽입·보고 조립)과는 변경 이유가 다른 별도 조립 지점이다 — api 프로세스에는 이 표를
 * 지우는 코드 경로가 존재하지 않아야 한다(계획 `docs/plans/2026-09-17-access-log-purge.md`
 * §2.1).
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class PersonalDataAccessLogPurgeConfiguration {
    @Bean
    fun personalDataAccessLogPurge(jdbcClient: JdbcClient): PersonalDataAccessLogPurge =
        JdbcPersonalDataAccessLogPurge(jdbcClient)

    @Bean
    fun personalDataAccessLogPurgeObserver(): PersonalDataAccessLogPurgeObserver =
        LoggingPersonalDataAccessLogPurgeObserver()

    /**
     * `PersonalDataAccessLogPurgePolicy.init`의 `require`(프로그래밍 오류용
     * `IllegalArgumentException`)를 여기서 `ConfigurationException`으로 옮긴다 — 운영자가
     * `easydoc.access-log.retention`을 법정 최소 보관기간(1년) 미만으로 설정하는 사고를
     * 기동 실패로 막는다(`CreditAccountConfiguration.parseSignupGrantValidity`와 같은 판단).
     */
    @Bean
    fun personalDataAccessLogPurgePolicy(properties: AccessLogProperties): PersonalDataAccessLogPurgePolicy =
        try {
            PersonalDataAccessLogPurgePolicy(
                enabled = properties.purgeEnabled,
                retention = properties.retention,
                batchSize = properties.purgeBatchSize,
            )
        } catch (failure: IllegalArgumentException) {
            throw invalidRetention(properties, failure)
        }

    private fun invalidRetention(
        properties: AccessLogProperties,
        failure: IllegalArgumentException,
    ): ConfigurationException =
        ConfigurationException(
            "easydoc.access-log.retention(접속기록 보관기간)은 P1Y 이상이어야 한다" +
                "(고시 최소 보관기간): ${properties.retention} (${failure.message})",
        )

    /** `Clock.systemUTC()` — `SignupGrantRecordPurgeConfiguration.purgeSignupGrantRecords`와 같은 판단. */
    @Bean
    fun purgePersonalDataAccessLogs(
        store: PersonalDataAccessLogPurge,
        transactionRunner: TransactionRunner,
        observer: PersonalDataAccessLogPurgeObserver,
        policy: PersonalDataAccessLogPurgePolicy,
    ): PurgePersonalDataAccessLogs =
        PurgePersonalDataAccessLogs(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
            clock = Clock.systemUTC(),
        )
}

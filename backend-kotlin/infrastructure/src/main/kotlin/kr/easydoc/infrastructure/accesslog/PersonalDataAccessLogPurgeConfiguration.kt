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
     * `IllegalArgumentException`)에 검증을 맡기지 않는다 — `retention`·`batchSize` 두
     * `require`가 같은 예외 타입을 던져 `try/catch` 하나로는 어느 값이 잘못됐는지 구분할
     * 수 없다(리뷰 지적: `purge-batch-size` 위반이 `retention` 위반으로 오진단됐다).
     * 여기서 필드마다 먼저 검사해 필드명이 정확한 `ConfigurationException`을 던진다 —
     * 운영자가 설정을 고칠 때 어느 키를 봐야 하는지 메시지가 가리켜야 한다.
     */
    @Bean
    fun personalDataAccessLogPurgePolicy(properties: AccessLogProperties): PersonalDataAccessLogPurgePolicy {
        validateBatchSize(properties)
        validateRetention(properties)
        return PersonalDataAccessLogPurgePolicy(
            enabled = properties.purgeEnabled,
            retention = properties.retention,
            batchSize = properties.purgeBatchSize,
        )
    }

    private fun validateBatchSize(properties: AccessLogProperties) {
        if (properties.purgeBatchSize < 1) {
            throw ConfigurationException(
                "easydoc.access-log.purge-batch-size(접속기록 파기 배치 크기)는 1 이상이어야 한다: " +
                    "${properties.purgeBatchSize}",
            )
        }
    }

    /** 하한 판정은 `PersonalDataAccessLogPurgePolicy.meetsMinimumRetention`을 그대로 재사용한다. */
    private fun validateRetention(properties: AccessLogProperties) {
        if (!PersonalDataAccessLogPurgePolicy.meetsMinimumRetention(properties.retention)) {
            throw ConfigurationException(
                "easydoc.access-log.retention(접속기록 보관기간)은 " +
                    "${PersonalDataAccessLogPurgePolicy.MINIMUM_RETENTION} 이상이어야 한다" +
                    "(고시 최소 보관기간): ${properties.retention}",
            )
        }
    }

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

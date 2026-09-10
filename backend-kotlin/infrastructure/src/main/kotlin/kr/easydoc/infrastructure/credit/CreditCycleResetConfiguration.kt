package kr.easydoc.infrastructure.credit

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditCycleReset
import kr.easydoc.application.credit.CreditCycleResetObserver
import kr.easydoc.application.credit.CreditCycleResetPolicy
import kr.easydoc.application.credit.LoggingCreditCycleResetObserver
import kr.easydoc.application.credit.ResetCreditCycles
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 크레딧 주기 초기화 설정. 바인딩 접두사는 `easydoc.credits.cycle-reset` — 크레딧을
 * 「구독 주기에 포함된 이용량」으로 바꾼 사용자 결정(2026-09-10).
 */
@ConfigurationProperties(prefix = "easydoc.credits.cycle-reset")
data class CreditCycleResetProperties(
    val enabled: Boolean = true,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 200
    }
}

/**
 * worker 만 주기를 초기화한다. API 프로세스에는 스케줄과 이 빈이 없다 —
 * `UnverifiedAccountPurgeConfiguration`(`infrastructure.auth`)과 같은 판단.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class CreditCycleResetConfiguration {
    /**
     * [usageProperties] 는 `easydoc.usage.zone`(기본 `Asia/Seoul`)을 그대로 재사용한다 —
     * `JdbcCreditCycleReset` KDoc(리뷰 지적: 사용량 집계와 같은 "한 달"을 써야 한다. 새
     * `easydoc.credits.zone`을 만들지 않는다).
     */
    @Bean
    fun creditCycleReset(
        jdbcClient: JdbcClient,
        usageProperties: UsageProperties,
    ): CreditCycleReset = JdbcCreditCycleReset(jdbcClient, usageProperties.zoneId())

    @Bean
    fun creditCycleResetObserver(): CreditCycleResetObserver = LoggingCreditCycleResetObserver()

    @Bean
    fun creditCycleResetPolicy(properties: CreditCycleResetProperties): CreditCycleResetPolicy =
        CreditCycleResetPolicy(
            enabled = properties.enabled,
            batchSize = properties.batchSize,
        )

    /** `Clock.systemUTC()` 를 쓴다 — `UnverifiedAccountPurgeConfiguration` 과 같은 판단. */
    @Bean
    fun resetCreditCycles(
        store: CreditCycleReset,
        transactionRunner: TransactionRunner,
        observer: CreditCycleResetObserver,
        policy: CreditCycleResetPolicy,
    ): ResetCreditCycles =
        ResetCreditCycles(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
            clock = Clock.systemUTC(),
        )
}

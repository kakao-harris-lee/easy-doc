package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.LoggingUnverifiedAccountPurgeObserver
import kr.easydoc.application.auth.PurgeUnverifiedAccounts
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UnverifiedAccountPurge
import kr.easydoc.application.auth.UnverifiedAccountPurgeObserver
import kr.easydoc.application.auth.UnverifiedAccountPurgePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration

/**
 * 미검증 계정 파기 설정. 바인딩 접두사는 `easydoc.auth.unverified-purge`
 * (`docs/kotlin-redevelopment-backlog.md` §1.4 ⑵ ⓐ, 2026-09-07 결정 — 미검증 계정이
 * `ix_users_email` 을 무기한 선점하는 결함을 TTL 파기로 닫는다).
 */
@ConfigurationProperties(prefix = "easydoc.auth.unverified-purge")
data class UnverifiedAccountProperties(
    val enabled: Boolean = true,
    val ttlHours: Long = DEFAULT_TTL_HOURS,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_TTL_HOURS: Long = 24
        const val DEFAULT_BATCH_SIZE: Int = 200
    }
}

/**
 * worker 만 미검증 계정을 지운다. API 프로세스에는 스케줄과 이 빈이 없다 —
 * `RetentionPurgeConfiguration`(`infrastructure.document`)과 같은 판단.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class UnverifiedAccountPurgeConfiguration {
    @Bean
    fun unverifiedAccountPurge(jdbcClient: JdbcClient): UnverifiedAccountPurge = JdbcUnverifiedAccountPurge(jdbcClient)

    @Bean
    fun unverifiedAccountPurgeObserver(): UnverifiedAccountPurgeObserver = LoggingUnverifiedAccountPurgeObserver()

    @Bean
    fun unverifiedAccountPurgePolicy(properties: UnverifiedAccountProperties): UnverifiedAccountPurgePolicy =
        UnverifiedAccountPurgePolicy(
            enabled = properties.enabled,
            ttl = Duration.ofHours(properties.ttlHours),
            batchSize = properties.batchSize,
        )

    /**
     * `Clock.systemUTC()` 를 쓴다 — `AuthConfiguration.accessTokens` 와 같은 판단(서버 시계는
     * NTP 동기를 전제한다).
     */
    @Bean
    fun purgeUnverifiedAccounts(
        store: UnverifiedAccountPurge,
        transactionRunner: TransactionRunner,
        observer: UnverifiedAccountPurgeObserver,
        policy: UnverifiedAccountPurgePolicy,
    ): PurgeUnverifiedAccounts =
        PurgeUnverifiedAccounts(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
            clock = Clock.systemUTC(),
        )
}

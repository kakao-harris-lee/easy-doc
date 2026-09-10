package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.ExpiredAuthArtifactPurge
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgeObserver
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgePolicy
import kr.easydoc.application.auth.LoggingExpiredAuthArtifactPurgeObserver
import kr.easydoc.application.auth.PurgeExpiredAuthArtifacts
import kr.easydoc.application.auth.TransactionRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration

/**
 * 만료 인증 아티팩트(`email_verification_codes`·`password_reset_codes`·`oauth_states`)
 * 파기 설정. 바인딩 접두사는 `easydoc.auth.ephemeral-purge`
 * (`docs/plans/2026-09-10-personal-data-inventory.md` §2.2 확정 결함, 개인정보 보호법
 * §21 — 목적 달성 시 지체 없이 파기).
 *
 * [retentionHours] 기본값 24시간을 고른 이유: 코드 TTL이 10분, 재발송 쿨다운이 60초라
 * 24시간은 두 값을 크게 웃돌아 어떤 기능도 건드리지 않으면서(파기 기준은 `created_at`
 * — `JdbcExpiredAuthArtifactPurge` KDoc), 개인정보 처리방침에 「24시간 내 파기」로
 * 적을 수 있는 값이다.
 */
@ConfigurationProperties(prefix = "easydoc.auth.ephemeral-purge")
data class AuthEphemeralPurgeProperties(
    val enabled: Boolean = true,
    val retentionHours: Long = DEFAULT_RETENTION_HOURS,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_RETENTION_HOURS: Long = 24
        const val DEFAULT_BATCH_SIZE: Int = 500
    }
}

/**
 * worker 만 만료 인증 아티팩트를 지운다 — `UnverifiedAccountPurgeConfiguration`과 같은
 * 판단(API 프로세스에는 스케줄과 이 빈이 없다).
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class ExpiredAuthArtifactPurgeConfiguration {
    @Bean
    fun expiredAuthArtifactPurge(jdbcClient: JdbcClient): ExpiredAuthArtifactPurge =
        JdbcExpiredAuthArtifactPurge(jdbcClient)

    @Bean
    fun expiredAuthArtifactPurgeObserver(): ExpiredAuthArtifactPurgeObserver = LoggingExpiredAuthArtifactPurgeObserver()

    @Bean
    fun expiredAuthArtifactPurgePolicy(properties: AuthEphemeralPurgeProperties): ExpiredAuthArtifactPurgePolicy =
        ExpiredAuthArtifactPurgePolicy(
            enabled = properties.enabled,
            retention = Duration.ofHours(properties.retentionHours),
            batchSize = properties.batchSize,
        )

    /** `Clock.systemUTC()` — `UnverifiedAccountPurgeConfiguration.purgeUnverifiedAccounts`와 같은 판단. */
    @Bean
    fun purgeExpiredAuthArtifacts(
        store: ExpiredAuthArtifactPurge,
        transactionRunner: TransactionRunner,
        observer: ExpiredAuthArtifactPurgeObserver,
        policy: ExpiredAuthArtifactPurgePolicy,
    ): PurgeExpiredAuthArtifacts =
        PurgeExpiredAuthArtifacts(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
            clock = Clock.systemUTC(),
        )
}

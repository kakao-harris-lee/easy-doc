package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.SubscriptionStore
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossBillingStore
import kr.easydoc.application.subscription.TossBillingTiming
import kr.easydoc.application.subscription.TossGateway
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Typed timing settings for the Toss test billing workflow. The defaults preserve the values
 * that were previously fixed in the service and HTTP adapter.
 */
@ConfigurationProperties("easydoc.payment.toss")
data class TossBillingProperties(
    @param:org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
    val connectTimeout: java.time.Duration = DEFAULT_CONNECT_TIMEOUT,
    @param:org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
    val requestTimeout: java.time.Duration = DEFAULT_REQUEST_TIMEOUT,
    @param:org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
    val sessionTtl: java.time.Duration = TossBillingTiming.DEFAULT_SESSION_TTL,
    @param:org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
    val retryInterval: java.time.Duration = TossBillingTiming.DEFAULT_RETRY_INTERVAL,
    @param:org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.DAYS)
    val retryWindow: java.time.Duration = TossBillingTiming.DEFAULT_RETRY_WINDOW,
) {
    init {
        require(!connectTimeout.isZero && !connectTimeout.isNegative) {
            "easydoc.payment.toss.connect-timeout 은 0보다 커야 합니다"
        }
        require(!requestTimeout.isZero && !requestTimeout.isNegative) {
            "easydoc.payment.toss.request-timeout 은 0보다 커야 합니다"
        }
        TossBillingTiming(sessionTtl, retryInterval, retryWindow)
    }

    fun timing(): TossBillingTiming = TossBillingTiming(sessionTtl, retryInterval, retryWindow)

    companion object {
        val DEFAULT_CONNECT_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(10)
        val DEFAULT_REQUEST_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(70)
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
class TossConfiguration {
    @Bean
    fun tossStore(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): TossBillingStore = JdbcTossBillingStore(jdbc, cipher)

    @Bean
    fun tossGateway(
        properties: PaymentProperties,
        billing: TossBillingProperties,
    ): TossGateway =
        TossHttpGateway(
            properties.tossSecretKey,
            connectTimeout = billing.connectTimeout,
            requestTimeout = billing.requestTimeout,
        )

    @Bean
    @Suppress("LongParameterList")
    fun tossBillingService(
        store: TossBillingStore,
        subscriptions: SubscriptionStore,
        accounts: CreditAccountRepository,
        credits: CreditAccountService,
        transaction: TransactionRunner,
        gateway: TossGateway,
        properties: PaymentProperties,
        billing: TossBillingProperties,
        usage: UsageProperties,
        users: kr.easydoc.application.auth.UserRepository,
    ): TossBillingService =
        TossBillingService(
            store,
            subscriptions,
            accounts,
            credits,
            transaction,
            gateway,
            properties.provider == "toss_test",
            Clock.systemUTC(),
            usage.zoneId(),
            properties.tossClientKey,
            users,
            billing.timing(),
        )
}

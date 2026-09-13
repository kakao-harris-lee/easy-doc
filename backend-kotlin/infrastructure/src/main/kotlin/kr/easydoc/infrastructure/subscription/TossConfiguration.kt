package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.SubscriptionStore
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossBillingStore
import kr.easydoc.application.subscription.TossGateway
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
class TossConfiguration {
    @Bean
    fun tossStore(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): TossBillingStore = JdbcTossBillingStore(jdbc, cipher)

    @Bean
    fun tossGateway(properties: PaymentProperties): TossGateway = TossHttpGateway(properties.tossSecretKey)

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
        )
}

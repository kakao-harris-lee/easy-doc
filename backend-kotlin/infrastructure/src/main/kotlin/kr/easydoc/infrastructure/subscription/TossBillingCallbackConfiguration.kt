package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.TossBillingCallbackService
import kr.easydoc.application.subscription.TossBillingCallbackStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
class TossBillingCallbackConfiguration {
    @Bean
    fun tossBillingCallbackStore(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): TossBillingCallbackStore = JdbcTossBillingCallbackStore(jdbc, cipher)

    @Bean
    fun tossBillingCallbackService(
        store: TossBillingCallbackStore,
        toss: kr.easydoc.application.subscription.TossBillingService,
        accounts: CreditAccountRepository,
        transaction: TransactionRunner,
    ): TossBillingCallbackService =
        TossBillingCallbackService(
            store = store,
            toss = toss,
            accounts = accounts,
            transaction = transaction,
            clock = Clock.systemUTC(),
        )
}

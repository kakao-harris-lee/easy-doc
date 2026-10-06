package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminBillingService
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossBillingStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
class AdminBillingConfiguration {
    @Bean
    fun adminBillingService(
        jdbc: JdbcClient,
        billing: TossBillingService,
        orders: TossBillingStore,
        accounts: CreditAccountRepository,
        transaction: TransactionRunner,
    ): AdminBillingService = AdminBillingService(JdbcAdminBillingStore(jdbc), billing, orders, accounts, transaction)
}

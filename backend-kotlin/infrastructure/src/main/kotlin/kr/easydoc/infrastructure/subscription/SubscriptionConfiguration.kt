package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.subscription.PaymentGateway
import kr.easydoc.application.subscription.SubscriptionService
import kr.easydoc.application.subscription.SubscriptionStore
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.util.UUID

@ConfigurationProperties("easydoc.payment")
data class PaymentProperties(
    val provider: String = "stub",
    val mockEnabled: Boolean = false,
    val tossClientKey: kr.easydoc.core.security.Secret = kr.easydoc.core.security.Secret.EMPTY,
    val tossSecretKey: kr.easydoc.core.security.Secret = kr.easydoc.core.security.Secret.EMPTY,
)

/** Deterministic stub: the same input always has the same outcome; no network, keys or SDK. */
class StubPaymentGateway : PaymentGateway {
    override fun charge(
        orderId: UUID,
        amount: Int,
        fail: Boolean,
    ): Boolean = !fail
}

@Configuration(proxyBeanMethods = false)
class SubscriptionConfiguration {
    @Bean
    fun paymentGateway(
        properties: PaymentProperties,
        environment: Environment,
    ): PaymentGateway {
        check(properties.provider in setOf("stub", "toss_test")) { "Only stub and Toss test payments are allowed" }
        if (properties.provider == "toss_test") {
            check(
                environment.activeProfiles.any { it in setOf("local", "test") } &&
                    environment.activeProfiles.none { it in setOf("prod", "production") },
            ) { "Toss test requires local/test" }
            check(
                properties.tossClientKey.reveal().startsWith("test_ck_") &&
                    properties.tossSecretKey.reveal().startsWith("test_sk_"),
            ) { "Toss individual test keys are required" }
        }
        check(!properties.mockEnabled || environment.activeProfiles.any { it in setOf("local", "test") }) {
            "Mock payments require the local or test profile"
        }
        check(!properties.mockEnabled || environment.activeProfiles.none { it in setOf("prod", "production") }) {
            "Mock payments are forbidden in production"
        }
        return StubPaymentGateway()
    }

    @Bean
    fun subscriptionStore(jdbc: JdbcClient): SubscriptionStore = JdbcSubscriptionStore(jdbc)

    @Bean
    @Suppress("LongParameterList")
    fun subscriptionService(
        store: SubscriptionStore,
        credits: CreditAccountService,
        transaction: TransactionRunner,
        gateway: PaymentGateway,
        properties: PaymentProperties,
        usage: UsageProperties,
        users: kr.easydoc.application.auth.UserRepository,
        toss: org.springframework.beans.factory.ObjectProvider<kr.easydoc.application.subscription.TossBillingService>,
    ): SubscriptionService =
        SubscriptionService(
            store,
            credits,
            transaction,
            gateway,
            properties.mockEnabled && properties.provider == "stub",
            Clock.systemUTC(),
            usage.zoneId(),
            users,
            toss.ifAvailable,
        )
}

package kr.easydoc.infrastructure.retention

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.retention.PurgeRetainedTransactionRecords
import kr.easydoc.application.retention.RetainedTransactionAdminService
import kr.easydoc.application.retention.RetainedTransactionPurgePolicy
import kr.easydoc.application.retention.RetainedTransactionPurgeStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 법정 거래 원장은 API와 worker가 같은 JDBC 저장소를 사용한다. migrate 프로필에서는
 * 암호화 키와 저장 adapter를 만들지 않으므로 Flyway만 스키마를 적용할 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
class RetainedTransactionConfiguration {
    @Bean
    fun retainedTransactionRepository(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): JdbcRetainedTransactionRepository = JdbcRetainedTransactionRepository(jdbc, cipher)

    @Bean
    fun retainedTransactionAdminService(
        repository: JdbcRetainedTransactionRepository,
        transaction: TransactionRunner,
    ): RetainedTransactionAdminService = RetainedTransactionAdminService(repository, transaction, Clock.systemUTC())

    @Bean
    @Profile("worker")
    fun retainedTransactionPurgePolicy(
        properties: kr.easydoc.infrastructure.document.RetentionProperties,
    ): RetainedTransactionPurgePolicy =
        RetainedTransactionPurgePolicy(
            enabled = properties.enabled,
            dryRun = properties.dryRun,
            batchSize = properties.batchSize,
        )

    @Bean
    @Profile("worker")
    fun purgeRetainedTransactionRecords(
        store: RetainedTransactionPurgeStore,
        transaction: TransactionRunner,
        policy: RetainedTransactionPurgePolicy,
    ): PurgeRetainedTransactionRecords = PurgeRetainedTransactionRecords(store, transaction, policy, Clock.systemUTC())
}

/** Schema-only startup holds no content keys and cannot perform a legal-archive mutation. */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrationRetainedTransactionConfiguration {
    @Bean
    fun migrationRetainedTransactionArchiver(): kr.easydoc.application.retention.RetainedTransactionArchiver =
        object : kr.easydoc.application.retention.RetainedTransactionArchiver {
            override fun archiveForUser(userId: java.util.UUID): Unit =
                error("Legal archive writes are disabled in the migrate profile")

            override fun archivePayment(
                order: kr.easydoc.application.subscription.BillingOrder,
                payment: kr.easydoc.application.subscription.TossPayment,
                previousRefundedAmount: Int,
                customerEmail: String?,
                consent: kr.easydoc.application.subscription.BillingSession?,
            ): Unit = error("Legal archive writes are disabled in the migrate profile")
        }
}

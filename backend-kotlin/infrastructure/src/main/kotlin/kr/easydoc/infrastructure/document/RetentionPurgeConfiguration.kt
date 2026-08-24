package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.LoggingRetentionPurgeObserver
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.RetentionPurgeObserver
import kr.easydoc.application.document.RetentionPurgePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient

/** 보존 만료 파기 설정. 바인딩 접두사는 `easydoc.retention`. */
@ConfigurationProperties(prefix = "easydoc.retention")
data class RetentionProperties(
    val enabled: Boolean = true,
    val dryRun: Boolean = false,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 100
    }
}

/** worker 만 만료 문서를 지운다. API 프로세스에는 스케줄과 이 빈이 없다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class RetentionPurgeConfiguration {
    @Bean
    fun expiredDocumentPurge(jdbcClient: JdbcClient): ExpiredDocumentPurge = JdbcExpiredDocumentPurge(jdbcClient)

    @Bean
    fun retentionPurgeObserver(): RetentionPurgeObserver = LoggingRetentionPurgeObserver()

    @Bean
    fun retentionPurgePolicy(properties: RetentionProperties): RetentionPurgePolicy =
        RetentionPurgePolicy(
            enabled = properties.enabled,
            dryRun = properties.dryRun,
            batchSize = properties.batchSize,
        )

    @Bean
    fun purgeExpiredDocuments(
        store: ExpiredDocumentPurge,
        transactionRunner: TransactionRunner,
        observer: RetentionPurgeObserver,
        policy: RetentionPurgePolicy,
    ): PurgeExpiredDocuments =
        PurgeExpiredDocuments(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
        )
}

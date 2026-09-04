package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.FeedbackCommentPurge
import kr.easydoc.application.document.FeedbackCommentPurgeObserver
import kr.easydoc.application.document.FeedbackCommentPurgePolicy
import kr.easydoc.application.document.LoggingFeedbackCommentPurgeObserver
import kr.easydoc.application.document.LoggingRetentionPurgeObserver
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
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

    @Bean
    fun feedbackCommentPurge(jdbcClient: JdbcClient): FeedbackCommentPurge = JdbcFeedbackCommentPurge(jdbcClient)

    @Bean
    fun feedbackCommentPurgeObserver(): FeedbackCommentPurgeObserver = LoggingFeedbackCommentPurgeObserver()

    /**
     * enabled·dryRun·batchSize 는 문서 파기와 같은 손잡이(`easydoc.retention`)를 그대로
     * 쓴다 — 둘 다 worker 의 같은 보존 파기 배치에 속한 단계라 별도 on/off·dry-run 표면을
     * 두지 않는다. 보존 일수만 `easydoc.feedback.comment-retention-days`로 갈린다 — 문서
     * 보존과 피드백 의견 보존은 서로 다른 기간을 가질 수 있는 별개의 정책이다.
     */
    @Bean
    fun feedbackCommentPurgePolicy(
        retention: RetentionProperties,
        feedback: FeedbackProperties,
    ): FeedbackCommentPurgePolicy =
        FeedbackCommentPurgePolicy(
            enabled = retention.enabled,
            dryRun = retention.dryRun,
            batchSize = retention.batchSize,
            retentionDays = feedback.commentRetentionDays,
        )

    @Bean
    fun purgeFeedbackComments(
        store: FeedbackCommentPurge,
        transactionRunner: TransactionRunner,
        observer: FeedbackCommentPurgeObserver,
        policy: FeedbackCommentPurgePolicy,
    ): PurgeFeedbackComments =
        PurgeFeedbackComments(
            store = store,
            transaction = transactionRunner,
            observer = observer,
            policy = policy,
        )
}

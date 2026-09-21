package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DefaultReviewHistoryAppender
import kr.easydoc.application.document.ReviewHistoryAppender
import kr.easydoc.application.document.ReviewHistoryRepository
import kr.easydoc.application.document.ReviewHistoryService
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/** R5 is opt-in; all public reads and mutation recording are inert while this flag is false. */
@ConfigurationProperties(prefix = "easydoc.review-history")
data class ReviewHistoryProperties(val enabled: Boolean = false)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewHistoryProperties::class)
@Profile("!$MIGRATE_PROFILE")
class ReviewHistoryConfiguration {
    @Bean
    fun reviewHistoryRepository(jdbcClient: JdbcClient): ReviewHistoryRepository =
        JdbcReviewHistoryRepository(jdbcClient)

    @Bean
    fun reviewHistoryAppender(
        properties: ReviewHistoryProperties,
        repository: ReviewHistoryRepository,
        cipher: ContentCipher,
    ): ReviewHistoryAppender =
        DefaultReviewHistoryAppender(
            enabled = properties.enabled,
            repository = repository,
            cipher = cipher,
            clock = Clock.systemUTC(),
        )

    @Bean
    fun reviewHistoryService(
        properties: ReviewHistoryProperties,
        conversions: ConversionRepository,
        repository: ReviewHistoryRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): ReviewHistoryService =
        ReviewHistoryService(
            enabled = properties.enabled,
            conversions = conversions,
            repository = repository,
            cipher = cipher,
            transaction = transactionRunner,
            clock = Clock.systemUTC(),
        )
}

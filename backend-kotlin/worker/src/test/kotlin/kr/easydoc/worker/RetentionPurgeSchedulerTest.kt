package kr.easydoc.worker

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.ExpiredDocumentPurge
import kr.easydoc.application.document.FeedbackCommentPurge
import kr.easydoc.application.document.FeedbackCommentPurgeObserver
import kr.easydoc.application.document.FeedbackCommentPurgePolicy
import kr.easydoc.application.document.FeedbackCommentPurgeResult
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
import kr.easydoc.application.document.RetentionPurgeObserver
import kr.easydoc.application.document.RetentionPurgePolicy
import kr.easydoc.application.document.RetentionPurgeResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * `RetentionPurgeScheduler` 는 Spring 도 DB 도 없이 대역으로 돈다 — 문서 파기와 피드백
 * 자유 의견 파기가 서로의 실패를 가리지 않는지가 이 테스트의 대상이다.
 */
class RetentionPurgeSchedulerTest {
    @Test
    @DisplayName("두 파기 단계가 모두 돈다")
    fun `문서 파기와 피드백 의견 파기가 둘 다 돈다`() {
        val documentStore = RecordingExpiredDocumentPurge()
        val feedbackStore = RecordingFeedbackCommentPurge()

        scheduler(documentStore, feedbackStore).run()

        assertThat(documentStore.calls).isEqualTo(1)
        assertThat(feedbackStore.calls).isEqualTo(1)
    }

    @Test
    @DisplayName("문서 파기가 실패해도 피드백 의견 파기는 그대로 돈다")
    fun `문서 파기 실패가 피드백 의견 파기를 막지 않는다`() {
        val documentStore = RecordingExpiredDocumentPurge(failing = true)
        val feedbackStore = RecordingFeedbackCommentPurge()

        scheduler(documentStore, feedbackStore).run()

        assertThat(documentStore.calls).isEqualTo(1)
        assertThat(feedbackStore.calls)
            .describedAs("문서 파기 단계의 예외가 다음 단계 실행을 막으면 안 된다")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("피드백 의견 파기가 실패해도 문서 파기는 이미 자기 몫을 끝냈다")
    fun `피드백 의견 파기 실패가 문서 파기를 가리지 않는다`() {
        val documentStore = RecordingExpiredDocumentPurge()
        val feedbackStore = RecordingFeedbackCommentPurge(failing = true)

        scheduler(documentStore, feedbackStore).run()

        assertThat(documentStore.calls)
            .describedAs("뒤에 도는 단계의 실패가 앞 단계가 이미 낸 결과를 무효로 만들면 안 된다")
            .isEqualTo(1)
        assertThat(feedbackStore.calls).isEqualTo(1)
    }

    @Test
    @DisplayName("두 단계가 모두 실패해도 스케줄 실행 자체는 예외를 던지지 않는다")
    fun `둘 다 실패해도 run 은 예외를 던지지 않는다`() {
        val documentStore = RecordingExpiredDocumentPurge(failing = true)
        val feedbackStore = RecordingFeedbackCommentPurge(failing = true)

        assertThatCode { scheduler(documentStore, feedbackStore).run() }.doesNotThrowAnyException()

        assertThat(documentStore.calls).isEqualTo(1)
        assertThat(feedbackStore.calls).isEqualTo(1)
    }

    @Test
    @DisplayName("실패 로그는 예외 메시지만 남기고 Throwable 자체는 로거에 넘기지 않는다")
    fun `실패 로그가 Throwable 을 싣지 않는다`() {
        val documentStore = RecordingExpiredDocumentPurge(failing = true)
        val feedbackStore = RecordingFeedbackCommentPurge()

        val events = captureLog { scheduler(documentStore, feedbackStore).run() }

        val failureEvent =
            events.singleOrNull { it.level == Level.ERROR }
                ?: error("실패 로그 한 건을 찾지 못했다 — 이 테스트가 재려는 로그 자체가 없다")
        assertThat(failureEvent.throwableProxy)
            .describedAs("Throwable 을 그대로 넘기면 스택트레이스가 함께 찍힌다 — 메시지만 남겨야 한다")
            .isNull()
        assertThat(failureEvent.formattedMessage)
            .describedAs("실패 사유는 여전히 읽혀야 한다 — 메시지 자체를 지우는 것은 답이 아니다")
            .contains("document purge boom")
    }

    /** [RetentionPurgeScheduler] 로거에 실행 중 찍힌 이벤트를 모은다. */
    private fun captureLog(block: () -> Unit): List<ILoggingEvent> {
        val logger =
            LoggerFactory.getLogger(RetentionPurgeScheduler::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.TRACE
        try {
            block()
        } finally {
            logger.level = previousLevel
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    private fun scheduler(
        documentStore: ExpiredDocumentPurge,
        feedbackStore: FeedbackCommentPurge,
    ): RetentionPurgeScheduler =
        RetentionPurgeScheduler(
            documentPurge =
                PurgeExpiredDocuments(
                    store = documentStore,
                    transaction = PassthroughTransactionRunner,
                    observer = NoopDocumentObserver,
                    policy = RetentionPurgePolicy(enabled = true, dryRun = false, batchSize = BATCH),
                ),
            feedbackCommentPurge =
                PurgeFeedbackComments(
                    store = feedbackStore,
                    transaction = PassthroughTransactionRunner,
                    observer = NoopFeedbackObserver,
                    policy =
                        FeedbackCommentPurgePolicy(
                            enabled = true,
                            dryRun = false,
                            batchSize = BATCH,
                            retentionDays = RETENTION_DAYS,
                        ),
                ),
        )

    private object PassthroughTransactionRunner : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private object NoopDocumentObserver : RetentionPurgeObserver {
        override fun record(result: RetentionPurgeResult) = Unit
    }

    private object NoopFeedbackObserver : FeedbackCommentPurgeObserver {
        override fun record(result: FeedbackCommentPurgeResult) = Unit
    }

    private class RecordingExpiredDocumentPurge(private val failing: Boolean = false) : ExpiredDocumentPurge {
        var calls: Int = 0
            private set

        override fun purge(
            dryRun: Boolean,
            limit: Int,
        ): RetentionPurgeResult {
            calls++
            if (failing) error("document purge boom")
            return RetentionPurgeResult(
                dryRun = dryRun,
                enabled = true,
                purgedDocuments = 0,
                purgedConversions = 0,
                skippedLeased = 0,
                documentIds = emptyList(),
            )
        }
    }

    private class RecordingFeedbackCommentPurge(private val failing: Boolean = false) : FeedbackCommentPurge {
        var calls: Int = 0
            private set

        override fun purge(
            dryRun: Boolean,
            limit: Int,
            retentionDays: Int,
        ): FeedbackCommentPurgeResult {
            calls++
            if (failing) error("feedback comment purge boom")
            return FeedbackCommentPurgeResult(dryRun = dryRun, enabled = true, purgedComments = 0)
        }
    }

    private companion object {
        const val BATCH: Int = 100
        const val RETENTION_DAYS: Int = 30
    }
}

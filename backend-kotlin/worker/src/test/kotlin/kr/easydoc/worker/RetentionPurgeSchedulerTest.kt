package kr.easydoc.worker

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

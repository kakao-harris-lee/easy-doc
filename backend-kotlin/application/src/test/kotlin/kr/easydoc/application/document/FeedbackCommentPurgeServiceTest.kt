package kr.easydoc.application.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 피드백 자유 의견 파기 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다.
 * `RetentionPurgeServiceTest`(문서 파기)와 같은 배치 흐름을 지표 하나(`purgedComments`)로 잰다.
 */
class FeedbackCommentPurgeServiceTest {
    @Test
    @DisplayName("꺼져 있으면 저장소를 부르지 않는다")
    fun `비활성이면 저장소를 건너뛴다`() {
        val world = World(enabled = false)

        val result = world.purge.run()

        assertThat(result.enabled).isFalse()
        assertThat(world.store.calls).isZero()
        assertThat(world.observer.seen).containsExactly(result)
        assertThat(world.transaction.started).isZero()
    }

    @Test
    @DisplayName("dry-run 이면 저장소에 dryRun=true 로 넘기고 트랜잭션 안에서 돈다")
    fun `dry-run 은 미리보기만 한다`() {
        val world = World(dryRun = true)
        world.store.next = FeedbackCommentPurgeResult(dryRun = true, enabled = true, purgedComments = 3)

        val result = world.purge.run()

        assertThat(result.dryRun).isTrue()
        assertThat(world.store.dryRuns).containsExactly(true)
        assertThat(world.transaction.committed).isEqualTo(1)
        assertThat(result.purgedComments).isEqualTo(3)
    }

    @Test
    @DisplayName("보존 일수 정책이 저장소 호출에 그대로 실린다")
    fun `보존 일수를 넘긴다`() {
        val world = World(retentionDays = RETENTION_DAYS)

        world.purge.run()

        assertThat(world.store.retentionDaysSeen).containsExactly(RETENTION_DAYS)
    }

    @Test
    @DisplayName("배치 크기 정책이 저장소 한도에 그대로 실린다")
    fun `배치 크기를 넘긴다`() {
        val world = World(batchSize = BATCH)

        world.purge.run()

        assertThat(world.store.limits).containsExactly(BATCH)
    }

    @Test
    @DisplayName("실제 파기는 짧은 배치가 나올 때까지 트랜잭션마다 반복한다")
    fun `대상량이 배치를 넘으면 끝까지 지운다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            FeedbackCommentPurgeResult(dryRun = false, enabled = true, purgedComments = BATCH),
            FeedbackCommentPurgeResult(dryRun = false, enabled = true, purgedComments = 1),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.purgedComments).isEqualTo(BATCH + 1)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("dry-run 은 한 배치만 미리보고 반복하지 않는다")
    fun `dry-run 은 한 배치만 본다`() {
        val world = World(dryRun = true, batchSize = BATCH)
        world.store.enqueue(
            FeedbackCommentPurgeResult(dryRun = true, enabled = true, purgedComments = BATCH),
            FeedbackCommentPurgeResult(dryRun = true, enabled = true, purgedComments = 1),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(1)
        assertThat(world.transaction.committed).isEqualTo(1)
        assertThat(result.purgedComments).isEqualTo(BATCH)
    }

    @Test
    @DisplayName("결과 문자열에 건수만 있고 의견 내용은 없다")
    fun `결과에 의견 내용이 없다`() {
        val world = World()
        world.store.next = FeedbackCommentPurgeResult(dryRun = false, enabled = true, purgedComments = 1)

        val result = world.purge.run()

        assertThat(result.toString()).doesNotContain("comment")
        assertThat(result.toString()).contains("purgedComments=1")
    }

    private class World(
        enabled: Boolean = true,
        dryRun: Boolean = false,
        batchSize: Int = DEFAULT_BATCH,
        retentionDays: Int = RETENTION_DAYS,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeFeedbackCommentPurge()
        val observer = RecordingFeedbackCommentObserver()
        val purge =
            PurgeFeedbackComments(
                store = store,
                transaction = transaction,
                observer = observer,
                policy =
                    FeedbackCommentPurgePolicy(
                        enabled = enabled,
                        dryRun = dryRun,
                        batchSize = batchSize,
                        retentionDays = retentionDays,
                    ),
            )
    }

    private class FakeFeedbackCommentPurge : FeedbackCommentPurge {
        var calls: Int = 0
            private set
        val dryRuns = mutableListOf<Boolean>()
        val limits = mutableListOf<Int>()
        val retentionDaysSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<FeedbackCommentPurgeResult>()
        var next: FeedbackCommentPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: FeedbackCommentPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            dryRun: Boolean,
            limit: Int,
            retentionDays: Int,
        ): FeedbackCommentPurgeResult {
            calls++
            dryRuns += dryRun
            limits += limit
            retentionDaysSeen += retentionDays
            return queued.removeFirstOrNull()
                ?: FeedbackCommentPurgeResult(dryRun = dryRun, enabled = true, purgedComments = 0)
        }
    }

    private class RecordingFeedbackCommentObserver : FeedbackCommentPurgeObserver {
        val seen = mutableListOf<FeedbackCommentPurgeResult>()

        override fun record(result: FeedbackCommentPurgeResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        const val RETENTION_DAYS: Int = 30
    }
}

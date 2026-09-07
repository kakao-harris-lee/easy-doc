package kr.easydoc.application.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 미검증 계정 파기 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다.
 * `FeedbackCommentPurgeServiceTest`(`application.document`)와 같은 배치 흐름을
 * 지표(`deleted`·`skippedWithDocuments`)로 잰다.
 */
class UnverifiedAccountPurgeServiceTest {
    @Test
    @DisplayName("꺼져 있으면 저장소를 부르지 않는다")
    fun `비활성이면 저장소를 건너뛴다`() {
        val world = World(enabled = false)

        val result = world.purge.run()

        assertThat(result.enabled).isFalse()
        assertThat(world.store.calls).isZero()
        assertThat(world.observer.seen).containsExactly(result)
        assertThat(world.transaction.committed).isZero()
    }

    @Test
    @DisplayName("실행 시각에서 TTL 을 뺀 시각을 저장소에 넘긴다")
    fun `TTL 을 기준시각으로 계산해 넘긴다`() {
        val world = World(ttl = Duration.ofHours(24), now = FIXED_NOW)

        world.purge.run()

        assertThat(world.store.createdBeforeSeen).containsExactly(FIXED_NOW.minus(Duration.ofHours(24)))
    }

    @Test
    @DisplayName("배치 크기 정책이 저장소 한도에 그대로 실린다")
    fun `배치 크기를 넘긴다`() {
        val world = World(batchSize = BATCH)

        world.purge.run()

        assertThat(world.store.batchSizesSeen).containsExactly(BATCH)
    }

    @Test
    @DisplayName("대상량이 배치를 넘으면 짧은 배치가 나올 때까지 트랜잭션마다 반복한다")
    fun `대상량이 배치를 넘으면 끝까지 지운다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            UnverifiedAccountPurgeResult(enabled = true, deleted = BATCH, skippedWithDocuments = 1),
            UnverifiedAccountPurgeResult(enabled = true, deleted = 1, skippedWithDocuments = 2),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.deleted).isEqualTo(BATCH + 1)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("문서를 가진 계정 건수는 누적하지 않고 마지막 배치 값을 쓴다")
    fun `건너뛴 건수는 마지막 배치 값이다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            UnverifiedAccountPurgeResult(enabled = true, deleted = BATCH, skippedWithDocuments = 5),
            UnverifiedAccountPurgeResult(enabled = true, deleted = 1, skippedWithDocuments = 5),
        )

        val result = world.purge.run()

        assertThat(result.skippedWithDocuments).isEqualTo(5)
    }

    @Test
    @DisplayName("결과 문자열에 건수만 있고 이메일·식별자는 없다")
    fun `결과에 이메일이 없다`() {
        val world = World()
        world.store.next = UnverifiedAccountPurgeResult(enabled = true, deleted = 1, skippedWithDocuments = 0)

        val result = world.purge.run()

        assertThat(result.toString()).doesNotContain("@")
        assertThat(result.toString()).contains("deleted=1")
    }

    private class World(
        enabled: Boolean = true,
        ttl: Duration = Duration.ofHours(24),
        batchSize: Int = DEFAULT_BATCH,
        now: Instant = FIXED_NOW,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeUnverifiedAccountPurge()
        val observer = RecordingUnverifiedAccountObserver()
        val purge =
            PurgeUnverifiedAccounts(
                store = store,
                transaction = transaction,
                observer = observer,
                policy = UnverifiedAccountPurgePolicy(enabled = enabled, ttl = ttl, batchSize = batchSize),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    private class RecordingTransactionRunner : TransactionRunner {
        var committed: Int = 0
            private set

        override fun <T> inTransaction(block: () -> T): T {
            val result = block()
            committed++
            return result
        }
    }

    private class FakeUnverifiedAccountPurge : UnverifiedAccountPurge {
        var calls: Int = 0
            private set
        val createdBeforeSeen = mutableListOf<Instant>()
        val batchSizesSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<UnverifiedAccountPurgeResult>()
        var next: UnverifiedAccountPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: UnverifiedAccountPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            createdBefore: Instant,
            batchSize: Int,
        ): UnverifiedAccountPurgeResult {
            calls++
            createdBeforeSeen += createdBefore
            batchSizesSeen += batchSize
            return queued.removeFirstOrNull()
                ?: UnverifiedAccountPurgeResult(enabled = true, deleted = 0, skippedWithDocuments = 0)
        }
    }

    private class RecordingUnverifiedAccountObserver : UnverifiedAccountPurgeObserver {
        val seen = mutableListOf<UnverifiedAccountPurgeResult>()

        override fun record(result: UnverifiedAccountPurgeResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-07T00:00:00Z")
    }
}

package kr.easydoc.application.credit

import kr.easydoc.application.auth.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 크레딧 주기 초기화 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다.
 * `UnverifiedAccountPurgeServiceTest`(`application.auth`)와 같은 배치 흐름을
 * 지표(`resetCount`)로 잰다.
 */
class CreditCycleResetTest {
    @Test
    @DisplayName("꺼져 있으면 저장소를 부르지 않는다")
    fun `비활성이면 저장소를 건너뛴다`() {
        val world = World(enabled = false)

        val result = world.reset.run()

        assertThat(result.enabled).isFalse()
        assertThat(world.store.calls).isZero()
        assertThat(world.observer.seen).containsExactly(result)
        assertThat(world.transaction.committed).isZero()
    }

    @Test
    @DisplayName("실행 시각을 그대로 저장소에 넘긴다")
    fun `실행 시각을 넘긴다`() {
        val world = World(now = FIXED_NOW)

        world.reset.run()

        assertThat(world.store.nowSeen).containsExactly(FIXED_NOW)
    }

    @Test
    @DisplayName("배치 크기 정책이 저장소 한도에 그대로 실린다")
    fun `배치 크기를 넘긴다`() {
        val world = World(batchSize = BATCH)

        world.reset.run()

        assertThat(world.store.batchSizesSeen).containsExactly(BATCH)
    }

    @Test
    @DisplayName("대상량이 배치를 넘으면 짧은 배치가 나올 때까지 트랜잭션마다 반복한다")
    fun `대상량이 배치를 넘으면 끝까지 초기화한다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            CreditCycleResetResult(enabled = true, resetCount = BATCH),
            CreditCycleResetResult(enabled = true, resetCount = 1),
        )

        val result = world.reset.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.resetCount).isEqualTo(BATCH + 1)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("결과 문자열에 건수만 있다")
    fun `결과에 건수만 있다`() {
        val world = World()
        world.store.next = CreditCycleResetResult(enabled = true, resetCount = 3)

        val result = world.reset.run()

        assertThat(result.toString()).contains("resetCount=3")
    }

    private class World(
        enabled: Boolean = true,
        batchSize: Int = DEFAULT_BATCH,
        now: Instant = FIXED_NOW,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeCreditCycleReset()
        val observer = RecordingCreditCycleResetObserver()
        val reset =
            ResetCreditCycles(
                store = store,
                transaction = transaction,
                observer = observer,
                policy = CreditCycleResetPolicy(enabled = enabled, batchSize = batchSize),
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

    private class FakeCreditCycleReset : CreditCycleReset {
        var calls: Int = 0
            private set
        val nowSeen = mutableListOf<Instant>()
        val batchSizesSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<CreditCycleResetResult>()
        var next: CreditCycleResetResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: CreditCycleResetResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun reset(
            now: Instant,
            batchSize: Int,
        ): CreditCycleResetResult {
            calls++
            nowSeen += now
            batchSizesSeen += batchSize
            return queued.removeFirstOrNull() ?: CreditCycleResetResult(enabled = true, resetCount = 0)
        }
    }

    private class RecordingCreditCycleResetObserver : CreditCycleResetObserver {
        val seen = mutableListOf<CreditCycleResetResult>()

        override fun record(result: CreditCycleResetResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
    }
}

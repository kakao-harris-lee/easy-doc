package kr.easydoc.application.credit

import kr.easydoc.application.auth.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset

/**
 * 가입 크레딧 원장 파기 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다.
 * `UnverifiedAccountPurgeServiceTest`(`application.auth`)와 같은 배치 흐름을 지표
 * (`deleted`)로 잰다.
 */
class SignupGrantRecordPurgeServiceTest {
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
    @DisplayName("실행 시각에서 TTL(Period)을 뺀 시각을 저장소에 넘긴다")
    fun `TTL 을 기준시각으로 계산해 넘긴다`() {
        val ttl = Period.ofYears(2)
        val world = World(ttl = ttl, now = FIXED_NOW)

        world.purge.run()

        val expected = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minus(ttl).toInstant()
        assertThat(world.store.grantedBeforeSeen).containsExactly(expected)
    }

    @Test
    @DisplayName("경계값(정확히 TTL 시점)의 기준시각 계산이 매번 같다 — 처분은 저장소 질의의 부등호가 정한다")
    fun `경계 계산이 결정적이다`() {
        val world = World(ttl = Period.ofYears(2), now = FIXED_NOW)

        world.purge.run()
        world.purge.run()

        assertThat(world.store.grantedBeforeSeen.toSet()).hasSize(1)
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
            SignupGrantRecordPurgeResult(enabled = true, deleted = BATCH),
            SignupGrantRecordPurgeResult(enabled = true, deleted = 1),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.deleted).isEqualTo(BATCH + 1)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("배치가 MAX_ROUNDS 를 넘으면 상한에서 멈춘다")
    fun `MAX_ROUNDS 상한이 있다`() {
        val world = World(batchSize = 1)
        world.store.alwaysFull = true

        assertThatIllegalStateException()
            .isThrownBy { world.purge.run() }
            .withMessageContaining("10000")
    }

    @Test
    @DisplayName("결과 문자열에 건수만 있고 이메일 해시는 없다")
    fun `결과에 이메일 해시가 없다`() {
        val world = World()
        val hash = "a".repeat(64)
        world.store.next = SignupGrantRecordPurgeResult(enabled = true, deleted = 1)

        val result = world.purge.run()

        assertThat(result.toString()).doesNotContain(hash)
        assertThat(result.toString()).contains("deleted=1")
    }

    private class World(
        enabled: Boolean = true,
        ttl: Period = Period.ofYears(2),
        batchSize: Int = DEFAULT_BATCH,
        now: Instant = FIXED_NOW,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeSignupGrantRecordPurge()
        val observer = RecordingSignupGrantRecordObserver()
        val purge =
            PurgeSignupGrantRecords(
                store = store,
                transaction = transaction,
                observer = observer,
                policy = SignupGrantRecordPurgePolicy(enabled = enabled, ttl = ttl, batchSize = batchSize),
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

    private class FakeSignupGrantRecordPurge : SignupGrantRecordPurge {
        var calls: Int = 0
            private set
        var alwaysFull: Boolean = false
        val grantedBeforeSeen = mutableListOf<Instant>()
        val batchSizesSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<SignupGrantRecordPurgeResult>()
        var next: SignupGrantRecordPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: SignupGrantRecordPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            grantedBefore: Instant,
            batchSize: Int,
        ): SignupGrantRecordPurgeResult {
            calls++
            grantedBeforeSeen += grantedBefore
            batchSizesSeen += batchSize
            if (alwaysFull) return SignupGrantRecordPurgeResult(enabled = true, deleted = batchSize)
            return queued.removeFirstOrNull() ?: SignupGrantRecordPurgeResult(enabled = true, deleted = 0)
        }
    }

    private class RecordingSignupGrantRecordObserver : SignupGrantRecordPurgeObserver {
        val seen = mutableListOf<SignupGrantRecordPurgeResult>()

        override fun record(result: SignupGrantRecordPurgeResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
    }
}

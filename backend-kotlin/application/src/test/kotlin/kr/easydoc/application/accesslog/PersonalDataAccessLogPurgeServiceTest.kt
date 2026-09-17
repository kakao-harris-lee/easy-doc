package kr.easydoc.application.accesslog

import kr.easydoc.application.auth.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset

/**
 * 접속기록(`personal_data_access_logs`, V22) 파기 유스케이스 — Spring 도 DB 도 없이
 * 대역으로 돈다. `SignupGrantRecordPurgeServiceTest`(`application.credit`)와 같은
 * 배치 흐름을 잰다. 계획 `docs/plans/2026-09-17-access-log-purge.md` §2·§4.
 */
class PersonalDataAccessLogPurgeServiceTest {
    @Test
    @DisplayName("꺼져 있으면 저장소를 부르지 않는다")
    fun `비활성이면 저장소를 건너뛴다`() {
        val world = World(enabled = false)

        val result = world.purge.run()

        assertThat(result.enabled).isFalse()
        assertThat(result.deleted).isZero()
        assertThat(world.store.calls).isZero()
        assertThat(world.observer.seen).containsExactly(result)
        assertThat(world.transaction.committed).isZero()
    }

    @Test
    @DisplayName("실행 시각에서 보관기간(Period)을 뺀 시각을 저장소에 넘긴다")
    fun `보관기간을 기준시각으로 계산해 넘긴다`() {
        val retention = Period.ofYears(1)
        val world = World(retention = retention, now = FIXED_NOW)

        world.purge.run()

        val expected = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minus(retention).toInstant()
        assertThat(world.store.accessedBeforeSeen).containsExactly(expected)
    }

    @Test
    @DisplayName("경계값(정확히 보관기간 시점)의 기준시각 계산이 매번 같다 — 처분은 저장소 질의의 부등호가 정한다")
    fun `경계 계산이 결정적이다`() {
        val world = World(retention = Period.ofYears(1), now = FIXED_NOW)

        world.purge.run()
        world.purge.run()

        assertThat(world.store.accessedBeforeSeen.toSet()).hasSize(1)
    }

    @Test
    @DisplayName("배치 크기 정책이 저장소 한도에 그대로 실린다")
    fun `배치 크기를 넘긴다`() {
        val world = World(batchSize = BATCH)

        world.purge.run()

        assertThat(world.store.batchSizesSeen).containsExactly(BATCH)
    }

    @Test
    @DisplayName("대상이 배치를 넘으면 짧은 배치가 나올 때까지 트랜잭션마다 반복한다")
    fun `배치를 넘으면 끝까지 지운다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            PersonalDataAccessLogPurgeResult(enabled = true, deleted = BATCH),
            PersonalDataAccessLogPurgeResult(enabled = true, deleted = 1),
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
    @DisplayName("결과 문자열에 건수만 있다")
    fun `결과에 건수만 있다`() {
        val world = World()
        world.store.next = PersonalDataAccessLogPurgeResult(enabled = true, deleted = 3)

        val result = world.purge.run()

        assertThat(result.toString()).isEqualTo("PersonalDataAccessLogPurgeResult(enabled=true, deleted=3)")
    }

    @Test
    @DisplayName("보관기간 1년 미만은 정책 생성이 거부한다")
    fun `보관기간 하한 위반은 거부한다`() {
        listOf(Period.ofMonths(11), Period.ofDays(364), Period.ZERO, Period.ofDays(-1)).forEach { retention ->
            assertThatIllegalArgumentException()
                .describedAs("retention=$retention 은 1년 미만이라 거부돼야 한다")
                .isThrownBy { PersonalDataAccessLogPurgePolicy(enabled = true, retention = retention, batchSize = 1) }
        }
    }

    @Test
    @DisplayName("보관기간 1년 이상은 정책 생성이 통과한다")
    fun `보관기간 하한을 만족하면 통과한다`() {
        listOf(Period.ofYears(1), Period.ofMonths(12), Period.ofYears(2), Period.ofDays(365)).forEach { retention ->
            PersonalDataAccessLogPurgePolicy(enabled = true, retention = retention, batchSize = 1)
        }
    }

    @Test
    @DisplayName("배치 크기가 1보다 작으면 정책 생성이 거부한다")
    fun `배치 크기 하한 위반은 거부한다`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                PersonalDataAccessLogPurgePolicy(enabled = true, retention = Period.ofYears(1), batchSize = 0)
            }
    }

    private class World(
        enabled: Boolean = true,
        retention: Period = Period.ofYears(1),
        batchSize: Int = DEFAULT_BATCH,
        now: Instant = FIXED_NOW,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakePersonalDataAccessLogPurge()
        val observer = RecordingPersonalDataAccessLogPurgeObserver()
        val purge =
            PurgePersonalDataAccessLogs(
                store = store,
                transaction = transaction,
                observer = observer,
                policy =
                    PersonalDataAccessLogPurgePolicy(enabled = enabled, retention = retention, batchSize = batchSize),
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

    private class FakePersonalDataAccessLogPurge : PersonalDataAccessLogPurge {
        var calls: Int = 0
            private set
        var alwaysFull: Boolean = false
        val accessedBeforeSeen = mutableListOf<Instant>()
        val batchSizesSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<PersonalDataAccessLogPurgeResult>()
        var next: PersonalDataAccessLogPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: PersonalDataAccessLogPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            accessedBefore: Instant,
            batchSize: Int,
        ): PersonalDataAccessLogPurgeResult {
            calls++
            accessedBeforeSeen += accessedBefore
            batchSizesSeen += batchSize
            if (alwaysFull) {
                return PersonalDataAccessLogPurgeResult(enabled = true, deleted = batchSize)
            }
            return queued.removeFirstOrNull() ?: PersonalDataAccessLogPurgeResult(enabled = true, deleted = 0)
        }
    }

    private class RecordingPersonalDataAccessLogPurgeObserver : PersonalDataAccessLogPurgeObserver {
        val seen = mutableListOf<PersonalDataAccessLogPurgeResult>()

        override fun record(result: PersonalDataAccessLogPurgeResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-17T00:00:00Z")
    }
}

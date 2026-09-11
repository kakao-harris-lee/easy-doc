package kr.easydoc.application.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 만료 인증 아티팩트(이메일 인증 코드·비밀번호 재설정 코드·OAuth state) 파기 유스케이스 —
 * Spring 도 DB 도 없이 대역으로 돈다. `UnverifiedAccountPurgeServiceTest`와 같은 배치
 * 흐름을 표별 삭제 건수로 잰다.
 */
class ExpiredAuthArtifactPurgeServiceTest {
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
    @DisplayName("실행 시각에서 보존기간을 뺀 시각을 저장소에 넘긴다")
    fun `보존기간을 기준시각으로 계산해 넘긴다`() {
        val world = World(retention = Duration.ofHours(24), now = FIXED_NOW)

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
    @DisplayName("세 표 중 하나라도 배치 한도만큼 지워지면 짧은 배치가 나올 때까지 반복한다")
    fun `한 표라도 배치를 넘으면 끝까지 지운다`() {
        val world = World(batchSize = BATCH)
        world.store.enqueue(
            // oauth_states 만 배치 한도에 닿았다 — 나머지 두 표는 이미 짧은 배치다.
            ExpiredAuthArtifactPurgeResult(
                enabled = true,
                emailVerificationCodesDeleted = 0,
                passwordResetCodesDeleted = 0,
                oauthStatesDeleted = BATCH,
            ),
            ExpiredAuthArtifactPurgeResult(
                enabled = true,
                emailVerificationCodesDeleted = 0,
                passwordResetCodesDeleted = 0,
                oauthStatesDeleted = 1,
            ),
        )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(2)
        assertThat(world.transaction.committed).isEqualTo(2)
        assertThat(result.oauthStatesDeleted).isEqualTo(BATCH + 1)
        assertThat(world.observer.seen).containsExactly(result)
    }

    @Test
    @DisplayName("배치 미만이면 표별 삭제 건수를 그대로 합쳐 한 번만 돈다")
    fun `배치 미만이면 한 번만 돈다`() {
        val world = World()
        world.store.next =
            ExpiredAuthArtifactPurgeResult(
                enabled = true,
                emailVerificationCodesDeleted = 1,
                passwordResetCodesDeleted = 2,
                oauthStatesDeleted = 3,
            )

        val result = world.purge.run()

        assertThat(world.store.calls).isEqualTo(1)
        assertThat(result.emailVerificationCodesDeleted).isEqualTo(1)
        assertThat(result.passwordResetCodesDeleted).isEqualTo(2)
        assertThat(result.oauthStatesDeleted).isEqualTo(3)
    }

    @Test
    @DisplayName("결과 문자열에 표별 건수만 있고 해시·salt·state·nonce는 없다")
    fun `결과에 비밀 값이 없다`() {
        val world = World()
        world.store.next =
            ExpiredAuthArtifactPurgeResult(
                enabled = true,
                emailVerificationCodesDeleted = 1,
                passwordResetCodesDeleted = 0,
                oauthStatesDeleted = 0,
            )

        val result = world.purge.run()

        assertThat(result.toString()).contains("emailVerificationCodesDeleted=1")
        assertThat(result.toString()).doesNotContain("hash", "salt", "state", "nonce")
    }

    private class World(
        enabled: Boolean = true,
        retention: Duration = Duration.ofHours(24),
        batchSize: Int = DEFAULT_BATCH,
        now: Instant = FIXED_NOW,
    ) {
        val transaction = RecordingTransactionRunner()
        val store = FakeExpiredAuthArtifactPurge()
        val observer = RecordingExpiredAuthArtifactObserver()
        val purge =
            PurgeExpiredAuthArtifacts(
                store = store,
                transaction = transaction,
                observer = observer,
                policy =
                    ExpiredAuthArtifactPurgePolicy(enabled = enabled, retention = retention, batchSize = batchSize),
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

    private class FakeExpiredAuthArtifactPurge : ExpiredAuthArtifactPurge {
        var calls: Int = 0
            private set
        val createdBeforeSeen = mutableListOf<Instant>()
        val batchSizesSeen = mutableListOf<Int>()
        private val queued = ArrayDeque<ExpiredAuthArtifactPurgeResult>()
        var next: ExpiredAuthArtifactPurgeResult?
            get() = queued.firstOrNull()
            set(value) {
                queued.clear()
                if (value != null) {
                    queued.addLast(value)
                }
            }

        fun enqueue(vararg results: ExpiredAuthArtifactPurgeResult) {
            queued.clear()
            queued.addAll(results)
        }

        override fun purge(
            createdBefore: Instant,
            batchSize: Int,
        ): ExpiredAuthArtifactPurgeResult {
            calls++
            createdBeforeSeen += createdBefore
            batchSizesSeen += batchSize
            return queued.removeFirstOrNull()
                ?: ExpiredAuthArtifactPurgeResult(
                    enabled = true,
                    emailVerificationCodesDeleted = 0,
                    passwordResetCodesDeleted = 0,
                    oauthStatesDeleted = 0,
                )
        }
    }

    private class RecordingExpiredAuthArtifactObserver : ExpiredAuthArtifactPurgeObserver {
        val seen = mutableListOf<ExpiredAuthArtifactPurgeResult>()

        override fun record(result: ExpiredAuthArtifactPurgeResult) {
            seen += result
        }
    }

    private companion object {
        const val BATCH: Int = 2
        const val DEFAULT_BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
    }
}

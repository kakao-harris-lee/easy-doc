package kr.easydoc.infrastructure.queue

import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.application.conversion.ConversionJobLeasePort
import kr.easydoc.application.conversion.ConversionWorkerPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** heartbeat 한 번의 예외가 이후 연장을 죽이면 안 된다. */
class ScheduledConversionJobHeartbeatTest {
    @Test
    @DisplayName("한 번의 연장 실패 뒤에도 heartbeat 가 계속 돈다")
    fun `연장 예외가 후속 실행을 막지 않는다`() {
        val ticks = CountDownLatch(3)
        val leases = FlakyLeases(ticks)
        val heartbeat =
            ScheduledConversionJobHeartbeat(
                leases = leases,
                policy =
                    ConversionWorkerPolicy(
                        owner = "worker-a",
                        leaseDuration = Duration.ofMinutes(2),
                        maxAttempts = 3,
                        retryBackoff = Duration.ofSeconds(1),
                    ),
                interval = Duration.ofMillis(20),
            )
        val lease = ConversionJobLease(UUID.randomUUID(), "worker-a", 1)

        heartbeat.whileHeld(lease) {
            assertThat(ticks.await(2, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(leases.renewals.get()).isGreaterThanOrEqualTo(3)
    }

    private class FlakyLeases(private val ticks: CountDownLatch) : ConversionJobLeasePort {
        val renewals = AtomicInteger()

        override fun acquire(
            owner: String,
            leaseDuration: Duration,
            maxAttempts: Int,
        ): ConversionAcquire = ConversionAcquire.Empty

        override fun renew(
            lease: ConversionJobLease,
            leaseDuration: Duration,
        ): Boolean {
            val n = renewals.incrementAndGet()
            ticks.countDown()
            if (n == 1) {
                throw TransientRenewFailure()
            }
            return true
        }

        override fun lockIfHeld(lease: ConversionJobLease): Boolean = false

        override fun complete(lease: ConversionJobLease): Boolean = false

        override fun retry(
            lease: ConversionJobLease,
            delay: Duration,
        ): Boolean = false

        override fun fail(lease: ConversionJobLease): Boolean = false
    }

    private class TransientRenewFailure : RuntimeException("일시적인 DB 오류")
}

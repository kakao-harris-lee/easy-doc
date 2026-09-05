package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.exceptions.RateLimitedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** 사전 조회 남용 한도의 인스턴스별 구현 (P0-5 조각 4). */
class InMemorySlidingWindowLookupRateLimiterTest {
    @Test
    @DisplayName("한도 안의 호출은 통과하고, 한도를 넘으면 RateLimitedException 이다")
    fun `한도를 넘으면 거절한다`() {
        val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
        val limiter = InMemorySlidingWindowLookupRateLimiter(limitPerMinute = 3, clock = clock)
        val userId = UUID.randomUUID()

        repeat(3) { limiter.checkAndRecord(userId) }

        assertThatThrownBy { limiter.checkAndRecord(userId) }
            .isInstanceOf(RateLimitedException::class.java)
    }

    @Test
    @DisplayName("한도를 넘은 예외의 Retry-After 는 1 이상이고, 창이 지나면 다시 통과한다")
    fun `창이 지나면 다시 허용한다`() {
        val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
        val limiter = InMemorySlidingWindowLookupRateLimiter(limitPerMinute = 1, clock = clock)
        val userId = UUID.randomUUID()

        limiter.checkAndRecord(userId)
        val thrown = catchThrowable { limiter.checkAndRecord(userId) } as RateLimitedException
        assertThat(thrown.retryAfterSeconds).isGreaterThanOrEqualTo(1)

        clock.advance(Duration.ofSeconds(61))

        limiter.checkAndRecord(userId)
    }

    @Test
    @DisplayName("서로 다른 사용자는 서로의 한도에 영향을 주지 않는다")
    fun `사용자별로 독립이다`() {
        val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
        val limiter = InMemorySlidingWindowLookupRateLimiter(limitPerMinute = 1, clock = clock)

        limiter.checkAndRecord(UUID.randomUUID())

        limiter.checkAndRecord(UUID.randomUUID())
    }
}

/** `JdbcVerificationCodeStoreTest` 의 `VerificationCodeClock` 과 같은 필요다. */
private class MutableClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

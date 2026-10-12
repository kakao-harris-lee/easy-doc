package kr.easydoc.api

import kr.easydoc.api.subscription.TossBillingCallbackRateLimiter
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.RateLimitedException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TossBillingCallbackRateLimiterTest {
    @Test
    fun `independent billing states do not share a quota behind the same proxy chain`() {
        val limiter = TossBillingCallbackRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        repeat(30) { limiter.check("A".repeat(43)) }
        assertThatThrownBy { limiter.check("A".repeat(43)) }
            .isInstanceOf(RateLimitedException::class.java)
        repeat(30) { limiter.check("B".repeat(43)) }
    }

    @Test
    fun `malformed states never consume a valid billing session quota`() {
        val limiter = TossBillingCallbackRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        repeat(31) {
            assertThatThrownBy { limiter.check("invalid") }.isInstanceOf(NotFoundException::class.java)
        }
        repeat(30) { limiter.check("A".repeat(43)) }
    }
}

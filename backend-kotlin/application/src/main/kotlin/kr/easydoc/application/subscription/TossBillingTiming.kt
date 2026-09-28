package kr.easydoc.application.subscription

import java.time.Duration

/** Runtime timing policy for the Toss test billing workflow. */
data class TossBillingTiming(
    val sessionTtl: Duration = DEFAULT_SESSION_TTL,
    val retryInterval: Duration = DEFAULT_RETRY_INTERVAL,
    val retryWindow: Duration = DEFAULT_RETRY_WINDOW,
) {
    init {
        require(sessionTtl.isPositive()) { "결제 세션 TTL은 0보다 커야 합니다" }
        require(retryInterval.isPositive()) { "결제 재시도 간격은 0보다 커야 합니다" }
        require(retryWindow.isPositive()) { "결제 재시도 기간은 0보다 커야 합니다" }
    }

    companion object {
        val DEFAULT_SESSION_TTL: Duration = Duration.ofSeconds(600)
        val DEFAULT_RETRY_INTERVAL: Duration = Duration.ofSeconds(30)
        val DEFAULT_RETRY_WINDOW: Duration = Duration.ofDays(14)
    }
}

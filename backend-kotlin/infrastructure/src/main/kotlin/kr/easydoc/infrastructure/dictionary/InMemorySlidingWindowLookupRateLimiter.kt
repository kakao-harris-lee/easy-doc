package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.dictionary.LookupRateLimiter
import kr.easydoc.core.exceptions.RateLimitedException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 사전 조회 남용 한도의 인스턴스별(process-local) 구현 (P0-5 조각 4, 계획 §3.4).
 *
 * 사용자마다 최근 60초 안의 호출 시각을 든다. [limitPerMinute] 를 넘으면
 * [RateLimitedException] — 재시도까지 남은 시간은 창 안에서 **가장 오래된** 호출이
 * 60초를 채우는 시점까지다.
 *
 * DB 를 쓰지 않는다 — 계획이 명시적으로 "프로세스 내 카운터"를 요구했다(다중 인스턴스에서는
 * 인스턴스마다 별도 한도가 된다는 뜻이고, 계약도 그렇게 적는다). [Clock] 은 조립 지점
 * (`DictionaryConfiguration`)이 `Clock.systemUTC()` 를 직접 넘긴다 — 다른 인증 어댑터
 * (`JdbcVerificationCodeStore` 등)와 같은 관례.
 */
class InMemorySlidingWindowLookupRateLimiter(
    private val limitPerMinute: Int,
    private val clock: Clock,
) : LookupRateLimiter {
    private val callsByUser = ConcurrentHashMap<UUID, ArrayDeque<Instant>>()

    override fun checkAndRecord(userId: UUID) {
        val now = clock.instant()
        val windowStart = now.minus(WINDOW)
        val calls = callsByUser.computeIfAbsent(userId) { ArrayDeque() }

        synchronized(calls) {
            while (calls.isNotEmpty() && calls.first().isBefore(windowStart)) {
                calls.removeFirst()
            }
            if (calls.size >= limitPerMinute) {
                val retryAfter = Duration.between(now, calls.first().plus(WINDOW)).seconds.coerceAtLeast(1)
                throw RateLimitedException(RATE_LIMITED_MESSAGE, retryAfter)
            }
            calls.addLast(now)
        }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)

        /** 계약 `TooManyRequests.examples.rate_limited` 와 같은 값 — 기존 429 관례를 재사용한다. */
        const val RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해주세요"
    }
}

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
            // 드레인 전에 이미 뭔가 있었는지를 먼저 본다 — `computeIfAbsent` 가 이번 호출에서 막
            // 새로 만든 빈 deque(이 사용자의 첫 호출)와, 예전 호출들이 전부 창 밖으로 밀려나
            // 방금 비어버린 deque(진짜 idle 복귀)를 구분해야 한다. 구분하지 않으면 첫 호출마다
            // 아래 청소가 걸려 map 에 아무것도 안 남고, 그 다음 호출도 늘 "첫 호출"처럼 보여
            // 한도 자체가 무력화된다.
            val hadEntriesBeforeDrain = calls.isNotEmpty()
            while (calls.isNotEmpty() && calls.first().isBefore(windowStart)) {
                calls.removeFirst()
            }
            if (hadEntriesBeforeDrain && calls.isEmpty()) {
                // idle 사용자 청소 — 갖고 있던 호출이 전부 창 밖으로 밀려났으면 map 항목 자체를
                // 지운다. value-equality 로 가드한다(`ConcurrentHashMap.remove(key, value)`):
                // `ArrayDeque` 는 `equals` 를 오버라이드하지 않아 참조 동일성으로 비교되므로,
                // 그사이 다른 스레드가 이 `calls` 인스턴스에 이미 새 항목을 넣었다면(그래도 여전히
                // map 의 값이 이 참조라면) 그 갱신을 지워버릴 위험이 남는다 — 드물게 그 경합이
                // 나면 다음 호출이 `computeIfAbsent` 로 새 deque 를 다시 만들 뿐이라 benign
                // re-create 로 받아들인다.
                callsByUser.remove(userId, calls)
            }
            if (calls.size >= limitPerMinute) {
                val retryAfter = Duration.between(now, calls.first().plus(WINDOW)).seconds.coerceAtLeast(1)
                throw RateLimitedException(RATE_LIMITED_MESSAGE, retryAfter)
            }
            calls.addLast(now)
        }
    }

    /** 테스트 전용 — 현재 map 에 남아 있는(청소되지 않은) 사용자 수. */
    internal fun trackedUsers(): Int = callsByUser.size

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)

        /** 계약 `TooManyRequests.examples.rate_limited` 와 같은 값 — 기존 429 관례를 재사용한다. */
        const val RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해주세요"
    }
}

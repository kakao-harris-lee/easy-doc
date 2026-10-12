package kr.easydoc.api.subscription

import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.RateLimitedException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Per-state guard, independent of the number of reverse proxies. Edge nginx limits client IPs. */
class TossBillingCallbackRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val limit: Int = 30,
    private val window: Duration = Duration.ofMinutes(1),
    private val maxEntries: Int = 10_000,
) {
    private data class Entry(
        var startedAt: Instant,
        var count: Int,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun check(state: String) {
        if (!STATE_PATTERN.matches(state)) throw NotFoundException("카드 등록 복구 자료를 찾을 수 없습니다")
        val key =
            MessageDigest
                .getInstance("SHA-256")
                .digest(state.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        val now = clock.instant()
        synchronized(entries) {
            entries.entries.removeIf { (_, entry) -> !now.isBefore(entry.startedAt.plus(window)) }
            if (entries.size >= maxEntries && !entries.containsKey(key)) {
                // Random unknown states must not grow this process's memory without bound.
                val iterator = entries.entries.iterator()
                iterator.next()
                iterator.remove()
            }
            val entry = entries[key]
            if (entry == null || !now.isBefore(entry.startedAt.plus(window))) {
                entries[key] = Entry(now, 1)
                return
            }
            if (entry.count >= limit) {
                throw RateLimitedException("카드 등록 결과 요청이 너무 많습니다. 잠시 후 다시 시도하세요", RETRY_AFTER_SECONDS)
            }
            entry.count += 1
        }
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val RETRY_AFTER_SECONDS = 60L
        val STATE_PATTERN = Regex("[A-Za-z0-9_-]{43}")
    }
}

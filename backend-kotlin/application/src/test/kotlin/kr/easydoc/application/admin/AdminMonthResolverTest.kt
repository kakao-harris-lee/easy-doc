package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.InvalidInputException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AdminMonthResolverTest {
    private val resolver =
        AdminMonthResolver(
            ZoneId.of("Asia/Seoul"),
            Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneId.of("UTC")),
        )

    @Test fun `Korean month uses exclusive following month boundary including leap day`() {
        val result = resolver.resolve("2024-02")
        assertEquals(Instant.parse("2024-01-31T15:00:00Z"), result.from)
        assertEquals(Instant.parse("2024-02-29T15:00:00Z"), result.until)
    }

    @Test fun `invalid and future months rejected`() {
        listOf("2026-11", "2026-13", "2026-1", "bad", "0000-01").forEach {
            assertThrows(InvalidInputException::class.java) { resolver.resolve(it) }
        }
    }

    @Test fun `history omits future months and handles year rollover`() {
        assertEquals(10, resolver.months(2026).size)
        assertEquals(12, resolver.months(2025).size)
        assertEquals(Instant.parse("2025-12-31T15:00:00Z"), resolver.resolve("2025-12").until)
    }
}

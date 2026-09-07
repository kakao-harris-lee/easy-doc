package kr.easydoc.application.usage

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.llm.LlmCallPurpose
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** `UsageQueryService` — 기간 기본값·검증, 404 판정. */
class UsageQueryServiceTest {
    private val zone = ZoneId.of("Asia/Seoul")

    // 2026-09-07 12:00 KST 로 고정 — 「오늘」·「이번 달 1일」 기본값을 계산하는 기준.
    private val clock = Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("UTC"))

    private val owner = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val emptyUsage =
        WorkspaceUsage(
            documents = 0,
            characters = 0,
            credits = 0,
            llmCalls = 0,
            inputTokens = 0,
            outputTokens = 0,
            estimatedCostUsd = null,
            costUnknownCalls = 0,
            byPurpose = emptyList(),
        )

    private class FakeUsageReadRepository(private val result: WorkspaceUsage?) : UsageReadRepository {
        var lastFrom: Instant? = null
        var lastToExclusive: Instant? = null

        override fun aggregate(
            ownerId: UUID,
            workspaceId: UUID,
            from: Instant,
            toExclusive: Instant,
        ): WorkspaceUsage? {
            lastFrom = from
            lastToExclusive = toExclusive
            return result
        }
    }

    @Test
    @DisplayName("from·to를 생략하면 이번 달 1일부터 오늘까지다")
    fun `기본 기간은 이번 달 1일부터 오늘까지다`() {
        val repository = FakeUsageReadRepository(emptyUsage)
        val service = UsageQueryService(repository, zone, clock)

        service.usageOf(owner, workspaceId, from = null, to = null)

        // 2026-09-01 00:00 KST 부터 2026-09-08 00:00 KST(=09-07 다음날 자정, 배타 상한) 까지.
        assertThat(repository.lastFrom).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"))
        assertThat(repository.lastToExclusive).isEqualTo(Instant.parse("2026-09-07T15:00:00Z"))
    }

    @Test
    @DisplayName("from·to를 주면 그 날짜의 zone 자정 경계로 변환한다 — to는 포함, 다음날 자정은 배타")
    fun `명시한 기간이 자정 경계로 변환된다`() {
        val repository = FakeUsageReadRepository(emptyUsage)
        val service = UsageQueryService(repository, zone, clock)

        service.usageOf(owner, workspaceId, from = "2026-01-01", to = "2026-01-31")

        assertThat(repository.lastFrom).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"))
        assertThat(repository.lastToExclusive).isEqualTo(Instant.parse("2026-01-31T15:00:00Z"))
    }

    @Test
    @DisplayName("to가 from보다 앞이면 422(InvalidInputException)")
    fun `to가 from보다 앞이면 예외`() {
        val service = UsageQueryService(FakeUsageReadRepository(emptyUsage), zone, clock)

        assertThatThrownBy { service.usageOf(owner, workspaceId, from = "2026-02-01", to = "2026-01-01") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(USAGE_TO_BEFORE_FROM_MESSAGE)
    }

    @Test
    @DisplayName("366일을 넘는 구간은 422")
    fun `366일 초과 구간은 예외`() {
        val service = UsageQueryService(FakeUsageReadRepository(emptyUsage), zone, clock)

        // 포함 상한 기준 367일 (2025-01-01 ~ 2026-01-02) — 366 초과.
        assertThatThrownBy { service.usageOf(owner, workspaceId, from = "2025-01-01", to = "2026-01-02") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(USAGE_RANGE_TOO_WIDE_MESSAGE)
    }

    @Test
    @DisplayName("정확히 366일 구간은 통과한다 — 경계값")
    fun `366일 구간은 허용된다`() {
        val repository = FakeUsageReadRepository(emptyUsage)
        val service = UsageQueryService(repository, zone, clock)

        service.usageOf(owner, workspaceId, from = "2025-01-01", to = "2026-01-01")

        assertThat(repository.lastFrom).isNotNull()
    }

    /**
     * `DateTimeFormatter.ISO_LOCAL_DATE`는 엄격하다 — 자리 수가 다르거나(`2026-9-7`),
     * 구분자가 다르거나(`2026/01/01`), 그 달에 없는 날짜(`2026-09-31`, 9월은 30일까지다)를
     * 전부 거절한다.
     */
    @ParameterizedTest(name = "「{0}」은 형식 오류다")
    @ValueSource(strings = ["2026-9-7", "2026-09-31", "2026/01/01"])
    @DisplayName("형식이 어긋난 날짜는 422")
    fun `형식 오류는 예외`(malformed: String) {
        val service = UsageQueryService(FakeUsageReadRepository(emptyUsage), zone, clock)

        assertThatThrownBy { service.usageOf(owner, workspaceId, from = malformed, to = null) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(MALFORMED_USAGE_DATE_MESSAGE)
    }

    @Test
    @DisplayName("저장소가 null이면(소유가 아니거나 없음) 404")
    fun `소유가 아니면 404`() {
        val service = UsageQueryService(FakeUsageReadRepository(null), zone, clock)

        assertThatThrownBy { service.usageOf(owner, workspaceId, from = null, to = null) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    @DisplayName("byPurpose·비용 미상 건수를 그대로 돌려준다")
    fun `집계 값을 그대로 통과시킨다`() {
        val usage =
            WorkspaceUsage(
                documents = 3,
                characters = 3000,
                credits = 3,
                llmCalls = 5,
                inputTokens = 100,
                outputTokens = 200,
                estimatedCostUsd = BigDecimal("0.012345"),
                costUnknownCalls = 1,
                byPurpose =
                    listOf(
                        PurposeUsage(LlmCallPurpose.CONVERT, 3, 60, 120, BigDecimal("0.01")),
                        PurposeUsage(LlmCallPurpose.REPAIR, 2, 40, 80, null),
                    ),
            )
        val service = UsageQueryService(FakeUsageReadRepository(usage), zone, clock)

        val result = service.usageOf(owner, workspaceId, from = null, to = null)

        assertThat(result).isEqualTo(usage)
    }
}

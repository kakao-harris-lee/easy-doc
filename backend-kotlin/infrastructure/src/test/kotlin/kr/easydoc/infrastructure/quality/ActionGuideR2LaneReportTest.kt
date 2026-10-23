package kr.easydoc.infrastructure.quality

import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.infrastructure.llm.AnthropicTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

class ActionGuideR2LaneReportTest {
    @Test
    fun `반복별 결과와 20건 탐색 p95를 본문 없이 남긴다`() {
        val journal = LaneJournal(retryBudget = 0)
        val spendLimit = LaneSpendLimit(BigDecimal.ONE, BigDecimal.TEN, BigDecimal("50"))
        val report =
            ActionGuideR2LaneReport(
                description = "provider=openai settings=gpt-6-astra",
                journal = journal,
                spendLimit = spendLimit,
                maxUsd = BigDecimal.ONE,
                maxCalls = 20,
                cohort = "development",
                plannedReservationUsd = BigDecimal("0.500000"),
            )
        val candidate =
            ActionGuideCandidate(
                schemaVersion = 1,
                sections =
                    ActionGuideSectionKind.entries.map { kind ->
                        ActionGuideSection(kind, ActionGuideSectionStatus.NOT_IN_SOURCE, emptyList())
                    },
            )

        repeat(20) { index ->
            report.record(
                documentId = "070",
                run = index + 1,
                result = ActionGuideRunResult.Valid(record(index + 1L), candidate),
                elapsed = Duration.ofMillis(index + 1L),
            )
        }

        val rendered = report.render()

        assertThat(rendered)
            .contains("cohort=development document=070 run=1 outcome=valid latency_ms=1")
            .contains("cohort=development document=070 run=20 outcome=valid latency_ms=20")
            .contains("p95 19ms (n=20, 탐색치)")
            .contains("중앙값 11ms")
            .contains("사전 예약 상계 US$0.500000")
            .doesNotContain("candidate")
    }

    @Test
    @DisplayName("지출 줄은 정산한 실제 비용과 아직 붙잡고 있는 예약을 갈라 적는다")
    fun `지출 줄에 정산액과 미정산 예약을 함께 적는다`() {
        val spendLimit = LaneSpendLimit(CAP_USD, BigDecimal.TEN, BigDecimal("50"))
        val prompt = AnthropicTestSupport.conversionPrompt()
        // 두 번 예약하고 한 번만 정산한다 — 실패한 시도의 예약이 남은 유료 레인의 상태다.
        val reservation = spendLimit.reserve(prompt, LlmOptions())
        spendLimit.reserve(prompt, LlmOptions())
        spendLimit.settle(reservation, SETTLED_USD)
        val report =
            ActionGuideR2LaneReport(
                description = "provider=openai settings=gpt-6-astra",
                journal = LaneJournal(retryBudget = 0),
                spendLimit = spendLimit,
                maxUsd = CAP_USD,
                maxCalls = 20,
            )

        assertThat(report.render())
            .contains(
                "지출 US$${SETTLED_USD.add(reservation).setScale(6, RoundingMode.HALF_UP)} " +
                    "(정산 US$0.250000 · " +
                    "미정산 예약 US$${reservation.setScale(6, RoundingMode.HALF_UP)}) · " +
                    "상한 US$10.000000",
            )
    }

    private fun record(latencyMs: Long): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ACTION_GUIDE,
            provider = "fake",
            model = "fake-action-guide",
            inputTokens = 10,
            outputTokens = 20,
            latencyMs = latencyMs,
            estimatedCostUsd = null,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = 10,
            calledAt = java.time.Instant.EPOCH,
        )

    private companion object {
        /** 예약 두 건이 들어가는 넉넉한 상한 — 이 테스트가 보는 것은 하드 스톱이 아니라 지출 줄이다. */
        val CAP_USD: BigDecimal = BigDecimal("10")

        /** 정산한 실제 비용. 리포트가 이 값을 「정산」 자리에 적어야 한다. */
        val SETTLED_USD: BigDecimal = BigDecimal("0.250000")
    }
}

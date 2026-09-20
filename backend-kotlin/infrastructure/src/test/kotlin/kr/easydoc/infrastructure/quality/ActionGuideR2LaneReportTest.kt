package kr.easydoc.infrastructure.quality

import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
}

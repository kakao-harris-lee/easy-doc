package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.suggestion.isNumericOnlySource
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.segment.splitUnits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * P0-1 그림 평가 레인의 **유료 호출 전에 정해지는 규칙**을 가짜로 시험한다.
 *
 * 예산 선검사와 연속 실패 중단은 유료 호출을 **시작할지** 정하는 자리라, 실제 호출로만
 * 확인할 수 있으면 확인할 방법이 없다([GoldenLlmLaneTest] 가 선택 규칙을 유료 호출 없이
 * 시험하는 것과 같은 판단). 이 테스트에는 `llm` 태그가 없어 평소 `test` 레인에서 돈다.
 */
class IllustrationEvaluationLaneTest {
    @Test
    fun `상한을 넘길 호출은 시작하지 않는다`() {
        val budget = budgetOf(max = "1.00", textWorstCase = "0.60")

        budget.settle(budget.reserveText(PROMPT, OPTIONS), BigDecimal("0.60"))

        // 0.60 을 이미 썼으니 다음 호출의 최악 비용 0.60 을 더하면 상한 1.00 을 넘는다.
        assertThatThrownBy { budget.reserveText(PROMPT, OPTIONS) }
            .isInstanceOf(IllustrationLaneBudgetStop::class.java)
        assertThat(budget.spentUsd).isEqualByComparingTo("0.60")
    }

    @Test
    fun `상한 안이면 호출을 시작한다`() {
        val budget = budgetOf(max = "1.00", textWorstCase = "0.60")

        assertThat(budget.reserveText(PROMPT, OPTIONS)).isEqualByComparingTo("0.60")
    }

    @Test
    fun `이미지는 장당 최악 비용으로 선검사한다`() {
        val budget = budgetOf(max = "0.15", textWorstCase = "0.01")

        assertThat(budget.reserveImage()).isEqualByComparingTo(ILLUSTRATION_IMAGE_WORST_CASE_USD)
        budget.settle(ILLUSTRATION_IMAGE_WORST_CASE_USD, ILLUSTRATION_IMAGE_WORST_CASE_USD)
        assertThatThrownBy { budget.reserveImage() }.isInstanceOf(IllustrationLaneBudgetStop::class.java)
    }

    @Test
    fun `실제 비용을 모르면 예약액을 그대로 지출로 잡는다`() {
        val budget = budgetOf(max = "1.00", textWorstCase = "0.30")

        budget.settle(budget.reserveText(PROMPT, OPTIONS), actualUsd = null)

        assertThat(budget.spentUsd).isEqualByComparingTo("0.30")
    }

    @Test
    fun `거절 2건 연속이면 레인을 멈춘다`() {
        val rule = IllustrationLaneStopRule()

        rule.record(IllustrationLaneSignal.REJECTED)
        assertThat(rule.stopped).isFalse()
        rule.record(IllustrationLaneSignal.REJECTED)

        assertThat(rule.stopped).isTrue()
        assertThat(rule.stopReason).contains("거절")
    }

    @Test
    fun `결과 불명확 2건 연속이면 레인을 멈춘다`() {
        val rule = IllustrationLaneStopRule()

        rule.record(IllustrationLaneSignal.OUTCOME_UNKNOWN)
        rule.record(IllustrationLaneSignal.OUTCOME_UNKNOWN)

        assertThat(rule.stopped).isTrue()
        assertThat(rule.stopReason).contains("결과 불명확")
    }

    @Test
    fun `사이에 정상 결과가 끼면 연속이 끊긴다`() {
        val rule = IllustrationLaneStopRule()

        rule.record(IllustrationLaneSignal.REJECTED)
        rule.record(IllustrationLaneSignal.HEALTHY)
        rule.record(IllustrationLaneSignal.REJECTED)

        assertThat(rule.stopped).isFalse()
    }

    @Test
    fun `서로 다른 실패가 섞이면 연속이 아니다`() {
        val rule = IllustrationLaneStopRule()

        rule.record(IllustrationLaneSignal.REJECTED)
        rule.record(IllustrationLaneSignal.OUTCOME_UNKNOWN)

        assertThat(rule.stopped).isFalse()
    }

    @Test
    fun `인증·권한·정책 거절만 중단 신호다`() {
        assertThat(illustrationLaneSignalOf(faultOf("HTTP 400"))).isEqualTo(IllustrationLaneSignal.REJECTED)
        assertThat(illustrationLaneSignalOf(faultOf("HTTP 401"))).isEqualTo(IllustrationLaneSignal.REJECTED)
        assertThat(illustrationLaneSignalOf(faultOf("HTTP 403"))).isEqualTo(IllustrationLaneSignal.REJECTED)
    }

    @Test
    fun `속도 제한과 서버 오류는 중단 신호가 아니다`() {
        assertThat(illustrationLaneSignalOf(faultOf("HTTP 429"))).isEqualTo(IllustrationLaneSignal.HEALTHY)
        assertThat(illustrationLaneSignalOf(faultOf("HTTP 503"))).isEqualTo(IllustrationLaneSignal.HEALTHY)
        assertThat(illustrationLaneSignalOf(faultOf("SocketTimeoutException")))
            .isEqualTo(IllustrationLaneSignal.HEALTHY)
    }

    @Test
    fun `합성 숫자-only 표본은 제품 가드에 걸린다`() {
        assertThat(isNumericOnlySource(splitUnits(NUMERIC_ONLY_LANE_DOCUMENT.sourceText))).isTrue()
        assertThat(NUMERIC_ONLY_LANE_DOCUMENT.synthetic).isTrue()
    }

    @Test
    fun `원장은 단계마다 누적 비용까지 한 줄로 남긴다`() {
        val ledger = IllustrationLaneLedger()

        ledger.add(
            IllustrationLaneLedgerRow(
                documentId = "001",
                step = "suggest",
                outcome = "valid",
                latencyMs = 1_200,
                inputTokens = 3_000,
                outputTokens = 900,
                estimatedUsd = BigDecimal("0.0420"),
            ),
            cumulativeUsd = BigDecimal("0.1120"),
        )

        val lines = ledger.render().trim().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines.first()).isEqualTo(
            "doc,step,outcome,latency_ms,input_tokens,output_tokens,est_usd,cumulative_usd",
        )
        assertThat(lines.last()).isEqualTo("001,suggest,valid,1200,3000,900,0.0420,0.1120")
    }

    @Test
    fun `측정하지 못한 칸은 비워 둔다`() {
        val ledger = IllustrationLaneLedger()

        ledger.add(
            IllustrationLaneLedgerRow(documentId = "900", step = "suggest", outcome = "skipped_numeric_only"),
            cumulativeUsd = BigDecimal.ZERO,
        )

        val lines = ledger.render().trim().lines()
        assertThat(lines.last()).isEqualTo("900,suggest,skipped_numeric_only,,,,,0")
    }

    private fun budgetOf(
        max: String,
        textWorstCase: String,
    ): IllustrationLaneBudget = IllustrationLaneBudget(BigDecimal(max)) { _, _ -> BigDecimal(textWorstCase) }

    /** 어댑터 문구에서 원인을 되살리는 경로는 [LaneFaults] 하나다 — 레인이 따로 적지 않는다. */
    private fun faultOf(label: String): LaneFault = LaneFaults.of(LlmProviderException("호출 실패 ($label)"))

    private companion object {
        val PROMPT: LlmPrompt = LlmPrompt.forIllustrationSuggestions("원문 한 줄", "본문 한 줄")
        val OPTIONS: LlmOptions = LlmOptions(maxTokens = 8_192)
    }
}

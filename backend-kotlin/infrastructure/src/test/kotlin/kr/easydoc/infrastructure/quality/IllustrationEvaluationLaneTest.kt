package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionAnalysis
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionParser
import kr.easydoc.core.illustration.suggestion.isNumericOnlySource
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.segment.splitUnits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
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
    fun `계획이 고정한 provider 와 모델이면 통과한다`() {
        val env = mapOf(GoldenLlmLane.PROVIDER_ENV to "openai", GoldenLlmLane.MODEL_ENV to "gpt-6-astra")

        assertThat(illustrationLaneModelRejection(env::get)).isNull()
    }

    @Test
    fun `모델을 적지 않으면 거절한다`() {
        val env = mapOf(GoldenLlmLane.PROVIDER_ENV to "openai")

        // 미설정은 코드 기본값 gpt-4.1 로 접혀, 계획이 고정한 모델이 아닌 것을 재고도 쟀다고 적는다.
        assertThat(illustrationLaneModelRejection(env::get)).contains(GoldenLlmLane.MODEL_ENV, "gpt-6-astra")
    }

    @Test
    fun `다른 모델이나 다른 provider 는 거절한다`() {
        val otherModel = mapOf(GoldenLlmLane.PROVIDER_ENV to "openai", GoldenLlmLane.MODEL_ENV to "gpt-4.1")
        val otherProvider = mapOf(GoldenLlmLane.PROVIDER_ENV to "anthropic", GoldenLlmLane.MODEL_ENV to "gpt-6-astra")

        assertThat(illustrationLaneModelRejection(otherModel::get)).contains("gpt-4.1")
        assertThat(illustrationLaneModelRejection(otherProvider::get)).contains("anthropic")
    }

    @Test
    fun `provider 미설정은 배포 기본값 openai 로 본다`() {
        val env = mapOf(GoldenLlmLane.MODEL_ENV to "gpt-6-astra")

        assertThat(illustrationLaneModelRejection(env::get)).isNull()
    }

    @Test
    fun `레인 상한은 숫자여야 하고 승인 총액을 넘을 수 없다`() {
        assertThat(illustrationLaneBudgetOf("20")).isEqualByComparingTo("20")

        assertThatThrownBy { illustrationLaneBudgetOf(null) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("EASYDOC_LANE_MAX_USD")
        assertThatThrownBy { illustrationLaneBudgetOf("20달러") }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("숫자")
        assertThatThrownBy { illustrationLaneBudgetOf("0") }
            .isInstanceOf(ConfigurationException::class.java)
        assertThatThrownBy { illustrationLaneBudgetOf("50.01") }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("승인")
    }

    @Test
    fun `이미지 단가는 설정했다면 0보다 커야 한다`() {
        assertThat(illustrationLaneImageCostOf(null)).isNull()
        assertThat(illustrationLaneImageCostOf("0.02")).isEqualByComparingTo("0.02")

        assertThatThrownBy { illustrationLaneImageCostOf("0") }.isInstanceOf(ConfigurationException::class.java)
        assertThatThrownBy { illustrationLaneImageCostOf("-1") }.isInstanceOf(ConfigurationException::class.java)
        assertThatThrownBy { illustrationLaneImageCostOf("무료") }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    fun `2차 표본은 1차 13건에 보류 4건을 더한 17건이다`() {
        assertThat(ILLUSTRATION_LANE_PLAN_SAMPLE_IDS).hasSize(17).doesNotHaveDuplicates()
        assertThat(ILLUSTRATION_LANE_PLAN_SAMPLE_IDS)
            .containsSequence("001", "010", "015", "089")
            .contains("041", "090", "097", "005", "016", "081", "052", "063", NUMERIC_ONLY_LANE_DOCUMENT.id)
            .containsSequence("002", "019", "080", "098")
    }

    @Test
    fun `산출물 기본 경로는 2차 평가 디렉터리다`() {
        assertThat(IllustrationLaneArtifacts.DEFAULT_RELATIVE)
            .isEqualTo("docs/reports/2026-11-10-illustration-p0-1-eval-2-artifacts")
    }

    @Test
    fun `표본 밖 문서를 고르면 거절한다`() {
        val allowed = listOf("001", "010")

        assertThat(illustrationLaneSampleRejection("001,010", allowed)).isNull()
        assertThat(illustrationLaneSampleRejection("001", allowed)).isNull()
        assertThat(illustrationLaneSampleRejection(null, allowed)).isNull()
        assertThat(illustrationLaneSampleRejection("001,002", allowed)).contains("002")
    }

    @Test
    fun `단계가 다르면 연속 실패가 아니다`() {
        val rules = IllustrationLaneStopRules()

        rules.generate.record(IllustrationLaneSignal.REJECTED)
        rules.convert.record(IllustrationLaneSignal.HEALTHY)
        assertThat(rules.stopped).isFalse()
        rules.generate.record(IllustrationLaneSignal.REJECTED)

        assertThat(rules.stopped).isTrue()
        assertThat(rules.stopReason).contains("generate", "거절")
    }

    @Test
    fun `합성 숫자-only 표본은 제품 가드에 걸린다`() {
        assertThat(isNumericOnlySource(splitUnits(NUMERIC_ONLY_LANE_DOCUMENT.sourceText))).isTrue()
        assertThat(NUMERIC_ONLY_LANE_DOCUMENT.synthetic).isTrue()
    }

    @Test
    fun `제안 JSON 은 응답 원문과 숫자-only 여부를 함께 남긴다`() {
        val node = JSON.readTree(IllustrationLaneJson.suggestions("응답 원문", null, numericOnlySource = true))

        assertThat(node.path("raw_text").stringValue("")).isEqualTo("응답 원문")
        assertThat(node.path("numeric_only_source").booleanValue(false)).isTrue()
        assertThat(node.path("analysis").stringValue("")).isEqualTo("invalid_structure")
    }

    @Test
    fun `유효한 제안도 숫자-only 여부를 함께 남긴다`() {
        val analysis =
            IllustrationSuggestionParser.parseAndValidate(
                VALID_SUGGESTION_JSON,
                splitUnits(SOURCE_TEXT),
                splitUnits(EASY_BODY),
            )
        val valid = analysis as IllustrationSuggestionAnalysis.Valid

        val node =
            JSON.readTree(
                IllustrationLaneJson.suggestions(VALID_SUGGESTION_JSON, valid.suggestions, numericOnlySource = false),
            )

        assertThat(node.path("analysis").stringValue("")).isEqualTo("valid")
        assertThat(node.path("numeric_only_source").booleanValue(true)).isFalse()
        assertThat(node.path("raw_text").stringValue("")).isEqualTo(VALID_SUGGESTION_JSON)
        val suggestion = node.path("suggestions").path(0)
        assertThat(suggestion.path("purpose").stringValue("")).isEqualTo("procedure")
        assertThat(suggestion.path("alt_text_draft").stringValue("")).isEqualTo("신청 순서를 보여 주는 그림")
        val anchor = suggestion.path("source_anchors").path(0)
        assertThat(anchor.path("quote").stringValue("")).isEqualTo("먼저 신청서를 씁니다")
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
            IllustrationLaneLedgerRow(documentId = "900", step = "generate-1", outcome = "configuration_invalid"),
            cumulativeUsd = BigDecimal.ZERO,
        )

        val lines = ledger.render().trim().lines()
        assertThat(lines.last()).isEqualTo("900,generate-1,configuration_invalid,,,,,0")
    }

    private fun budgetOf(
        max: String,
        textWorstCase: String,
    ): IllustrationLaneBudget = IllustrationLaneBudget(BigDecimal(max)) { _, _ -> BigDecimal(textWorstCase) }

    /** 어댑터 문구에서 원인을 되살리는 경로는 [LaneFaults] 하나다 — 레인이 따로 적지 않는다. */
    private fun faultOf(label: String): LaneFault = LaneFaults.of(LlmProviderException("호출 실패 ($label)"))

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        val PROMPT: LlmPrompt = LlmPrompt.forIllustrationSuggestions("원문 한 줄", "본문 한 줄")
        val OPTIONS: LlmOptions = LlmOptions(maxTokens = 8_192)

        const val SOURCE_TEXT: String = "먼저 신청서를 씁니다\n그다음 서류를 냅니다"
        const val EASY_BODY: String = "신청서를 씁니다\n서류를 냅니다"

        /** 제품 파서·검증기를 그대로 통과하는 최소 응답 — 숫자가 없어 사실 대조에 걸리지 않는다. */
        val VALID_SUGGESTION_JSON: String =
            """
            {"schema_version":1,"suggestions":[{"purpose":"procedure",
            "reason":"절차를 그림으로 보여 주면 이해가 쉽다","body_range":{"start":0,"end":1},
            "source_anchors":[{"source_unit_indexes":[0],"quote":"먼저 신청서를 씁니다"}],
            "scenes":["신청서를 쓰는 모습"],"preserved_facts":["신청서를 먼저 씁니다"],
            "alt_text_draft":"신청 순서를 보여 주는 그림"}]}
            """.trimIndent().replace("\n", "")
    }
}

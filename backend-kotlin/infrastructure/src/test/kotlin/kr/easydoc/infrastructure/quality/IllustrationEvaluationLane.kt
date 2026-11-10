package kr.easydoc.infrastructure.quality

import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSet
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.quality.GoldenDocument
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

// P0-1 「실제 provider 품질·비용·지연 출시 게이트」 레인의 규칙 묶음
// (`docs/plans/2026-11-07-illustration-explanation-improvement-plan.md` §12).
//
// 여기 있는 것은 전부 **유료 호출을 시작할지 정하는 규칙**이다 — 예산 선검사, 연속 실패
// 중단, 원장 한 줄, 숫자-only 합성 표본. 유료 호출을 해야만 확인할 수 있는 자리에 두면
// 확인할 방법이 없으므로(`GoldenLlmLane` 이 선택 규칙을 분리한 것과 같은 판단)
// `IllustrationProviderEvaluationTest` 본문에서 떼어 `IllustrationEvaluationLaneTest` 가
// 가짜로 시험한다.
//
// 담는 것은 문서 id·단계 이름·숫자뿐이다 — 원문, 쉬운 글, 프롬프트, 응답 본문, API 키는
// 어느 문자열에도 실리지 않는다(프로젝트 CLAUDE.md 관측 규칙).

/**
 * 이미지 1장의 보수적 최악 비용.
 *
 * 계획 §12.1 「다음 호출의 최악 비용(… 이미지는 US$0.10/장으로 보수 계상)을 더해 상한을
 * 넘으면 그 호출을 시작하지 않는다」. 외부 추정 단가(US$0.013~0.03/장)보다 크게 잡는 이유는
 * 상한 판정이 과소 계상으로 헐거워지지 않게 하기 위해서다. 실제 정산 단가가 구성
 * (`easydoc.illustration-generation.provider-cost-usd-per-image`)으로 주어지면 지출 누적은
 * 그 값으로 하고, 선검사는 그대로 이 상계를 쓴다.
 */
internal val ILLUSTRATION_IMAGE_WORST_CASE_USD: BigDecimal = BigDecimal("0.10")

/** 계획 §12.3 — 거절·결과 불명확이 이만큼 연속이면 레인을 멈춘다. */
internal const val ILLUSTRATION_LANE_STOP_STREAK: Int = 2

/** 계획 §12.1 「문서마다 유효 제안 중 앞의 최대 2건」. */
internal const val ILLUSTRATION_LANE_IMAGES_PER_DOCUMENT: Int = 2

/**
 * 예산 상한에 걸려 **이 호출을 시작하지 않았다**.
 *
 * [LlmProviderException][kr.easydoc.core.exceptions.LlmProviderException] 을 상속하지 않는
 * 것이 요점이다 — 변환 유스케이스(`ConvertDocumentUseCase`)는 provider 실패를 잡아
 * `ConversionResult.Failed` 로 접지만, 예산 중단은 변환 실패가 아니라 **레인 중단**이라
 * 그 catch 를 지나 레인 본문까지 올라와야 한다.
 */
internal class IllustrationLaneBudgetStop(message: String) : RuntimeException(message)

/**
 * 레인 전체의 달러 상한과 누적 지출.
 *
 * [LaneSpendLimit] 과 두 가지가 다르다. 첫째, 상한에 걸리면 `check` 로 터지는 대신
 * [IllustrationLaneBudgetStop] 을 던져 레인이 「budget_stop」 을 원장에 적고 **정상적으로**
 * 멈추게 한다 — 계획 §12.3 이 예산 도달을 실패가 아니라 중단으로 규정한다. 둘째, 텍스트만이
 * 아니라 이미지 호출도 같은 상한에 건다.
 *
 * [textWorstCaseUsd] 는 호출자가 넘긴다 — 실제 레인은 [LaneSpendLimit.reservationFor] 를
 * 그대로 넘겨 텍스트 상계식을 한 벌만 둔다.
 */
internal class IllustrationLaneBudget(
    private val maxUsd: BigDecimal,
    private val imageWorstCaseUsd: BigDecimal = ILLUSTRATION_IMAGE_WORST_CASE_USD,
    private val textWorstCaseUsd: (LlmPrompt, LlmOptions) -> BigDecimal,
) {
    init {
        require(maxUsd.signum() > 0) { "레인 상한은 0보다 커야 한다: $maxUsd" }
        require(imageWorstCaseUsd.signum() > 0) { "이미지 최악 비용은 0보다 커야 한다: $imageWorstCaseUsd" }
    }

    /** 지금까지 쓴 것으로 계상한 액수. 실제 비용을 모르는 호출은 예약액 그대로 남는다([settle]). */
    var spentUsd: BigDecimal = BigDecimal.ZERO
        private set

    /** 텍스트 호출 하나를 예약한다. 상한을 넘기면 [IllustrationLaneBudgetStop]. */
    fun reserveText(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): BigDecimal = reserve(textWorstCaseUsd(prompt, options), "텍스트")

    /** 이미지 호출 하나를 예약한다. 상한을 넘기면 [IllustrationLaneBudgetStop]. */
    fun reserveImage(): BigDecimal = reserve(imageWorstCaseUsd, "이미지")

    /**
     * 예약을 실제 비용으로 정산한다. [actualUsd] 가 `null`(단가 미설정·호출 실패로 비용을
     * 모름)이면 **예약액을 그대로** 지출로 남긴다 — 0달러로 속이면 상한이 조용히 헐거워진다
     * ([LaneSpendLimit.settle] 이 예약을 붙잡아 두는 것과 같은 판단).
     */
    fun settle(
        reservation: BigDecimal,
        actualUsd: BigDecimal?,
    ) {
        require(actualUsd == null || actualUsd.signum() >= 0) { "실제 비용은 음수일 수 없다: actual_usd=$actualUsd" }
        spentUsd += actualUsd ?: reservation
        println("illustration lane budget: spent_usd=$spentUsd cap_usd=$maxUsd")
    }

    private fun reserve(
        worstCaseUsd: BigDecimal,
        what: String,
    ): BigDecimal {
        if (spentUsd + worstCaseUsd > maxUsd) {
            throw IllustrationLaneBudgetStop(
                "USD 상한 전에 중단: 상한=$maxUsd 지출=$spentUsd 다음 $what 호출 최악비용=$worstCaseUsd",
            )
        }
        return worstCaseUsd
    }
}

/**
 * 중단 규칙이 보는 호출 결과 한 건.
 *
 * 계획 §12.3 은 두 가지만 연속 중단 사유로 든다 — 인증·권한·정책 거부(4xx)와 결과 불명확.
 * 그 밖의 결과(성공, 구조 위반, 속도 제한, 5xx, 전송 실패)는 전부 [HEALTHY] 로 들어와
 * 연속을 끊는다. 「실패지만 중단 사유는 아니다」를 [HEALTHY] 하나로 접는 이유는, 그것들이
 * 연속 판정에서 하는 일이 성공과 똑같기 때문이다.
 */
internal enum class IllustrationLaneSignal {
    HEALTHY,
    REJECTED,
    OUTCOME_UNKNOWN,
}

/**
 * 같은 종류의 실패가 [threshold] 건 연속이면 레인을 멈춘다(계획 §12.3).
 *
 * 종류가 섞이면 연속이 아니다 — 거절 1건 뒤 결과 불명확 1건은 「거절 2건」도 「불명확 2건」도
 * 아니다. 한 번 멈춘 이유는 덮어쓰지 않는다: 왜 멈췄는지가 평가 기록에 남아야 한다.
 */
internal class IllustrationLaneStopRule(private val threshold: Int = ILLUSTRATION_LANE_STOP_STREAK) {
    init {
        require(threshold >= 1) { "중단 임계는 1 이상이어야 한다: $threshold" }
    }

    private var last: IllustrationLaneSignal = IllustrationLaneSignal.HEALTHY

    private var streak: Int = 0

    /** 멈춘 이유 한 줄. 아직 멈추지 않았으면 `null`. */
    var stopReason: String? = null
        private set

    val stopped: Boolean get() = stopReason != null

    fun record(signal: IllustrationLaneSignal) {
        if (signal == IllustrationLaneSignal.HEALTHY) {
            last = signal
            streak = 0
            return
        }
        streak = if (signal == last) streak + 1 else 1
        last = signal
        if (streak >= threshold && stopReason == null) {
            stopReason = "${reasonOf(signal)} $streak 건 연속 — 계획 §12.3 중단 규칙"
        }
    }

    private fun reasonOf(signal: IllustrationLaneSignal): String =
        when (signal) {
            IllustrationLaneSignal.REJECTED -> "provider 거절(4xx)"
            IllustrationLaneSignal.OUTCOME_UNKNOWN -> "결과 불명확"
            IllustrationLaneSignal.HEALTHY -> "정상"
        }
}

/**
 * 어댑터 실패 한 건을 중단 규칙의 신호로 옮긴다.
 *
 * 상태 해석은 [LaneFaults] 하나가 한다 — 레인이 문구를 다시 읽으면 규칙이 두 벌이 된다.
 * 4xx 중에서도 [LaneFault.transient] 인 것(408·425·429)은 「지금 말고 나중에」라는 뜻이지
 * 계획 §12.3 이 말하는 인증·권한·정책 거부가 아니라 [IllustrationLaneSignal.HEALTHY] 다.
 * 상태가 없는 전송 실패도 여기서는 [IllustrationLaneSignal.HEALTHY] 이고, 이미지 호출의
 * 타임아웃·I/O 실패는 결과 불명확이라 호출부가 따로 [IllustrationLaneSignal.OUTCOME_UNKNOWN]
 * 으로 기록한다(제품 `ProviderIllustrationGenerationJobRunner` 가 그 둘만 다시 던지는 것과
 * 같은 가름).
 */
internal fun illustrationLaneSignalOf(fault: LaneFault): IllustrationLaneSignal {
    val status = fault.status ?: return IllustrationLaneSignal.HEALTHY
    return if (status in CLIENT_ERROR_FLOOR..CLIENT_ERROR_CEILING && !fault.transient) {
        IllustrationLaneSignal.REJECTED
    } else {
        IllustrationLaneSignal.HEALTHY
    }
}

private const val CLIENT_ERROR_FLOOR: Int = 400

private const val CLIENT_ERROR_CEILING: Int = 499

/**
 * 원장 한 줄. 계획 §12.1 「단계별 지연·토큰·추정 비용, 실패 분류」를 문서·단계 단위로 남긴다.
 *
 * 재지 못한 칸은 0이 아니라 **빈 칸**이다 — provider 가 실패해 토큰을 모르는 호출과 토큰이
 * 0인 호출은 다른 사실이다([LaneMeasurement] 의 `null` 관례와 같다).
 */
internal data class IllustrationLaneLedgerRow(
    val documentId: String,
    val step: String,
    val outcome: String,
    val latencyMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedUsd: BigDecimal? = null,
)

/** 원장 전체. `ledger.csv` 와 stdout 이 같은 문자열을 쓴다. */
internal class IllustrationLaneLedger {
    private val lines = mutableListOf<String>()

    fun add(
        row: IllustrationLaneLedgerRow,
        cumulativeUsd: BigDecimal,
    ) {
        lines +=
            listOf(
                row.documentId,
                row.step,
                row.outcome,
                row.latencyMs?.toString().orEmpty(),
                row.inputTokens?.toString().orEmpty(),
                row.outputTokens?.toString().orEmpty(),
                row.estimatedUsd?.toPlainString().orEmpty(),
                cumulativeUsd.toPlainString(),
            ).joinToString(",")
    }

    fun render(): String = (listOf(HEADER) + lines).joinToString("\n", postfix = "\n")

    private companion object {
        const val HEADER: String = "doc,step,outcome,latency_ms,input_tokens,output_tokens,est_usd,cumulative_usd"
    }
}

/**
 * 계획 §12.1 표본 13건 중 **합성 숫자-only 1건**.
 *
 * 골든 코퍼스에는 숫자만 있는 문서가 없어 레인이 직접 만든다. 금액·날짜·비율만 있는 짧은
 * 표를 본떴고, 실제로 [isNumericOnlySource][kr.easydoc.core.illustration.suggestion.isNumericOnlySource]
 * 가 참이 되는지는 [IllustrationEvaluationLaneTest] 가 고정한다 — 참이 아니면 이 표본은
 * 가드를 재지 못한 채 유료 호출만 쓴다. 제품 가드가 허용하는 문자는 숫자·공백과 좁게 정한
 * 구분 기호뿐이라(`NumericOnlySource.kt`) `%`·`원` 같은 단위는 쓸 수 없다.
 *
 * id 는 골든 코퍼스와 겹치지 않는 값이다 — 산출물 디렉터리 이름이 문서 id 라 겹치면 한쪽이
 * 다른 쪽을 덮어쓴다.
 */
internal val NUMERIC_ONLY_LANE_DOCUMENT: GoldenDocument =
    GoldenDocument(
        id = "900-numeric-only",
        title = "숫자-only 합성 표본",
        category = "synthetic",
        synthetic = true,
        sourceText =
            listOf(
                "2026. 1. 1.",
                "150,000",
                "2026. 6. 30.",
                "1,250,000",
                "2026-01-01 / 2026-12-31",
                "30.5",
            ).joinToString("\n"),
        requiredFacts = emptyList(),
    )

/**
 * 산출물 JSON. 표본이 공개 행정 문서라 제안 본문·원문 인용을 남기는 것은 허용된다(계획 §12.1) —
 * 사람이 ①~④ 를 채점하려면 그 값들이 있어야 한다. 이미지 쪽은 **프롬프트 전문 대신 길이**만
 * 남긴다: 프롬프트에 새로 생기는 사실이 없고, 구성·대체텍스트가 그 내용을 이미 담는다.
 *
 * 레인 본문이 아니라 여기 있는 이유는 [IllustrationLaneBudget] 과 같다 — 산출물 모양은 순수
 * 함수라 유료 호출 없이 시험할 수 있어야 한다([IllustrationEvaluationLaneTest]).
 */
internal object IllustrationLaneJson {
    private val JSON: JsonMapper = JsonMapper.builder().build()

    /**
     * 제안 한 번의 산출물.
     *
     * [rawText] 는 **언제나** 남긴다 — 제품이 숫자-only 원문의 제안을 전부 버리더라도
     * (`IllustrationSuggestionValidator.validate`), 모델이 스스로 억지 제안을 냈는지는 응답
     * 원문에만 남는다(계획 §12.2 ①′ 가 묻는 것이 그것이다). [numericOnlySource] 는 그 판정의
     * 참고값일 뿐 레인의 분기 조건이 아니다.
     */
    fun suggestions(
        rawText: String,
        suggestions: IllustrationSuggestionSet?,
        numericOnlySource: Boolean,
    ): String {
        val root = JSON.createObjectNode()
        root.put("raw_text", rawText)
        root.put("numeric_only_source", numericOnlySource)
        if (suggestions == null) {
            root.put("analysis", "invalid_structure")
            return write(root)
        }
        root.put("analysis", "valid")
        root.put("schema_version", suggestions.schemaVersion)
        root.put("analysis_version", suggestions.analysisVersion)
        root.put("dropped_count", suggestions.droppedCount)
        val array = root.putArray("suggestions")
        suggestions.suggestions.forEach { array.add(suggestionNode(it)) }
        return write(root)
    }

    fun image(
        suggestion: IllustrationSuggestion,
        configuration: IllustrationGenerationConfiguration,
        prompt: String,
    ): String {
        val node = JSON.createObjectNode()
        node.put("suggestion_id", suggestion.suggestionId.toString())
        node.put("purpose", suggestion.purpose.wireName)
        node.put("configuration_revision", illustrationConfigurationRevision(configuration))
        node.put("prompt_code_points", prompt.codePointCount(0, prompt.length))
        val scenes = node.putArray("scenes")
        configuration.scenes.forEach { scenes.add(it) }
        val facts = node.putArray("preserved_facts")
        configuration.preservedFacts.forEach { facts.add(it) }
        node.put("alt_text", configuration.altText)
        return write(node)
    }

    private fun suggestionNode(suggestion: IllustrationSuggestion): ObjectNode {
        val node = JSON.createObjectNode()
        node.put("suggestion_id", suggestion.suggestionId.toString())
        node.put("purpose", suggestion.purpose.wireName)
        node.put("reason", suggestion.reason)
        node.putObject("body_range").apply {
            put("start", suggestion.bodyRange.start)
            put("end", suggestion.bodyRange.end)
        }
        val anchors = node.putArray("source_anchors")
        suggestion.sourceAnchors.forEach { anchor ->
            val anchorNode = JSON.createObjectNode()
            val indexes = anchorNode.putArray("source_unit_indexes")
            anchor.sourceUnitIndexes.forEach { indexes.add(it) }
            anchorNode.put("quote", anchor.quote)
            anchors.add(anchorNode)
        }
        val scenes = node.putArray("scenes")
        suggestion.scenes.forEach { scenes.add(it) }
        val facts = node.putArray("preserved_facts")
        suggestion.preservedFacts.forEach { facts.add(it) }
        node.put("alt_text_draft", suggestion.altTextDraft)
        return node
    }

    private fun write(node: ObjectNode): String = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node)
}

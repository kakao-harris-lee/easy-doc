package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.application.conversion.ConversionUsage
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.IllustrationProviderIoException
import kr.easydoc.core.exceptions.IllustrationProviderTimeoutException
import kr.easydoc.core.exceptions.IllustrationResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.ILLUSTRATION_GENERATION_SIZE
import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.illustration.generation.MAX_GENERATION_FACTS
import kr.easydoc.core.illustration.generation.MAX_GENERATION_SCENES
import kr.easydoc.core.illustration.generation.buildIllustrationGenerationPrompt
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.generation.validateIllustrationGenerationConfiguration
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionAnalysis
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionParser
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSet
import kr.easydoc.core.illustration.suggestion.isNumericOnlySource
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader
import kr.easydoc.core.security.Secret
import kr.easydoc.core.segment.splitUnits
import kr.easydoc.infrastructure.illustration.generation.DEFAULT_OPENAI_IMAGE_MODEL
import kr.easydoc.infrastructure.illustration.generation.OpenAiImageGenerationProvider
import kr.easydoc.infrastructure.illustration.suggestion.IllustrationSuggestionProperties
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * P0-1 실제 provider 평가 레인 — 계획 §12
 * (`docs/plans/2026-11-07-illustration-explanation-improvement-plan.md`).
 *
 * `./gradlew :infrastructure:testLlm` 으로만 열리며(`@Tag("llm")`), 선택한 provider 의 비밀값이
 * 없으면 skip 한다. 표본마다 **제품과 같은 경로**를 그대로 지난다:
 * 변환(`ConvertDocumentUseCase`) → 숫자-only 가드(`isNumericOnlySource`) →
 * 제안(`LlmPrompt.forIllustrationSuggestions` + `IllustrationSuggestionParser.parseAndValidate`,
 * `ProviderIllustrationSuggestionJobRunner.call` 과 같은 순서·판정) →
 * 생성(`buildIllustrationGenerationPrompt` + `OpenAiImageGenerationProvider`). PNG 검증은
 * provider 가 응답을 받자마자 `GeneratedPngValidator.validate` 로 하므로
 * (`OpenAiImageGenerationProvider.generate`) 레인이 한 번 더 하지 않는다 — 여기서 올라오는
 * `IllustrationResultException` 이 곧 그 검증의 판정이다.
 *
 * **품질은 이 레인이 판정하지 않는다.** 채점은 사람(세션 모델)이 산출물을 보고 한다(§12.2).
 * 이 테스트가 실패하는 경우는 구성 오류뿐이다 — 비밀값·예산·산출물 디렉터리·표본 설정.
 *
 * 재시도는 두지 않는다(§12.1 「재시도 0회, 각 단계 1회 실행」) — 그래서 골든 레인과 달리
 * [LaneInstrumentedProvider] 로 감싸지 않고 제품이 조립한 provider 를 예산 계측
 * ([IllustrationLaneProvider])만 거쳐 그대로 부른다.
 */
@Tag("llm")
class IllustrationProviderEvaluationTest {
    @Test
    @DisplayName("비밀값이 있으면 제품과 같은 경로로 제안·생성을 실제 provider 로 측정한다")
    fun `그림 제안과 생성을 실측한다`() {
        val ready = resolvePlan()
        val budgetUsd = laneBudget()
        val spendLimit = spendLimitFor(ready, budgetUsd)
        val imageSettings = resolveImageSettings()
        val suggestionMaxTokens = suggestionMaxOutputTokens()
        val selection = selectDocuments()
        val artifacts = planArtifacts(selection.documents.map(GoldenDocument::id))

        // 텍스트 최악 비용의 상계식은 골든 레인과 한 벌만 둔다([LaneSpendLimit.reservationFor]).
        val budget = IllustrationLaneBudget(budgetUsd, textWorstCaseUsd = spendLimit::reservationFor)
        val provider = IllustrationLaneProvider(ready.provider, budget)
        val ledger = IllustrationLaneLedger()
        val stopRule = IllustrationLaneStopRule()

        val conditions =
            listOf(
                ready.description,
                "suggestion_max_tokens=$suggestionMaxTokens",
                imageSettings.description,
                "images_per_document<=$ILLUSTRATION_LANE_IMAGES_PER_DOCUMENT retries=0",
                selection.description,
                artifacts.description,
                "cap_usd=$budgetUsd image_worst_case_usd=$ILLUSTRATION_IMAGE_WORST_CASE_USD",
                "commit=${IllustrationLaneArtifacts.commitSha(IllustrationLaneArtifacts.repositoryRoot())}",
            ).joinToString(" · ")
        println(conditions)
        // 디렉터리를 나중에 여는 사람이 무엇으로 잰 산출물인지 알아야 한다 — 유료 호출 전에 쓴다.
        artifacts.writeConditions(conditions)

        val run =
            IllustrationLaneDocumentRun(
                converter = ConvertDocumentUseCase(provider, defaultOptions = ready.options),
                provider = provider,
                suggestionOptions = LlmOptions(maxTokens = suggestionMaxTokens),
                images = imageSettings.images(),
                materials = IllustrationLaneMaterials(budget, stopRule, ledger, artifacts),
            )

        // 문서를 **순차로** 돈다 — 동시 호출은 예산 선검사와 연속 실패 판정을 함께 무너뜨린다.
        for (document in selection.documents) {
            if (stopRule.stopped || runOrStop(run, document, ledger, budget)) break
        }

        artifacts.writeLedger(ledger.render())
        println(ledger.render())
        stopRule.stopReason?.let { println("lane stopped: $it") }
        println("lane spent_usd=${budget.spentUsd} cap_usd=$budgetUsd")
    }

    /** 예산 상한에 닿으면 실패가 아니라 중단이다(§12.3) — 원장에 남기고 레인을 끝낸다. */
    private fun runOrStop(
        run: IllustrationLaneDocumentRun,
        document: GoldenDocument,
        ledger: IllustrationLaneLedger,
        budget: IllustrationLaneBudget,
    ): Boolean =
        try {
            run.run(document)
            false
        } catch (stop: IllustrationLaneBudgetStop) {
            ledger.add(IllustrationLaneLedgerRow(document.id, "lane", "budget_stop"), budget.spentUsd)
            println("lane stopped: ${stop.message}")
            true
        }

    private fun resolvePlan(): LanePlan.Ready =
        when (val plan = GoldenLlmLane.plan(System::getenv)) {
            is LanePlan.Ready -> plan
            is LanePlan.Skipped -> abort<LanePlan.Ready>(plan.reason)
            is LanePlan.Unusable -> fail<LanePlan.Ready>(plan.reason)
        }

    /**
     * 계획 §12.1 표본 13건이 기본이다. [LaneDocuments.ENV] 를 설정하면 골든 코퍼스 + 합성
     * 표본 안에서 그 목록으로 바꾼다 — 검증(문법·중복·미존재)은 골든 레인과 같은 규칙을 쓴다.
     * 기본 표본은 코퍼스 순서가 아니라 **계획이 적은 순서**로 돈다(절차 → 비교·관계 →
     * 조건·예외 → 무제안 → 숫자-only) — 원장을 계획 §12.1 표와 나란히 읽기 위해서다.
     */
    private fun selectDocuments(): LaneDocumentSelection {
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory()).documents
        val pool = corpus + NUMERIC_ONLY_LANE_DOCUMENT
        if (System.getenv(LaneDocuments.ENV) != null) {
            return try {
                LaneDocuments.select(System::getenv, pool)
            } catch (exc: ConfigurationException) {
                fail<LaneDocumentSelection>(exc.message)
            }
        }
        val byId = pool.associateBy(GoldenDocument::id)
        val documents =
            PLAN_SAMPLE_IDS.map { id ->
                byId[id] ?: fail<GoldenDocument>("계획 §12.1 표본 '$id' 가 골든 코퍼스에 없다")
            }
        return LaneDocumentSelection(
            documents,
            "documents=plan-12.1(${documents.size})[${PLAN_SAMPLE_IDS.joinToString(",")}]",
        )
    }

    private fun planArtifacts(documentIds: List<String>): IllustrationLaneArtifacts =
        when (val plan = IllustrationLaneArtifacts.plan(System::getenv, documentIds)) {
            is IllustrationLaneArtifactsPlan.Ready -> plan.artifacts
            is IllustrationLaneArtifactsPlan.Unusable -> fail<IllustrationLaneArtifacts>(plan.reason)
        }

    /** 제품 worker 구성(`easydoc.illustration-suggestions.max-output-tokens`)과 같은 이름·기본값이다. */
    private fun suggestionMaxOutputTokens(): Int {
        val raw =
            System.getenv(SUGGESTION_MAX_OUTPUT_TOKENS_ENV)?.takeIf(String::isNotBlank)
                ?: return IllustrationSuggestionProperties.DEFAULT_MAX_OUTPUT_TOKENS
        return raw.toIntOrNull() ?: fail<Int>("$SUGGESTION_MAX_OUTPUT_TOKENS_ENV='$raw' 은 정수가 아니다")
    }

    private fun resolveImageSettings(): IllustrationLaneImageSettings =
        try {
            IllustrationLaneImageSettings.from(System::getenv)
        } catch (exc: ConfigurationException) {
            fail<IllustrationLaneImageSettings>(exc.message)
        }

    private fun spendLimitFor(
        ready: LanePlan.Ready,
        budgetUsd: BigDecimal,
    ): LaneSpendLimit =
        LaneSpendLimit(
            budgetUsd,
            checkNotNull(ready.pricing.inputUsdPerMillionTokens) {
                "텍스트 최악 비용을 계산하려면 ${GoldenLlmLane.INPUT_PRICE_ENV} 가 필요하다"
            },
            checkNotNull(ready.pricing.outputUsdPerMillionTokens) {
                "텍스트 최악 비용을 계산하려면 ${GoldenLlmLane.OUTPUT_PRICE_ENV} 가 필요하다"
            },
        )

    private fun laneBudget(): BigDecimal =
        checkNotNull(System.getenv("EASYDOC_LANE_MAX_USD")) {
            "유료 실행에는 EASYDOC_LANE_MAX_USD로 달러 상한을 명시해야 한다"
        }.toBigDecimal()

    private companion object {
        /** 제품 worker `application.yml` 과 같은 이름이다. */
        const val SUGGESTION_MAX_OUTPUT_TOKENS_ENV: String = "EASYDOC_ILLUSTRATION_SUGGESTIONS_MAX_OUTPUT_TOKENS"

        /**
         * 계획 §12.1 표본 13건 — 절차 `001·010·015·089`, 비교·관계 `041·090·097`,
         * 조건·예외 `005·016·081`, 무제안 예상 `052·063`, 숫자-only 합성 1건.
         */
        val PLAN_SAMPLE_IDS: List<String> =
            listOf("001", "010", "015", "089", "041", "090", "097", "005", "016", "081", "052", "063") +
                NUMERIC_ONLY_LANE_DOCUMENT.id
    }
}

/** 이미지 단계의 provider 와 정산 단가. 레인 조립이 한 번만 고른다. */
private class IllustrationLaneImages(
    val provider: IllustrationGenerationProvider,
    /** `easydoc.illustration-generation.provider-cost-usd-per-image`. 미설정이면 보수 단가로 계상한다. */
    val costUsdPerImage: BigDecimal?,
)

/**
 * 이미지 단계 설정 — 제품과 같은 환경변수 이름을 읽는다.
 *
 * 모델 기본값은 제품 상수([DEFAULT_OPENAI_IMAGE_MODEL])를 그대로 쓴다. 해상도·품질·장수는
 * `OpenAiImageGenerationProvider` 가 요청 본문에 고정하므로 레인이 고를 수 없다 — 측정 조건
 * 줄에는 그 고정값을 적는다.
 */
private class IllustrationLaneImageSettings(
    private val apiKey: Secret,
    private val model: String,
    private val costUsdPerImage: BigDecimal?,
) {
    val description: String
        get() =
            "image_model=$model size=${ILLUSTRATION_GENERATION_SIZE}x$ILLUSTRATION_GENERATION_SIZE n=1 " +
                "quality=medium(provider 고정) cost_usd_per_image=${costUsdPerImage ?: "미설정"}"

    fun images(): IllustrationLaneImages =
        IllustrationLaneImages(OpenAiImageGenerationProvider(apiKey, model), costUsdPerImage)

    companion object {
        /** 제품 worker `application.yml` 과 같은 이름이다. */
        const val MODEL_ENV: String = "EASYDOC_ILLUSTRATION_GENERATION_MODEL"

        const val COST_ENV: String = "EASYDOC_ILLUSTRATION_GENERATION_PROVIDER_COST_USD_PER_IMAGE"

        fun from(env: (String) -> String?): IllustrationLaneImageSettings {
            val key = env(GoldenLlmLane.OPENAI_KEY_ENV)?.takeIf(String::isNotBlank)
            if (key == null) {
                throw ConfigurationException(
                    "이미지 생성에는 ${GoldenLlmLane.OPENAI_KEY_ENV} 가 필요하다 — 키 없이 시작하면 변환·제안만 " +
                        "사고 생성 단계에서 전부 실패한다.",
                )
            }
            return IllustrationLaneImageSettings(
                apiKey = Secret(key),
                model = env(MODEL_ENV)?.takeIf(String::isNotBlank) ?: DEFAULT_OPENAI_IMAGE_MODEL,
                costUsdPerImage = costOf(env(COST_ENV)?.takeIf(String::isNotBlank)),
            )
        }

        /**
         * 미설정은 `null`(보수 단가로 계상). 값이 있는데 숫자가 아니거나 음수면 조용히 접지
         * 않는다 — 접으면 운영자가 적은 단가와 다른 값으로 지출을 계상한 채 유료 호출을 다
         * 쓰게 된다([GoldenLlmLane.priceOf] 와 같은 판단).
         */
        private fun costOf(raw: String?): BigDecimal? {
            val cost = raw?.toBigDecimalOrNull()
            if (raw != null && cost == null) throw ConfigurationException("$COST_ENV='$raw' 은 숫자가 아니다")
            if (cost != null && cost.signum() < 0) throw ConfigurationException("$COST_ENV 는 음수일 수 없다: $cost")
            return cost
        }
    }
}

/**
 * 제품 provider 를 예산 계측이 감싼다 — **호출 하나마다** 최악 비용을 먼저 견준다.
 *
 * 변환은 한 문서에 보정까지 두 번 부를 수 있어(`ConvertDocumentUseCase`), 문서 단위로만 견주면
 * 상한을 넘긴 뒤에야 알게 된다. 실패한 시도는 비용을 알 수 없으므로 예약액을 그대로 지출로
 * 남긴다([IllustrationLaneBudget.settle]).
 *
 * [lastFault] 는 변환 실패를 중단 규칙의 신호로 옮기는 데 쓴다 — `ConversionResult.Failed` 는
 * 원인을 `PROVIDER_ERROR` 한 값으로 뭉개서 4xx 인지 알 수 없다([LaneFault] KDoc 과 같은 문제).
 */
private class IllustrationLaneProvider(
    private val delegate: LlmProvider,
    private val budget: IllustrationLaneBudget,
) : LlmProvider {
    override val name: String get() = delegate.name

    /** 감싼 사실을 숨긴다 — 「무엇으로 쟀는지」는 제품 provider 의 설명이어야 한다. */
    override fun toString(): String = delegate.toString()

    var lastFault: LaneFault? = null
        private set

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        val reservation = budget.reserveText(prompt, options)
        var actualUsd: BigDecimal? = null
        try {
            return delegate.complete(prompt, options).also { actualUsd = it.estimatedCostUsd }
        } catch (exc: LlmProviderException) {
            lastFault = LaneFaults.of(exc)
            throw exc
        } finally {
            budget.settle(reservation, actualUsd)
        }
    }
}

/** 문서 한 건을 도는 동안 함께 끌고 다니는 레인 재료([LaneMaterials] 와 같은 자리). */
private class IllustrationLaneMaterials(
    val budget: IllustrationLaneBudget,
    val stopRule: IllustrationLaneStopRule,
    val ledger: IllustrationLaneLedger,
    val artifacts: IllustrationLaneArtifacts,
)

/**
 * 표본 한 건의 측정 — 변환 → 숫자-only 가드 → 제안 → 생성.
 *
 * 각 단계는 원장 한 줄과 중단 신호 하나를 남기고, 다음 단계로 갈 수 없으면 조용히 끝낸다
 * (변환이 실패한 문서에는 제안할 본문이 없다).
 */
private class IllustrationLaneDocumentRun(
    private val converter: ConvertDocumentUseCase,
    private val provider: IllustrationLaneProvider,
    private val suggestionOptions: LlmOptions,
    private val images: IllustrationLaneImages,
    private val materials: IllustrationLaneMaterials,
) {
    fun run(document: GoldenDocument) {
        val easyBody = convert(document) ?: return
        if (isNumericOnlySource(splitUnits(document.sourceText))) {
            // 제품의 숫자-only 가드는 `IllustrationSuggestionValidator.validate` 가 **응답을 받은
            // 뒤** 모든 제안을 버리는 자리에 있다 — 사용자에게 남는 결과는 언제나 제안 0건이다.
            // 레인은 그 결과를 사기 전에 끊는다(§12.1 예산). 원장의 이 줄이 「호출하지 않았다」는
            // 사실 자체다.
            materials.artifacts.writeSuggestions(document.id, IllustrationLaneJson.numericOnlySkip())
            record(IllustrationLaneLedgerRow(document.id, STEP_SUGGEST, "skipped_numeric_only"))
            return
        }
        suggest(document, easyBody)?.let { suggestions -> generate(document.id, suggestions) }
    }

    private fun convert(document: GoldenDocument): String? {
        val startedAt = System.nanoTime()
        val result = converter.convert(document.sourceText)
        val latencyMs = elapsedMillis(startedAt)
        return when (result) {
            is ConversionResult.Converted -> {
                materials.artifacts.writeEasyText(document.id, result.easyText.value)
                record(conversionRow(document.id, "converted", latencyMs, result.usage))
                materials.stopRule.record(IllustrationLaneSignal.HEALTHY)
                result.easyText.value
            }

            is ConversionResult.Failed -> {
                record(conversionRow(document.id, result.kind.name.lowercase(), latencyMs, result.usage))
                materials.stopRule.record(
                    provider.lastFault?.let(::illustrationLaneSignalOf) ?: IllustrationLaneSignal.HEALTHY,
                )
                null
            }
        }
    }

    /** `ProviderIllustrationSuggestionJobRunner.call` 과 같은 순서·판정이다. */
    @Suppress("ReturnCount") // 호출 실패·절단·구조 위반은 서로 다른 기록이라 조기 반환한다(제품 runner 와 같다).
    private fun suggest(
        document: GoldenDocument,
        easyBody: String,
    ): IllustrationSuggestionSet? {
        val prompt = LlmPrompt.forIllustrationSuggestions(document.sourceText, easyBody)
        val startedAt = System.nanoTime()
        val completion =
            try {
                provider.complete(prompt, suggestionOptions)
            } catch (failure: LlmProviderException) {
                record(IllustrationLaneLedgerRow(document.id, STEP_SUGGEST, "provider_error", elapsedMillis(startedAt)))
                materials.stopRule.record(illustrationLaneSignalOf(LaneFaults.of(failure)))
                return null
            }
        val latencyMs = completion.latencyMs ?: elapsedMillis(startedAt)
        if (completion.finishReason != LlmFinishReason.END_TURN || completion.text.isBlank()) {
            return recordInvalidSuggestion(document.id, completion, latencyMs, truncatedOutcome(completion))
        }
        val analysis =
            IllustrationSuggestionParser.parseAndValidate(
                completion.text,
                splitUnits(document.sourceText),
                splitUnits(easyBody),
            )
        return when (analysis) {
            is IllustrationSuggestionAnalysis.Valid -> {
                materials.artifacts.writeSuggestions(
                    document.id,
                    IllustrationLaneJson.suggestions(completion.text, analysis.suggestions),
                )
                record(suggestionRow(document.id, "valid", latencyMs, completion))
                materials.stopRule.record(IllustrationLaneSignal.HEALTHY)
                analysis.suggestions
            }

            IllustrationSuggestionAnalysis.InvalidStructure -> {
                recordInvalidSuggestion(document.id, completion, latencyMs, "invalid")
            }
        }
    }

    private fun recordInvalidSuggestion(
        documentId: String,
        completion: LlmCompletion,
        latencyMs: Long,
        outcome: String,
    ): IllustrationSuggestionSet? {
        materials.artifacts.writeSuggestions(documentId, IllustrationLaneJson.suggestions(completion.text, null))
        record(suggestionRow(documentId, outcome, latencyMs, completion))
        materials.stopRule.record(IllustrationLaneSignal.HEALTHY)
        return null
    }

    /** §12.1 「유효 제안 중 앞의 최대 2건을 제안이 준 장면·보존 사실·대체텍스트 전부로 1장씩」. */
    private fun generate(
        documentId: String,
        suggestions: IllustrationSuggestionSet,
    ) {
        suggestions.suggestions
            .take(ILLUSTRATION_LANE_IMAGES_PER_DOCUMENT)
            .forEachIndexed { index, suggestion ->
                if (!materials.stopRule.stopped) generateOne(documentId, index + 1, suggestion)
            }
    }

    private fun generateOne(
        documentId: String,
        index: Int,
        suggestion: IllustrationSuggestion,
    ) {
        val configuration =
            IllustrationGenerationConfiguration(
                scenes = suggestion.scenes.take(MAX_GENERATION_SCENES),
                preservedFacts = suggestion.preservedFacts.take(MAX_GENERATION_FACTS),
                altText = suggestion.altTextDraft,
            )
        try {
            validateIllustrationGenerationConfiguration(suggestion, configuration)
        } catch (rejected: IllegalArgumentException) {
            // 검증을 통과한 제안에서 만든 구성이라 평소에는 나지 않는다. 나면 제품 규칙과
            // 레인 조립이 어긋났다는 측정 결과이지 레인 구성 오류가 아니다.
            println("illustration lane: $documentId image-$index 구성 거절 — ${rejected.message}")
            record(IllustrationLaneLedgerRow(documentId, stepOf(index), "configuration_invalid"))
            return
        }
        callImage(documentId, index, suggestion, configuration)
    }

    private fun callImage(
        documentId: String,
        index: Int,
        suggestion: IllustrationSuggestion,
        configuration: IllustrationGenerationConfiguration,
    ) {
        val prompt = buildIllustrationGenerationPrompt(suggestion, configuration)
        val reservation = materials.budget.reserveImage()
        val startedAt = System.nanoTime()
        var generated: GeneratedIllustration? = null
        var failure: LlmProviderException? = null
        try {
            generated = images.provider.generate(IllustrationGenerationRequest(prompt))
        } catch (exc: LlmProviderException) {
            failure = exc
        }
        val latencyMs = elapsedMillis(startedAt)
        // 실패한 호출의 비용은 알 수 없다 — 예약액을 그대로 지출로 남긴다.
        val actualUsd = if (generated == null) null else images.costUsdPerImage ?: reservation
        materials.budget.settle(reservation, actualUsd)
        if (generated == null) {
            recordImageFailure(documentId, index, checkNotNull(failure), latencyMs)
            return
        }
        materials.artifacts.writeImage(documentId, index, generated.pngBytes)
        materials.artifacts.writeImageMetadata(
            documentId,
            index,
            IllustrationLaneJson.image(suggestion, configuration, prompt),
        )
        record(
            IllustrationLaneLedgerRow(
                documentId = documentId,
                step = stepOf(index),
                outcome = "succeeded",
                latencyMs = latencyMs,
                estimatedUsd = actualUsd,
            ),
        )
        materials.stopRule.record(IllustrationLaneSignal.HEALTHY)
    }

    /**
     * 제품 `ProviderIllustrationGenerationJobRunner` 와 같은 가름이다 — 타임아웃·I/O 중단은
     * provider 의 생성·청구 결과를 확정할 수 없어 `outcome_unknown` 이고, 응답 형식 오류
     * (= `GeneratedPngValidator` 판정)는 `result_invalid`, 나머지는 `generation_failed` 다.
     */
    @Suppress("InstanceOfCheckForException")
    private fun recordImageFailure(
        documentId: String,
        index: Int,
        failure: LlmProviderException,
        latencyMs: Long,
    ) {
        val unknown = failure is IllustrationProviderTimeoutException || failure is IllustrationProviderIoException
        val outcome =
            when {
                unknown -> "outcome_unknown"
                failure is IllustrationResultException -> "result_invalid"
                else -> "generation_failed"
            }
        record(
            IllustrationLaneLedgerRow(
                documentId = documentId,
                step = stepOf(index),
                outcome = outcome,
                latencyMs = latencyMs,
                estimatedUsd = ILLUSTRATION_IMAGE_WORST_CASE_USD,
            ),
        )
        materials.stopRule.record(
            if (unknown) {
                IllustrationLaneSignal.OUTCOME_UNKNOWN
            } else {
                illustrationLaneSignalOf(LaneFaults.of(failure))
            },
        )
    }

    private fun record(row: IllustrationLaneLedgerRow) {
        materials.ledger.add(row, materials.budget.spentUsd)
    }

    private fun conversionRow(
        documentId: String,
        outcome: String,
        latencyMs: Long,
        usage: ConversionUsage,
    ): IllustrationLaneLedgerRow =
        IllustrationLaneLedgerRow(
            documentId = documentId,
            step = STEP_CONVERT,
            outcome = outcome,
            latencyMs = latencyMs,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            estimatedUsd = usage.calls.mapNotNull { it.estimatedCostUsd }.fold(BigDecimal.ZERO, BigDecimal::add),
        )

    private fun suggestionRow(
        documentId: String,
        outcome: String,
        latencyMs: Long,
        completion: LlmCompletion,
    ): IllustrationLaneLedgerRow =
        IllustrationLaneLedgerRow(
            documentId = documentId,
            step = STEP_SUGGEST,
            outcome = outcome,
            latencyMs = latencyMs,
            inputTokens = completion.inputTokens,
            outputTokens = completion.outputTokens,
            estimatedUsd = completion.estimatedCostUsd,
        )

    private fun truncatedOutcome(completion: LlmCompletion): String =
        if (completion.truncated) "truncated" else "invalid"

    private fun stepOf(index: Int): String = "generate-$index"

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0))

    private companion object {
        const val STEP_CONVERT: String = "convert"
        const val STEP_SUGGEST: String = "suggest"
    }
}

/**
 * 산출물 JSON. 표본이 공개 행정 문서라 제안 본문·원문 인용을 남기는 것은 허용된다(§12.1) —
 * 사람이 ①~④ 를 채점하려면 그 값들이 있어야 한다. 이미지 쪽은 **프롬프트 전문 대신 길이**만
 * 남긴다: 프롬프트에 새로 생기는 사실이 없고, 구성·대체텍스트가 그 내용을 이미 담는다.
 */
private object IllustrationLaneJson {
    private val JSON: JsonMapper = JsonMapper.builder().build()

    fun numericOnlySkip(): String =
        write(
            JSON.createObjectNode().apply {
                put("analysis", "skipped_numeric_only")
                put("reason", "isNumericOnlySource 가 참이라 제안 호출을 시작하지 않았다(계획 §12.1 표본)")
            },
        )

    fun suggestions(
        rawText: String,
        suggestions: IllustrationSuggestionSet?,
    ): String {
        val root = JSON.createObjectNode()
        root.put("raw_text", rawText)
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

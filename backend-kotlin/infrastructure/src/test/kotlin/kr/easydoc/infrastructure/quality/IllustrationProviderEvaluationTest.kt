package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.application.conversion.ConversionUsage
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.easyread.ExplanationPromptVersion
import kr.easydoc.core.easyread.StructureHintOptions
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
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * P0-1 실제 provider 평가 레인 — 계획 §12
 * (`docs/plans/2026-11-07-illustration-explanation-improvement-plan.md`).
 *
 * `./gradlew :infrastructure:testLlm` 으로만 열리며(`@Tag("llm")`), 선택한 provider 의 비밀값이
 * 없으면 skip 한다. 표본마다 **제품과 같은 경로**를 그대로 지난다:
 * 변환(`ConvertDocumentUseCase`) →
 * 제안(`LlmPrompt.forIllustrationSuggestions` + `IllustrationSuggestionParser.parseAndValidate`,
 * `ProviderIllustrationSuggestionJobRunner.call` 과 같은 순서·판정) →
 * 생성(`buildIllustrationGenerationPrompt` + `OpenAiImageGenerationProvider`).
 *
 * **숫자-only 원문에서도 호출을 거르지 않는다.** 제품의 가드는 응답을 받은 뒤
 * `IllustrationSuggestionValidator.validate` 가 제안을 전부 버리는 자리에 있고, 호출 전에
 * 끊는 것은 fake runner 뿐이다 — 레인이 끊으면 제품과 다른 길을 재게 되고, 모델이 억지
 * 제안을 냈는지(계획 §12.2 ①′)도 볼 수 없다. `isNumericOnlySource` 판정은 산출물
 * `suggestions.json` 의 `numeric_only_source` 에 **정보로만** 실린다. PNG 검증은
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
        // 무엇을 재는지를 **조건 줄을 쓰기 전에** 확인한다 — 모델 미설정은 어댑터 기본값으로
        // 접히므로, 그대로 돌면 계획 §12.1 이 아닌 것을 재고도 쟀다고 적게 된다.
        illustrationLaneModelRejection(System::getenv)?.let { fail<Unit>(it) }
        val budgetUsd = laneBudget()
        val spendLimit = spendLimitFor(ready, budgetUsd)
        val imageSettings = resolveImageSettings()
        val suggestionMaxTokens = suggestionMaxOutputTokens()
        val selection = selectDocuments()
        val documentIds = selection.documents.map(GoldenDocument::id)
        val dictionary = planDictionary(documentIds)
        val artifacts = planArtifacts(documentIds)

        // 텍스트 최악 비용의 상계식은 골든 레인과 한 벌만 둔다([LaneSpendLimit.reservationFor]).
        val budget = IllustrationLaneBudget(budgetUsd, textWorstCaseUsd = spendLimit::reservationFor)
        val provider = IllustrationLaneProvider(ready.provider, budget)
        val ledger = IllustrationLaneLedger()
        val stopRules = IllustrationLaneStopRules()

        val conditions =
            listOf(
                ready.description,
                "suggestion_max_tokens=$suggestionMaxTokens",
                imageSettings.description,
                "images_per_document<=$ILLUSTRATION_LANE_IMAGES_PER_DOCUMENT retries=0",
                "explanation_version=${LANE_EXPLANATION_VERSION.name} " +
                    "structure_max_runs=${StructureHintOptions.DEFAULT_MAX_RUNS} " +
                    "reading_level=${LANE_READING_LEVEL.wireName}",
                dictionary.description,
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
                // 제품 조립과 같은 자리에 사전 포트를 넘긴다(GoldenCorpusLlmEvaluationTest 와 같다).
                converter =
                    ConvertDocumentUseCase(
                        provider,
                        defaultOptions = ready.options,
                        explanationPromptVersion = LANE_EXPLANATION_VERSION,
                        dictionary = dictionary.contextSource,
                    ),
                provider = provider,
                suggestionOptions = LlmOptions(maxTokens = suggestionMaxTokens),
                images = imageSettings.images(),
                materials = IllustrationLaneMaterials(budget, stopRules, ledger, artifacts, dictionary),
            )

        // 문서를 **순차로** 돈다 — 동시 호출은 예산 선검사와 연속 실패 판정을 함께 무너뜨린다.
        for (document in selection.documents) {
            if (stopRules.stopped || runOrStop(run, document, ledger, budget)) break
        }

        artifacts.writeLedger(ledger.render())
        println(ledger.render())
        stopRules.stopReason?.let { println("lane stopped: $it") }
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
        val byId = (corpus + NUMERIC_ONLY_LANE_DOCUMENT).associateBy(GoldenDocument::id)
        // 풀 자체가 계획 §12.1 의 13건이다 — 코퍼스 전건이 아니라. 환경변수로 고를 수 있는 것은
        // 그 안의 부분집합뿐이고(승인 범위), 바깥 id 는 아래에서 먼저 거절한다.
        val pool =
            PLAN_SAMPLE_IDS.map { id -> byId[id] ?: fail<GoldenDocument>("계획 §12.1 표본 '$id' 가 코퍼스에 없다") }
        illustrationLaneSampleRejection(System.getenv(LaneDocuments.ENV), PLAN_SAMPLE_IDS)
            ?.let { fail<LaneDocumentSelection>(it) }
        return try {
            LaneDocuments.select(System::getenv, pool)
        } catch (exc: ConfigurationException) {
            fail<LaneDocumentSelection>(exc.message)
        }
    }

    /**
     * 제품 변환은 사전 컨텍스트를 싣는다(파일럿은 `EASYDOC_DICTIONARY_ENABLED=true`). 레인도
     * 같은 포트를 넘긴다 — `EASYDOC_LANE_DICT_PRODUCT` 를 설정하면 제품 composition root 가
     * 조립한 실제 소스를 쓰고(색인은 클래스패스 리소스라 파일럿 DB 가 필요 없다), 미설정이면
     * 사전 없이 돈다. 어느 쪽이었는지는 조건 줄에 `dictContext=` 로 남는다.
     */
    private fun planDictionary(documentIds: List<String>): LaneDictionary =
        when (val plan = LaneDictionary.plan(System::getenv, documentIds)) {
            is LaneDictionaryPlan.Ready -> plan.dictionary
            is LaneDictionaryPlan.Unusable -> fail<LaneDictionary>(plan.reason)
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
        try {
            illustrationLaneBudgetOf(System.getenv(ILLUSTRATION_LANE_MAX_USD_ENV))
        } catch (exc: ConfigurationException) {
            fail<BigDecimal>(exc.message)
        }

    private companion object {
        /** 제품 worker `application.yml` 과 같은 이름이다. */
        const val SUGGESTION_MAX_OUTPUT_TOKENS_ENV: String = "EASYDOC_ILLUSTRATION_SUGGESTIONS_MAX_OUTPUT_TOKENS"

        /**
         * 계획 §12.1 표본 13건 — 절차 `001·010·015·089`, 비교·관계 `041·090·097`,
         * 조건·예외 `005·016·081`, 무제안 예상 `052·063`, 숫자-only 합성 1건.
         *
         * 텍스트 provider·모델도 같은 절이 고정한다 — [ILLUSTRATION_LANE_TEXT_PROVIDER]
         * (`openai`)와 [ILLUSTRATION_LANE_TEXT_MODEL](`gpt-6-astra`)이고,
         * [illustrationLaneModelRejection] 이 유료 호출 전에 확인한다.
         */
        val PLAN_SAMPLE_IDS: List<String> =
            listOf("001", "010", "015", "089", "041", "090", "097", "005", "016", "081", "052", "063") +
                NUMERIC_ONLY_LANE_DOCUMENT.id
    }
}

/**
 * 레인이 고정한 변환 조건 — 계획 §12 는 한 조건만 재므로 노브로 받지 않는다. 대신 조건 줄에
 * 그대로 적어, 디렉터리를 나중에 여는 사람이 무엇으로 잰 결과인지 알게 한다.
 */
private val LANE_EXPLANATION_VERSION: ExplanationPromptVersion = ExplanationPromptVersion.BASELINE

private val LANE_READING_LEVEL: ReadingLevel = ReadingLevel.GRADE_5_6

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
                // 값 규칙은 [illustrationLaneImageCostOf] 가 정본이다 — 유료 호출 없이 시험한다.
                costUsdPerImage = illustrationLaneImageCostOf(env(COST_ENV)),
            )
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
        // 호출마다 지운다. 남겨 두면 변환 1차 호출의 4xx 가 — 그 호출이 성공했는데 보정 호출이
        // 절단돼 `Failed(TRUNCATED)` 로 끝난 문서에서 — 예외 없이 끝난 실패의 원인으로 잘못
        // 읽혀 중단 규칙이 엉뚱한 신호를 센다.
        lastFault = null
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
    val stopRules: IllustrationLaneStopRules,
    val ledger: IllustrationLaneLedger,
    val artifacts: IllustrationLaneArtifacts,
    val dictionary: LaneDictionary,
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
        suggest(document, easyBody)?.let { suggestions -> generate(document.id, suggestions) }
    }

    private fun convert(document: GoldenDocument): String? {
        val startedAt = System.nanoTime()
        val result =
            try {
                // 사전 컨텍스트는 제품 조립 모드에서 포트가 대고, 파일 주입 모드에서만 명시
                // 인자가 온다(`LaneDictionary` KDoc 「두 방식」) — 골든 레인과 같은 호출이다.
                converter.convert(
                    document.sourceText,
                    dictionaryContext = materials.dictionary.contextFor(document.id),
                    readingLevel = LANE_READING_LEVEL,
                )
            } catch (stop: IllustrationLaneBudgetStop) {
                // 1차 호출이 끝난 뒤 보정 호출에서 멈췄다면 그 지출은 이미 정산됐다 — 어디까지
                // 샀는지 원장에 남기고 중단을 그대로 올린다.
                record(IllustrationLaneLedgerRow(document.id, STEP_CONVERT, "budget_stop", elapsedMillis(startedAt)))
                throw stop
            }
        val latencyMs = elapsedMillis(startedAt)
        return when (result) {
            is ConversionResult.Converted -> {
                materials.artifacts.writeEasyText(document.id, result.easyText.value)
                record(conversionRow(document.id, "converted", latencyMs, result.usage))
                materials.stopRules.convert.record(IllustrationLaneSignal.HEALTHY)
                result.easyText.value
            }

            is ConversionResult.Failed -> {
                record(conversionRow(document.id, result.kind.name.lowercase(), latencyMs, result.usage))
                materials.stopRules.convert.record(
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
        val sourceUnits = splitUnits(document.sourceText)
        // 제품은 숫자-only 원문에서도 provider 를 부른다 — 가드는 응답을 받은 뒤
        // `IllustrationSuggestionValidator.validate` 가 제안을 전부 버리는 자리에 있고
        // (`NumericOnlySource.kt` 의 유일한 제품 소비자), 호출 전에 끊는 것은 fake runner 뿐이다.
        // 레인도 끊지 않는다: 끊으면 모델이 억지 제안을 냈는지(계획 §12.2 ①′)를 볼 수 없다.
        // 이 값은 산출물에 정보로만 실린다.
        val numericOnlySource = isNumericOnlySource(sourceUnits)
        val prompt = LlmPrompt.forIllustrationSuggestions(document.sourceText, easyBody)
        val startedAt = System.nanoTime()
        val completion =
            try {
                provider.complete(prompt, suggestionOptions)
            } catch (failure: LlmProviderException) {
                record(IllustrationLaneLedgerRow(document.id, STEP_SUGGEST, "provider_error", elapsedMillis(startedAt)))
                materials.stopRules.suggest.record(illustrationLaneSignalOf(LaneFaults.of(failure)))
                return null
            }
        val latencyMs = completion.latencyMs ?: elapsedMillis(startedAt)
        if (completion.finishReason != LlmFinishReason.END_TURN || completion.text.isBlank()) {
            val outcome = truncatedOutcome(completion)
            return invalidSuggestion(document.id, completion, outcome, latencyMs, numericOnlySource)
        }
        val analysis =
            IllustrationSuggestionParser.parseAndValidate(completion.text, sourceUnits, splitUnits(easyBody))
        return when (analysis) {
            is IllustrationSuggestionAnalysis.Valid -> {
                materials.artifacts.writeSuggestions(
                    document.id,
                    IllustrationLaneJson.suggestions(completion.text, analysis.suggestions, numericOnlySource),
                )
                record(suggestionRow(document.id, "valid", latencyMs, completion))
                materials.stopRules.suggest.record(IllustrationLaneSignal.HEALTHY)
                analysis.suggestions
            }

            IllustrationSuggestionAnalysis.InvalidStructure -> {
                invalidSuggestion(document.id, completion, "invalid", latencyMs, numericOnlySource)
            }
        }
    }

    private fun invalidSuggestion(
        documentId: String,
        completion: LlmCompletion,
        outcome: String,
        latencyMs: Long,
        numericOnlySource: Boolean,
    ): IllustrationSuggestionSet? {
        materials.artifacts.writeSuggestions(
            documentId,
            IllustrationLaneJson.suggestions(completion.text, null, numericOnlySource),
        )
        record(suggestionRow(documentId, outcome, latencyMs, completion))
        materials.stopRules.suggest.record(IllustrationLaneSignal.HEALTHY)
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
                if (!materials.stopRules.stopped) generateOne(documentId, index + 1, suggestion)
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
        } finally {
            // 어떤 예외로 끝나든 예약은 정산한다 — 설정 오류처럼 여기서 잡지 않는 예외가
            // 예약을 떼어먹고 나가면 상한이 조용히 헐거워진다. 실패한 호출의 비용은 알 수
            // 없으므로 예약액을 그대로 지출로 남긴다.
            materials.budget.settle(
                reservation,
                if (generated == null) null else images.costUsdPerImage ?: reservation,
            )
        }
        val latencyMs = elapsedMillis(startedAt)
        val actualUsd = if (generated == null) null else images.costUsdPerImage ?: reservation
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
        materials.stopRules.generate.record(IllustrationLaneSignal.HEALTHY)
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
                // 실패한 호출이 실제로 청구됐는지는 알 수 없다 — 빈 칸으로 둔다. 보수적으로
                // 붙잡아 둔 예약액은 같은 줄의 cumulative_usd 에 이미 들어 있다.
                estimatedUsd = null,
            ),
        )
        materials.stopRules.generate.record(
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
            // 단가 미설정·호출 실패로 비용을 모르면 **빈 칸**이다 — 0으로 적으면 「0달러를
            // 썼다」로 읽힌다(LlmCompletion.estimatedCostUsd 와 같은 규칙).
            estimatedUsd =
                usage.calls
                    .mapNotNull { it.estimatedCostUsd }
                    .takeIf { it.isNotEmpty() }
                    ?.fold(BigDecimal.ZERO, BigDecimal::add),
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

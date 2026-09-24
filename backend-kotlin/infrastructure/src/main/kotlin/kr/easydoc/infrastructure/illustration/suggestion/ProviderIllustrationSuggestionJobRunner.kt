package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRunner
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionProviderCall
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionRunResult
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionAnalysis
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionParser
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.segment.splitUnits
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * 저장된 원문과 현재 본문으로 **단 한 번** 완성을 요청하고, 원문 근거가 맞는 제안만 통과시킨다
 * (명세 §5: 호출 1회, 자동 재시도 없음).
 *
 * 세 갈래로 끝난다.
 * - [IllustrationSuggestionRunResult.ProviderFailed]: 호출 자체가 실패했다.
 * - [IllustrationSuggestionRunResult.Invalid]: 응답은 받았지만 절단·빈 응답이거나, 구조가
 *   어긋났거나, 제안이 전부 원문 대조에서 탈락했다 — 셋 다 `result_invalid` 이고 반환한다.
 * - [IllustrationSuggestionRunResult.Valid]: 제안 0건('제안 없음')도 여기다 — 정상 결과라 소비한다.
 */
class ProviderIllustrationSuggestionJobRunner(
    private val input: IllustrationSuggestionInputSource,
    private val provider: LlmProvider,
    private val maxOutputTokens: Int,
    private val clock: Clock = Clock.systemUTC(),
) : IllustrationSuggestionJobRunner {
    init {
        require(maxOutputTokens > 0) { "출력 상한은 양수여야 합니다: $maxOutputTokens" }
    }

    /** 입력을 먼저 확정한다. 시작 트랜잭션 안에서 불리므로 여기서 없으면 호출을 시작하지 않는다. */
    override fun prepare(job: StoredIllustrationSuggestionJob): IllustrationSuggestionProviderCall? {
        val loaded = input.load(job) ?: return null
        return IllustrationSuggestionProviderCall { call(loaded) }
    }

    @Suppress("ReturnCount") // 호출 실패·절단·검증 실패는 서로 다른 정산 결과여서 조기 반환한다.
    private fun call(loaded: IllustrationSuggestionGenerationInput): IllustrationSuggestionRunResult {
        val prompt = LlmPrompt.forIllustrationSuggestions(loaded.sourceText, loaded.savedBody)
        val inputChars = loaded.sourceText.length + loaded.savedBody.length
        val startedAt = System.nanoTime()
        val completion =
            try {
                provider.complete(prompt, LlmOptions(maxTokens = maxOutputTokens))
            } catch (failure: LlmProviderException) {
                return IllustrationSuggestionRunResult.ProviderFailed(
                    LlmCallRecord(
                        purpose = LlmCallPurpose.ILLUSTRATION_SUGGESTION,
                        provider = provider.name,
                        model = null,
                        inputTokens = 0,
                        outputTokens = 0,
                        latencyMs = elapsedMillis(startedAt),
                        estimatedCostUsd = null,
                        pricingInputUsdPerMtok = null,
                        pricingOutputUsdPerMtok = null,
                        charCount = inputChars,
                        calledAt = clock.instant(),
                        outcome = LlmCallOutcome.PROVIDER_ERROR,
                        failureClass = failure.javaClass.simpleName,
                    ),
                )
            }
        val record = completion.toRecord(inputChars, elapsedMillis(startedAt))
        if (completion.finishReason != LlmFinishReason.END_TURN || completion.text.isBlank()) {
            return IllustrationSuggestionRunResult.Invalid(record)
        }
        // 프롬프트가 붙인 줄 번호와 같은 나눔이어야 `body_range`·`source_unit_indexes` 가 같은
        // 좌표계를 가리킨다(`LlmPrompt.forIllustrationSuggestions` 는 양쪽에 splitUnits 를 쓴다).
        val savedBodyUnits = splitUnits(loaded.savedBody)
        return when (
            val analysis =
                IllustrationSuggestionParser.parseAndValidate(
                    completion.text,
                    splitUnits(loaded.sourceText),
                    savedBodyUnits,
                )
        ) {
            is IllustrationSuggestionAnalysis.Valid -> {
                IllustrationSuggestionRunResult.Valid(record, analysis.suggestions)
            }

            is IllustrationSuggestionAnalysis.AllDropped -> {
                IllustrationSuggestionRunResult.Invalid(record)
            }

            IllustrationSuggestionAnalysis.InvalidStructure -> {
                IllustrationSuggestionRunResult.Invalid(record)
            }
        }
    }

    private fun LlmCompletion.toRecord(
        inputChars: Int,
        measuredLatencyMs: Long,
    ): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ILLUSTRATION_SUGGESTION,
            provider = provider,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs ?: measuredLatencyMs,
            estimatedCostUsd = estimatedCostUsd,
            pricingInputUsdPerMtok = pricingInputUsdPerMtok,
            pricingOutputUsdPerMtok = pricingOutputUsdPerMtok,
            charCount = inputChars,
            calledAt = clock.instant(),
            outcome = LlmCallOutcome.COMPLETED,
        )

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0))
}

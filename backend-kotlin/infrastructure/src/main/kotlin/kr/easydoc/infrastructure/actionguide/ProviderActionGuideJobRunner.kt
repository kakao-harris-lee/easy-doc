package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideJobRunner
import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.LlmProviderException
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

/** 저장된 원문과 현재 본문으로 단 한 번 완성을 요청하고, 원문 근거가 맞는 후보만 통과시킨다. */
class ProviderActionGuideJobRunner(
    private val input: ActionGuideInputSource,
    private val provider: LlmProvider,
    private val clock: Clock = Clock.systemUTC(),
) : ActionGuideJobRunner {
    @Suppress("ReturnCount") // 호출 실패·절단·검증 실패는 서로 다른 정산 결과여서 조기 반환한다.
    override fun run(job: StoredActionGuideJob): ActionGuideRunResult {
        val loaded = input.load(job) ?: error("행동 안내 생성 입력이 더 이상 유효하지 않습니다")
        val prompt = LlmPrompt.forActionGuide(loaded.sourceText, loaded.savedBody)
        val inputChars = loaded.sourceText.length + loaded.savedBody.length
        val startedAt = System.nanoTime()
        val completion =
            try {
                provider.complete(prompt, LlmOptions(maxTokens = MAX_OUTPUT_TOKENS))
            } catch (failure: LlmProviderException) {
                return ActionGuideRunResult.ProviderFailed(
                    LlmCallRecord(
                        purpose = LlmCallPurpose.ACTION_GUIDE,
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
            return ActionGuideRunResult.Invalid(record)
        }
        val candidate =
            try {
                ActionGuideCandidateParser.parseAndValidate(completion.text, splitUnits(loaded.sourceText))
            } catch (_: InvalidInputException) {
                return ActionGuideRunResult.Invalid(record)
            }
        return ActionGuideRunResult.Valid(record, candidate)
    }

    private fun LlmCompletion.toRecord(
        inputChars: Int,
        measuredLatencyMs: Long,
    ): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ACTION_GUIDE,
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

    companion object {
        const val MAX_OUTPUT_TOKENS: Int = 8_192
    }
}

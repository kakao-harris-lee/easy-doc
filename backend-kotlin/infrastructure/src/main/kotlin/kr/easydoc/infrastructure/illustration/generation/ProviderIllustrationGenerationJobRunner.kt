package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRunner
import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.application.illustration.generation.IllustrationGenerationProviderCall
import kr.easydoc.application.illustration.generation.IllustrationGenerationRunResult
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.IllustrationProviderIoException
import kr.easydoc.core.exceptions.IllustrationProviderTimeoutException
import kr.easydoc.core.exceptions.IllustrationResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.generation.IllustrationGenerationInput
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import java.math.BigDecimal
import java.time.Clock
import java.util.concurrent.TimeUnit

class ProviderIllustrationGenerationJobRunner(
    private val cipher: ContentCipher,
    private val provider: IllustrationGenerationProvider,
    private val providerCostUsdPerImage: BigDecimal? = null,
    private val clock: Clock = Clock.systemUTC(),
) : IllustrationGenerationJobRunner {
    override fun prepare(job: StoredIllustrationGenerationJob): IllustrationGenerationProviderCall {
        val input =
            IllustrationGenerationStorageCodec.decode(
                cipher.decrypt(job.inputPayload, job.jobId, EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT).value,
            )
        val actualMode = if (provider.name == "fake") "fake" else "provider"
        check(input.executionMode == actualMode) { "그림 생성 실행 모드가 접수 시점과 일치하지 않습니다" }
        return IllustrationGenerationProviderCall { call(input) }
    }

    @Suppress("InstanceOfCheckForException")
    private fun call(input: IllustrationGenerationInput): IllustrationGenerationRunResult {
        val startedAt = System.nanoTime()
        return try {
            val generated =
                provider.generate(IllustrationGenerationRequest(input.finalPrompt)).let {
                    it.copy(record = it.record.copy(estimatedCostUsd = providerCostUsdPerImage))
                }
            IllustrationGenerationRunResult.Succeeded(generated, input)
        } catch (failure: LlmProviderException) {
            // 전송 중단 뒤에는 provider의 생성·청구 결과를 확정할 수 없다.
            // worker의 기존 outcome_unknown 정산으로 넘겨 비용 null과 재호출 금지를 유지한다.
            if (failure is IllustrationProviderTimeoutException || failure is IllustrationProviderIoException) {
                throw failure
            }
            IllustrationGenerationRunResult.Failed(
                LlmCallRecord(
                    purpose = LlmCallPurpose.ILLUSTRATION_GENERATION,
                    provider = provider.name,
                    model = null,
                    inputTokens = 0,
                    outputTokens = 0,
                    latencyMs = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0)),
                    estimatedCostUsd = null,
                    pricingInputUsdPerMtok = null,
                    pricingOutputUsdPerMtok = null,
                    charCount = input.finalPrompt.codePointCount(0, input.finalPrompt.length),
                    calledAt = clock.instant(),
                    outcome = LlmCallOutcome.PROVIDER_ERROR,
                    failureClass = failure::class.java.simpleName,
                ),
                if (failure is IllustrationResultException) {
                    IllustrationGenerationJobFailureCode.RESULT_INVALID
                } else {
                    IllustrationGenerationJobFailureCode.GENERATION_FAILED
                },
            )
        }
    }
}

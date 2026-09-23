package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 입력 확정과 호출을 한 줄로 이어 붙인다 — 확정에 실패하는 경우는 전용 테스트가 따로 본다. */
private fun ProviderActionGuideJobRunner.runOnce(job: StoredActionGuideJob) = checkNotNull(prepare(job)).call()

class ProviderActionGuideJobRunnerTest {
    @Test
    fun `검증된 원문 근거 후보만 반환하고 provider를 한 번만 부른다`() {
        val provider = RecordingProvider(validCandidate())
        val input = ActionGuideInputSource { ActionGuideGenerationInput("신청은 3월까지입니다.", "3월까지 신청해요.") }

        val result = ProviderActionGuideJobRunner(input, provider).runOnce(job())

        assertThat(result).isInstanceOf(ActionGuideRunResult.Valid::class.java)
        val valid = result as ActionGuideRunResult.Valid
        assertThat(valid.candidate.sections).hasSize(6)
        assertThat(valid.record.inputTokens).isEqualTo(12)
        assertThat(valid.record.outcome).isEqualTo(LlmCallOutcome.COMPLETED)
        assertThat(provider.calls).isEqualTo(1)
        assertThat(provider.lastOptions?.maxTokens).isEqualTo(8_192)
        assertThat(provider.lastPrompt?.user).contains("신청은 3월까지입니다.", "3월까지 신청해요.")
    }

    @Test
    fun `원문에 없는 인용과 잘린 응답은 후보 실패이며 완성 원장은 보존한다`() {
        val input = ActionGuideInputSource { ActionGuideGenerationInput("신청은 3월까지입니다.", "3월까지 신청해요.") }
        val invented = ProviderActionGuideJobRunner(input, RecordingProvider(validCandidate().replace("3월까지", "4월까지")))
        val truncated =
            ProviderActionGuideJobRunner(input, RecordingProvider(validCandidate(), LlmFinishReason.MAX_TOKENS))

        val invalid = invented.runOnce(job()) as ActionGuideRunResult.Invalid
        val clipped = truncated.runOnce(job()) as ActionGuideRunResult.Invalid

        assertThat(invalid.record.outcome).isEqualTo(LlmCallOutcome.COMPLETED)
        assertThat(invalid.record.outputTokens).isEqualTo(34)
        assertThat(clipped.record.outcome).isEqualTo(LlmCallOutcome.COMPLETED)
    }

    @Test
    fun `provider 실패만 실패 호출로 분류하고 재시도하지 않는다`() {
        val provider = RecordingProvider(validCandidate(), throwsProviderError = true)
        val input = ActionGuideInputSource { ActionGuideGenerationInput("원문", "저장 본문") }

        val failed =
            ProviderActionGuideJobRunner(input, provider).runOnce(job()) as ActionGuideRunResult.ProviderFailed

        assertThat(provider.calls).isEqualTo(1)
        assertThat(failed.record.outcome).isEqualTo(LlmCallOutcome.PROVIDER_ERROR)
        assertThat(failed.record.model).isNull()
        assertThat(failed.record.failureClass).isEqualTo("LlmProviderException")
    }

    @Test
    fun `입력 본문을 다시 찾지 못하면 호출을 시작하지 않는다`() {
        val provider = RecordingProvider(validCandidate())
        val input = ActionGuideInputSource { null }

        // 시작 트랜잭션 안에서 불리는 단계다 — 예외가 아니라 null 로 「시작하지 않음」을 알린다.
        assertThat(ProviderActionGuideJobRunner(input, provider).prepare(job())).isNull()
        assertThat(provider.calls).isZero()
    }

    private fun job(): StoredActionGuideJob =
        StoredActionGuideJob(
            jobId = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            workspaceId = UUID.randomUUID(),
            documentId = UUID.randomUUID(),
            conversionId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
            expectedGuideRevision = null,
            basedOnContentRevision = 1,
            reservedCredits = BigDecimal.ONE,
            status = ActionGuideJobStatus.RUNNING,
            failureCode = null,
            executionId = UUID.randomUUID(),
            providerStartedAt = Instant.now(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun validCandidate(): String =
        """
        {"schema_version":1,"sections":[
          {"kind":"eligibility","status":"available","items":[{"text":"3월까지 신청하세요.","cautions":[],"source_anchors":[{"source_unit_indexes":[0],"quote":"3월까지"}]}]},
          {"kind":"benefits","status":"not_in_source","items":[]},
          {"kind":"documents","status":"not_in_source","items":[]},
          {"kind":"steps","status":"not_in_source","items":[]},
          {"kind":"exceptions","status":"not_in_source","items":[]},
          {"kind":"contact","status":"not_in_source","items":[]}
        ]}
        """.trimIndent()

    private class RecordingProvider(
        private val text: String,
        private val finishReason: LlmFinishReason = LlmFinishReason.END_TURN,
        private val throwsProviderError: Boolean = false,
    ) : LlmProvider {
        override val name: String = "fake"
        var calls: Int = 0
            private set
        var lastOptions: LlmOptions? = null
            private set
        var lastPrompt: LlmPrompt? = null
            private set

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion {
            calls++
            lastOptions = options
            lastPrompt = prompt
            if (throwsProviderError) throw LlmProviderException("sensitive provider response")
            return LlmCompletion(
                text = text,
                provider = name,
                model = "fake-action-guide",
                inputTokens = 12,
                outputTokens = 34,
                finishReason = finishReason,
            )
        }
    }
}

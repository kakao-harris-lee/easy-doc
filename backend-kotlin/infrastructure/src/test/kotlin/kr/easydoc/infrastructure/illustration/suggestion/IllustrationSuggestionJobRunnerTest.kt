package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionRunResult
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * provider·fake runner 가 원장 목적과 정산 갈래를 어떻게 정하는지(명세 §4·§5·§6).
 * 실제 외부 API 는 부르지 않는다 — provider 자리에 대역을 세운다.
 */
class IllustrationSuggestionJobRunnerTest {
    private val sourceText = "신청서를 제출합니다\n담당자가 확인합니다\n결과를 알려 드립니다"
    private val savedBody = "신청서를 냅니다.\n담당자가 봅니다.\n결과를 알려 줍니다."
    private val input =
        IllustrationSuggestionInputSource { IllustrationSuggestionGenerationInput(sourceText, savedBody) }

    @Test
    @DisplayName("유효한 제안 JSON 은 Valid 이고 원장 목적이 illustration_suggestion 이다")
    fun `유효한 응답은 Valid 다`() {
        val runner = ProviderIllustrationSuggestionJobRunner(input, provider(validJson()), MAX_TOKENS)

        val result = runner.prepare(job())!!.call()

        assertThat(result).isInstanceOf(IllustrationSuggestionRunResult.Valid::class.java)
        val valid = result as IllustrationSuggestionRunResult.Valid
        assertThat(valid.record.purpose).isEqualTo(LlmCallPurpose.ILLUSTRATION_SUGGESTION)
        assertThat(valid.record.outcome).isEqualTo(LlmCallOutcome.COMPLETED)
        assertThat(valid.suggestions.analysisVersion).isEqualTo(ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION)
        assertThat(
            valid.suggestions.suggestions
                .single()
                .purpose,
        ).isEqualTo(IllustrationSuggestionPurpose.PROCEDURE)
    }

    @Test
    @DisplayName("제안 0건('제안 없음')도 Valid 다 — 소비 대상이라 Invalid 와 갈라야 한다")
    fun `빈 배열은 Valid 다`() {
        val runner =
            ProviderIllustrationSuggestionJobRunner(
                input,
                provider("""{"schema_version":1,"suggestions":[]}"""),
                MAX_TOKENS,
            )

        val result = runner.prepare(job())!!.call()

        assertThat(result).isInstanceOf(IllustrationSuggestionRunResult.Valid::class.java)
        assertThat((result as IllustrationSuggestionRunResult.Valid).suggestions.suggestions).isEmpty()
    }

    @Test
    @DisplayName("구조 위반·모든 제안 탈락·절단·빈 응답은 모두 Invalid 다")
    fun `쓸 수 없는 응답은 Invalid 다`() {
        val structural = provider("""{"schema_version":2,"suggestions":[]}""")
        // 인용이 원문에 없으면 그 제안은 버려지고, 1건뿐이라 전량 탈락이다.
        val unanchored = provider(validJson(quote = "원문에 없는 문장"))
        val truncated = provider(validJson(), finishReason = LlmFinishReason.MAX_TOKENS)
        val blank = provider("   ")

        listOf(structural, unanchored, truncated, blank).forEach { provider ->
            val result = ProviderIllustrationSuggestionJobRunner(input, provider, MAX_TOKENS).prepare(job())!!.call()

            assertThat(result)
                .describedAs("provider 응답 갈래가 Invalid 로 모이지 않았다")
                .isInstanceOf(IllustrationSuggestionRunResult.Invalid::class.java)
        }
    }

    @Test
    @DisplayName("provider 예외는 ProviderFailed 이고 사용량 없이 실패 분류만 남는다")
    fun `호출 실패는 ProviderFailed 다`() {
        val failing =
            object : LlmProvider {
                override val name: String = "fake"

                override fun complete(
                    prompt: LlmPrompt,
                    options: LlmOptions,
                ): LlmCompletion = throw LlmProviderException("연결 실패")
            }

        val result = ProviderIllustrationSuggestionJobRunner(input, failing, MAX_TOKENS).prepare(job())!!.call()

        assertThat(result).isInstanceOf(IllustrationSuggestionRunResult.ProviderFailed::class.java)
        val record = (result as IllustrationSuggestionRunResult.ProviderFailed).record
        assertThat(record.outcome).isEqualTo(LlmCallOutcome.PROVIDER_ERROR)
        assertThat(record.inputTokens).isZero()
        assertThat(record.estimatedCostUsd).isNull()
        assertThat(record.failureClass).isEqualTo("LlmProviderException")
    }

    @Test
    @DisplayName("입력을 만들지 못하면 prepare 가 null 이다 — 호출을 시작하지 않는다")
    fun `입력이 없으면 호출하지 않는다`() {
        val noInput = IllustrationSuggestionInputSource { null }

        assertThat(ProviderIllustrationSuggestionJobRunner(noInput, provider(validJson()), MAX_TOKENS).prepare(job()))
            .isNull()
    }

    @Test
    @DisplayName("설정된 출력 상한이 provider 옵션으로 그대로 내려간다")
    fun `출력 상한이 옵션으로 내려간다`() {
        val recording = RecordingProvider(validJson())

        ProviderIllustrationSuggestionJobRunner(input, recording, maxOutputTokens = 4_096).prepare(job())!!.call()

        assertThat(recording.options.single().maxTokens).isEqualTo(4_096)
    }

    @Test
    @DisplayName("fake runner 는 원문 첫 비어 있지 않은 줄에 근거를 단 절차 제안 1건을 낸다")
    fun `fake runner 가 결정적인 제안을 낸다`() {
        val result = FakeIllustrationSuggestionJobRunner(input).prepare(job())!!.call()

        assertThat(result).isInstanceOf(IllustrationSuggestionRunResult.Valid::class.java)
        val suggestion = (result as IllustrationSuggestionRunResult.Valid).suggestions.suggestions.single()
        assertThat(suggestion.purpose).isEqualTo(IllustrationSuggestionPurpose.PROCEDURE)
        assertThat(suggestion.sourceAnchors.single().sourceUnitIndexes).containsExactly(0)
        assertThat(suggestion.sourceAnchors.single().quote).isEqualTo("신청서를 제출합니다")
        assertThat(result.record.provider).isEqualTo("fake")
        assertThat(result.record.purpose).isEqualTo(LlmCallPurpose.ILLUSTRATION_SUGGESTION)
    }

    @Test
    fun `fake runner 는 긴 원문도 유니코드 문자를 자르지 않고 근거를 인용한다`() {
        val source = "📄".repeat(1_100)
        val longInput = IllustrationSuggestionInputSource { IllustrationSuggestionGenerationInput(source, savedBody) }

        val result = FakeIllustrationSuggestionJobRunner(longInput).prepare(job())!!.call()

        assertThat(result).isInstanceOf(IllustrationSuggestionRunResult.Valid::class.java)
        val valid = result as IllustrationSuggestionRunResult.Valid
        assertThat(
            valid.suggestions.suggestions
                .single()
                .sourceAnchors
                .single()
                .quote,
        ).isEqualTo("📄".repeat(1_000))
    }

    @Test
    @DisplayName("fake runner 는 원문 앞의 빈 줄을 건너뛰고, 근거로 삼을 줄이 없으면 '제안 없음'이다")
    fun `fake runner 가 빈 원문을 다룬다`() {
        val leadingBlank =
            IllustrationSuggestionInputSource {
                IllustrationSuggestionGenerationInput("\n\n신청서를 제출합니다", savedBody)
            }
        val allBlank = IllustrationSuggestionInputSource { IllustrationSuggestionGenerationInput("\n \n", savedBody) }

        val skipped = FakeIllustrationSuggestionJobRunner(leadingBlank).prepare(job())!!.call()
        val empty = FakeIllustrationSuggestionJobRunner(allBlank).prepare(job())!!.call()

        assertThat(
            (skipped as IllustrationSuggestionRunResult.Valid)
                .suggestions.suggestions
                .single()
                .sourceAnchors,
        ).singleElement()
            .extracting { it.sourceUnitIndexes }
            .isEqualTo(listOf(2))
        assertThat((empty as IllustrationSuggestionRunResult.Valid).suggestions.suggestions).isEmpty()
    }

    private fun validJson(quote: String = "신청서를 제출합니다"): String =
        """
        {"schema_version":1,"suggestions":[{
          "purpose":"procedure",
          "reason":"신청 순서를 그림으로 보면 이해하기 쉽다",
          "body_range":{"start":0,"end":2},
          "source_anchors":[{"source_unit_indexes":[0],"quote":"$quote"}],
          "scenes":["신청서를 내는 장면"],
          "preserved_facts":[],
          "alt_text_draft":"신청 순서를 보여 주는 그림"
        }]}
        """.trimIndent()

    private fun provider(
        text: String,
        finishReason: LlmFinishReason = LlmFinishReason.END_TURN,
    ): LlmProvider = RecordingProvider(text, finishReason)

    private class RecordingProvider(
        private val text: String,
        private val finishReason: LlmFinishReason = LlmFinishReason.END_TURN,
    ) : LlmProvider {
        val options = mutableListOf<LlmOptions>()

        override val name: String = "fake"

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion {
            this.options += options
            return LlmCompletion(
                text = text,
                provider = name,
                model = "fake-model",
                inputTokens = 10,
                outputTokens = 20,
                finishReason = finishReason,
                latencyMs = 5,
                estimatedCostUsd = null,
                pricingInputUsdPerMtok = null,
                pricingOutputUsdPerMtok = null,
            )
        }
    }

    private fun job() =
        StoredIllustrationSuggestionJob(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            basedOnContentRevision = 1,
            reservedCredits = BigDecimal.ONE,
            status = IllustrationSuggestionJobStatus.RUNNING,
            failureCode = null,
            executionId = null,
            providerStartedAt = null,
            createdAt = Instant.parse("2026-09-24T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-24T00:00:00Z"),
        )

    private companion object {
        const val MAX_TOKENS = IllustrationSuggestionProperties.DEFAULT_MAX_OUTPUT_TOKENS
    }
}

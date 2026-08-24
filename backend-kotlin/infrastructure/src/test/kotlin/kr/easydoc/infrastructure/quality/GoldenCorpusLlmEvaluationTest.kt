package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.core.quality.GoldenDocumentLoader
import kr.easydoc.core.quality.GoldenJudge
import kr.easydoc.core.quality.JudgeLane
import kr.easydoc.core.quality.JudgeLaneDecision
import kr.easydoc.core.quality.evaluateFacts
import kr.easydoc.core.quality.evaluateStyle
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.llm.AnthropicProvider
import kr.easydoc.infrastructure.llm.AnthropicSettings
import kr.easydoc.infrastructure.llm.OpenAiProvider
import kr.easydoc.infrastructure.llm.OpenAiSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * 유료 LLM 으로 골든 문서를 변환하고 스타일·사실·judge 로 채점한다.
 * `./gradlew testLlm` 으로만 열리며, 비밀값이 없으면 skip 한다.
 */
@Tag("llm")
class GoldenCorpusLlmEvaluationTest {
    @Test
    @DisplayName("비밀값이 있으면 골든 변환 결과를 실제로 채점한다")
    fun `골든 변환을 채점한다`() {
        val openAi = System.getenv("OPENAI_API_KEY")
        val anthropic = System.getenv("ANTHROPIC_API_KEY")
        val secret = listOf(openAi, anthropic).firstOrNull { !it.isNullOrBlank() }
        assumeTrue(!secret.isNullOrBlank()) { "LLM 비밀값이 없어 judge 레인을 skip 한다" }
        assertThat(JudgeLane.decide(secret)).isEqualTo(JudgeLaneDecision.RUN)

        val provider =
            when {
                !openAi.isNullOrBlank() -> OpenAiProvider(OpenAiSettings(apiKey = Secret(openAi)))
                !anthropic.isNullOrBlank() -> AnthropicProvider(AnthropicSettings(apiKey = Secret(anthropic)))
                else -> error("LLM 비밀값이 없다")
            }
        val converter = ConvertDocumentUseCase(provider)
        val judge = GoldenJudge(provider)
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())
        val failures = mutableListOf<String>()

        corpus.documents.forEach { document ->
            val converted =
                when (val result = converter.convert(document.sourceText)) {
                    is ConversionResult.Converted -> {
                        result.easyText.value
                    }

                    is ConversionResult.Failed -> {
                        failures += "${document.id}: 변환 실패 ${result.kind}"
                        return@forEach
                    }
                }
            val facts = evaluateFacts(document.id, converted, document.requiredFacts)
            val judged = judge.score(document, converted)
            val style = evaluateStyle(document.id, converted)
            if (!facts.passed) {
                failures += "${document.id}: 사실 누락 ${facts.missing.size}"
            }
            if (!judged.passed) {
                failures += "${document.id}: judge 실패"
            }
            assertThat(style.toString()).doesNotContain(converted)
            assertThat(facts.toString()).doesNotContain(converted)
            assertThat(judged.toString()).doesNotContain(converted)
        }

        assertThat(failures).isEmpty()
    }
}

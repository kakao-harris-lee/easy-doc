package kr.easydoc.core.illustration.generation

import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class IllustrationGenerationTest {
    @Test
    fun `최종 프롬프트는 비워 둘 수 없고 toString에 원문을 노출하지 않는다`() {
        val request = IllustrationGenerationRequest(SENSITIVE_PROMPT)

        assertThat(request.toString()).doesNotContain(SENSITIVE_PROMPT)
        assertThatThrownBy { IllustrationGenerationRequest(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining(SENSITIVE_PROMPT)
    }

    @Test
    fun `생성 결과는 그림 생성 원장 목적만 허용하고 바이트를 toString에 노출하지 않는다`() {
        val result = GeneratedIllustration(byteArrayOf(1, 2, 3), record())

        assertThat(result.toString()).contains("pngBytes=3").doesNotContain("[1, 2, 3]")
        assertThatThrownBy {
            GeneratedIllustration(byteArrayOf(1), record(LlmCallPurpose.ILLUSTRATION_SUGGESTION))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `그림 생성 원장 wire name을 고정한다`() {
        assertThat(LlmCallPurpose.ILLUSTRATION_GENERATION.wireName).isEqualTo("illustration_generation")
    }

    @Test
    fun `생성 대체텍스트는 emoji를 코드 포인트로 세어 300까지 허용한다`() {
        listOf(150, 151, 300).forEach { count ->
            validateIllustrationGenerationConfiguration(suggestion(), configuration("😀".repeat(count)))
        }
        assertThatThrownBy {
            validateIllustrationGenerationConfiguration(suggestion(), configuration("😀".repeat(301)))
        }.isInstanceOf(IllegalArgumentException::class.java)

        val koreanAndEmoji = "한국어" + "😀".repeat(297)
        validateIllustrationGenerationConfiguration(suggestion(), configuration(koreanAndEmoji))
        assertThatThrownBy {
            validateIllustrationGenerationConfiguration(suggestion(), configuration(koreanAndEmoji + "😀"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            validateIllustrationGenerationConfiguration(suggestion(), configuration(" \t\n"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `구성 해시는 기존 UTF-16 canonical 직렬화 벡터를 유지한다`() {
        val configuration =
            IllustrationGenerationConfiguration(
                scenes = listOf("신청😀"),
                preservedFacts = listOf("조건😀"),
                altText = "설명😀",
            )

        assertThat(illustrationConfigurationRevision(configuration))
            .isEqualTo("72a9421f177c65caee9ad240f2a3971266e9b903c7241d7e0ed58c8ba6eb4bdd")
    }

    @Test
    fun `자산 soft 폐기는 저장 codec의 선택 필드이고 기존 레코드는 미폐기로 읽는다`() {
        val configuration = configuration("대체텍스트")
        val metadata =
            illustrationGenerationMetadata(
                suggestion(),
                "analysis-v1",
                configuration,
                illustrationConfigurationRevision(configuration),
            )
        val input = IllustrationGenerationInput("prompt", metadata)
        val legacy = IllustrationGenerationStorageCodec.encode(input)

        assertThat(legacy).doesNotContain("discarded")
        assertThat(IllustrationGenerationStorageCodec.decode(legacy).metadata.discarded).isFalse()

        val discarded = IllustrationGenerationInput(input.finalPrompt, metadata.copy(discarded = true))
        val decoded = IllustrationGenerationStorageCodec.decode(IllustrationGenerationStorageCodec.encode(discarded))
        assertThat(decoded.metadata.discarded).isTrue()
        assertThat(decoded.metadata.configurationRevision).isEqualTo(metadata.configurationRevision)
        assertThat(decoded.metadata.altText).isEqualTo(metadata.altText)
    }

    private fun configuration(altText: String) =
        IllustrationGenerationConfiguration(
            scenes = listOf("장면"),
            preservedFacts = listOf("사실"),
            altText = altText,
        )

    private fun suggestion() =
        IllustrationSuggestion(
            suggestionId = java.util.UUID.randomUUID(),
            purpose = IllustrationSuggestionPurpose.PROCEDURE,
            reason = "절차 설명",
            bodyRange = IllustrationSuggestionBodyRange(0, 0),
            sourceAnchors = emptyList(),
            scenes = listOf("장면"),
            preservedFacts = listOf("사실"),
            altTextDraft = "대체텍스트",
        )

    private fun record(purpose: LlmCallPurpose = LlmCallPurpose.ILLUSTRATION_GENERATION): LlmCallRecord =
        LlmCallRecord(
            purpose = purpose,
            provider = "fake",
            model = "fake-image",
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = 0,
            estimatedCostUsd = BigDecimal.ZERO,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = 10,
            calledAt = Instant.EPOCH,
            outcome = LlmCallOutcome.COMPLETED,
        )

    private companion object {
        const val SENSITIVE_PROMPT = "서버 최종 프롬프트-DO-NOT-LEAK"
    }
}

package kr.easydoc.core.illustration.generation

import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
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

    @Test
    @DisplayName("원문이 정한 대상·수치·단계를 바꾸지 못하게 지시한다(계획 §12.4, 이슈 #204)")
    fun `생성 프롬프트는 대상 바꿔 그리기와 모든 수치 그리기를 금지한다`() {
        val prompt = buildIllustrationGenerationPrompt(suggestion(), configuration("대체텍스트"))

        assertThat(prompt).contains(
            "Keep the exact kind of every object the source names",
            "never replace one with a different or more specific kind",
            "keep it generic instead of inventing equipment, places, or methods",
            "Do not show any number, amount, date, time, duration, or quantity in the image — " +
                "not as written text and not as a drawn quantity such as clock hands, calendar marks, " +
                "counted objects, or bar lengths. The accompanying text carries every number.",
            "Do not add, repeat, or reorder procedure steps",
            "physical package or box unless the source says an item is handed over",
        )
        // 기존 지시도 그대로 남는다.
        assertThat(prompt).contains(
            "Create exactly one educational illustration for adult readers.",
            "do not depict adults as children",
            "Do not put important names, conditions, or instructions in image text.",
            "Do not add unsupported actions or facts.",
            "Return one 1024x1024 PNG.",
        )
        // 수치 지시는 하나뿐이다 — 「없는 숫자만 금지」로 읽히던 문장은 남기지 않는다
        // (2026-11-10 2차 평가 002-2: 「20분 이상」을 10분짜리 시계로 그렸다).
        assertThat(prompt).doesNotContain(
            "Do not invent example numbers",
            "Do not put important names, numbers, conditions, or instructions",
        )
    }

    @Test
    @DisplayName("생성 프롬프트 개정 식별자는 메타데이터와 저장 codec에만 남는다(이슈 #204)")
    fun `생성 프롬프트 버전은 메타데이터에 남고 옛 레코드는 null로 읽힌다`() {
        val configuration = configuration("대체텍스트")
        val metadata =
            illustrationGenerationMetadata(
                suggestion(),
                "analysis-v1",
                configuration,
                illustrationConfigurationRevision(configuration),
            )

        assertThat(metadata.promptVersion).isEqualTo(ILLUSTRATION_GENERATION_PROMPT_VERSION)
        // toString 에는 내지 않는다 — 「prompt」가 민감 이름 토큰이라 SensitiveToStringReachTest 가
        // 이름만으로 누출로 본다. 값은 저장된 입력과 평가 산출물로 잇는다.
        assertThat(metadata.toString()).doesNotContain(ILLUSTRATION_GENERATION_PROMPT_VERSION)

        val encoded = IllustrationGenerationStorageCodec.encode(IllustrationGenerationInput("prompt", metadata))
        assertThat(IllustrationGenerationStorageCodec.decode(encoded).metadata.promptVersion)
            .isEqualTo(ILLUSTRATION_GENERATION_PROMPT_VERSION)

        // 이미 저장된 레코드에는 키가 없다 — 선택 필드라 그대로 읽혀야 한다.
        val legacy =
            IllustrationGenerationStorageCodec.encode(
                IllustrationGenerationInput("prompt", metadata.copy(promptVersion = null)),
            )
        assertThat(legacy).doesNotContain("prompt_version")
        assertThat(IllustrationGenerationStorageCodec.decode(legacy).metadata.promptVersion).isNull()
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

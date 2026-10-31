package kr.easydoc.core.illustration.generation

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

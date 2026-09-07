package kr.easydoc.core.llm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `LlmCallRecord.init` — `outcome`·`model`·`failureClass` 가 서로 어긋나면(V18, 백로그
 * 「실패 호출 원장 추적」 2026-09-08 리뷰) 원장이 「완성 자체가 없었다」와 「응답을
 * 알고 있다」를 동시에 주장하는 모순된 행을 쓴다 — 그 조합을 생성 시점에 막는다.
 */
class LlmCallRecordTest {
    private fun record(
        model: String?,
        outcome: LlmCallOutcome,
        failureClass: String?,
    ) = LlmCallRecord(
        purpose = LlmCallPurpose.CONVERT,
        provider = "anthropic",
        model = model,
        inputTokens = 0,
        outputTokens = 0,
        latencyMs = null,
        estimatedCostUsd = null,
        pricingInputUsdPerMtok = null,
        pricingOutputUsdPerMtok = null,
        charCount = 10,
        calledAt = Instant.now(),
        outcome = outcome,
        failureClass = failureClass,
    )

    @Test
    @DisplayName("COMPLETED 인데 model 이 null 이면 거절한다")
    fun `COMPLETED 는 model 이 있어야 한다`() {
        assertThatThrownBy { record(model = null, outcome = LlmCallOutcome.COMPLETED, failureClass = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("model 은 outcome=COMPLETED 일 때만 있어야 한다")
    }

    @Test
    @DisplayName("PROVIDER_ERROR 인데 model 이 있으면 거절한다 — 응답을 몰라야 한다")
    fun `PROVIDER_ERROR 는 model 이 없어야 한다`() {
        assertThatThrownBy {
            record(
                model = "claude-sonnet-5",
                outcome = LlmCallOutcome.PROVIDER_ERROR,
                failureClass = "LlmProviderException",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("model 은 outcome=COMPLETED 일 때만 있어야 한다")
    }

    @Test
    @DisplayName("COMPLETED 인데 failureClass 가 있으면 거절한다")
    fun `COMPLETED 는 failureClass 가 없어야 한다`() {
        assertThatThrownBy {
            record(model = "claude-sonnet-5", outcome = LlmCallOutcome.COMPLETED, failureClass = "LlmProviderException")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("failureClass 는 outcome=PROVIDER_ERROR 일 때만 있어야 한다")
    }

    @Test
    @DisplayName("PROVIDER_ERROR 인데 failureClass 가 null 이면 거절한다")
    fun `PROVIDER_ERROR 는 failureClass 가 있어야 한다`() {
        assertThatThrownBy { record(model = null, outcome = LlmCallOutcome.PROVIDER_ERROR, failureClass = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("failureClass 는 outcome=PROVIDER_ERROR 일 때만 있어야 한다")
    }

    @Test
    @DisplayName("메시지에 실제 값을 싣지 않는다 — model·failureClass 문자열이 새지 않는다")
    fun `예외 메시지는 값이 없다`() {
        val message =
            assertThatThrownBy {
                record(model = "claude-sonnet-5-비밀모델", outcome = LlmCallOutcome.PROVIDER_ERROR, failureClass = null)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .actual()
                .message
        assertThat(message).doesNotContain("claude-sonnet-5-비밀모델")
    }

    @Test
    @DisplayName("COMPLETED + model + failureClass=null 은 정상 생성된다")
    fun `COMPLETED 조합은 정상이다`() {
        val call = record(model = "claude-sonnet-5", outcome = LlmCallOutcome.COMPLETED, failureClass = null)

        assertThat(call.model).isEqualTo("claude-sonnet-5")
        assertThat(call.failureClass).isNull()
    }

    @Test
    @DisplayName("PROVIDER_ERROR + model=null + failureClass 는 정상 생성된다")
    fun `PROVIDER_ERROR 조합은 정상이다`() {
        val call = record(model = null, outcome = LlmCallOutcome.PROVIDER_ERROR, failureClass = "LlmProviderException")

        assertThat(call.model).isNull()
        assertThat(call.failureClass).isEqualTo("LlmProviderException")
    }
}

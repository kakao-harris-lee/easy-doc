package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MetricsLlmProviderDecoratorTest {
    @Test
    @DisplayName("delegate 응답에 지연과 설정 기반 예상 비용을 더하고 관측값을 전달한다")
    fun `성공 호출을 측정한다`() {
        val observations = mutableListOf<LlmCallObservation>()
        val times = ArrayDeque(listOf(1_000_000L, 26_000_000L))
        val decorator =
            MetricsLlmProviderDecorator(
                delegate = successfulProvider(inputTokens = 1_000_000, outputTokens = 500_000),
                pricing = TokenPricing(BigDecimal("2.00"), BigDecimal("8.00")),
                observer = LlmCallObserver(observations::add),
                nanoTime = times::removeFirst,
            )

        val completion = decorator.complete(prompt())

        assertThat(completion.latencyMs).isEqualTo(25)
        assertThat(completion.estimatedCostUsd).isEqualByComparingTo("6.00")
        assertThat(observations.single())
            .usingRecursiveComparison()
            .isEqualTo(
                LlmCallObservation(
                    provider = "openai",
                    model = "gpt-test",
                    latencyMs = 25,
                    inputTokens = 1_000_000,
                    outputTokens = 500_000,
                    estimatedCostUsd = BigDecimal("6.00"),
                    outcome = LlmCallOutcome.SUCCESS,
                ),
            )
    }

    @Test
    @DisplayName("가격이 미설정이어도 지연은 측정하고 비용은 추정하지 않는다")
    fun `가격 미설정을 구분한다`() {
        val times = ArrayDeque(listOf(0L, 5_000_000L))
        val decorator =
            MetricsLlmProviderDecorator(
                delegate = successfulProvider(inputTokens = 10, outputTokens = 20),
                pricing = null,
                nanoTime = times::removeFirst,
            )

        val completion = decorator.complete(prompt())

        assertThat(completion.latencyMs).isEqualTo(5)
        assertThat(completion.estimatedCostUsd).isNull()
    }

    @Test
    @DisplayName("실패도 지연과 outcome만 기록하고 원래 예외를 다시 던진다")
    fun `실패 호출을 측정한다`() {
        val failure = LlmProviderException("openai 호출 실패")
        val observations = mutableListOf<LlmCallObservation>()
        val times = ArrayDeque(listOf(10_000_000L, 13_000_000L))
        val decorator =
            MetricsLlmProviderDecorator(
                delegate =
                    object : LlmProvider {
                        override val name = "openai"

                        override fun complete(
                            prompt: LlmPrompt,
                            options: LlmOptions,
                        ): LlmCompletion = throw failure
                    },
                pricing = null,
                observer = LlmCallObserver(observations::add),
                nanoTime = times::removeFirst,
            )

        assertThatThrownBy { decorator.complete(prompt()) }.isSameAs(failure)
        assertThat(observations.single().outcome).isEqualTo(LlmCallOutcome.FAILURE)
        assertThat(observations.single().latencyMs).isEqualTo(3)
        assertThat(observations.single().model).isNull()
    }

    private fun successfulProvider(
        inputTokens: Int,
        outputTokens: Int,
    ): LlmProvider =
        object : LlmProvider {
            override val name = "openai"

            override fun complete(
                prompt: LlmPrompt,
                options: LlmOptions,
            ) = LlmCompletion(
                text = "결과",
                provider = name,
                model = "gpt-test",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
            )
        }

    private fun prompt() = AnthropicTestSupport.conversionPrompt("본문")
}

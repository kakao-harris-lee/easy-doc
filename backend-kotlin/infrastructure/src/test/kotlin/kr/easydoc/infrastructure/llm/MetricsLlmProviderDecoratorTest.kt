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
        assertThat(completion.pricingInputUsdPerMtok).isNull()
        assertThat(completion.pricingOutputUsdPerMtok).isNull()
    }

    @Test
    @DisplayName("모델별 단가가 있으면 응답 model 로 찾아 그 단가로 계산하고 스냅샷을 채운다")
    fun `모델별 단가를 우선한다`() {
        val times = ArrayDeque(listOf(0L, 1_000_000L))
        val decorator =
            MetricsLlmProviderDecorator(
                delegate = successfulProvider(inputTokens = 1_000_000, outputTokens = 1_000_000, model = "gpt-test"),
                pricing = TokenPricing(BigDecimal("999.00"), BigDecimal("999.00")),
                modelPricing = mapOf("gpt-test" to TokenPricing(BigDecimal("2.00"), BigDecimal("10.00"))),
                nanoTime = times::removeFirst,
            )

        val completion = decorator.complete(prompt())

        assertThat(completion.estimatedCostUsd).isEqualByComparingTo("12.00")
        assertThat(completion.pricingInputUsdPerMtok).isEqualByComparingTo("2.00")
        assertThat(completion.pricingOutputUsdPerMtok).isEqualByComparingTo("10.00")
    }

    @Test
    @DisplayName("응답 model이 모델별 단가 표에 없으면 기본 단가로 떨어지고 스냅샷도 그 값이다")
    fun `모델별 단가에 없으면 기본값으로 떨어진다`() {
        val times = ArrayDeque(listOf(0L, 1_000_000L))
        val decorator =
            MetricsLlmProviderDecorator(
                delegate = successfulProvider(inputTokens = 1_000_000, outputTokens = 0, model = "다른-모델"),
                pricing = TokenPricing(BigDecimal("3.00"), BigDecimal("15.00")),
                modelPricing = mapOf("gpt-test" to TokenPricing(BigDecimal("2.00"), BigDecimal("10.00"))),
                nanoTime = times::removeFirst,
            )

        val completion = decorator.complete(prompt())

        assertThat(completion.estimatedCostUsd).isEqualByComparingTo("3.00")
        assertThat(completion.pricingInputUsdPerMtok).isEqualByComparingTo("3.00")
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
        model: String = "gpt-test",
    ): LlmProvider =
        object : LlmProvider {
            override val name = "openai"

            override fun complete(
                prompt: LlmPrompt,
                options: LlmOptions,
            ) = LlmCompletion(
                text = "결과",
                provider = name,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
            )
        }

    private fun prompt() = AnthropicTestSupport.conversionPrompt("본문")
}

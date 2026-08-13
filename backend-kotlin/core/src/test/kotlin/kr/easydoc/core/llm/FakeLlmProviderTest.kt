package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.privacy.maskText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `core` 가 Spring·DB·HTTP 없이 LLM 경계를 시험할 수 있는지 확인한다(계획 §3.2).
 * 이 테스트는 실제 API 를 부르지 않는다.
 */
class FakeLlmProviderTest {
    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    private fun prompt() = LlmPrompt.forConversion(maskText("행정 안내문 본문입니다.").maskedText, fixedIds)

    @Test
    @DisplayName("준비한 응답을 순서대로 돌려준다")
    fun `순서대로 응답한다`() {
        val provider = FakeLlmProvider.replying("첫 번째", "두 번째")

        assertThat(provider.complete(prompt()).text).isEqualTo("첫 번째")
        assertThat(provider.complete(prompt()).text).isEqualTo("두 번째")
        assertThat(provider.unusedTurns).isZero()
    }

    @Test
    @DisplayName("호출 인자를 그대로 기록한다")
    fun `호출을 기록한다`() {
        val provider = FakeLlmProvider.replying("결과")

        provider.complete(prompt(), LlmOptions(maxTokens = 512))

        assertThat(provider.calls).hasSize(1)
        assertThat(
            provider.calls
                .single()
                .options.maxTokens,
        ).isEqualTo(512)
        assertThat(
            provider.calls
                .single()
                .prompt.user,
        ).contains("위 문서를 쉬운 글로 바꿔 주세요.")
    }

    @Test
    @DisplayName("응답 이름과 벤더 이름이 함께 실린다")
    fun `provider 와 model 을 채운다`() {
        val provider = FakeLlmProvider(listOf(FakeLlmTurn.Reply(text = "결과", model = "fake-2026")))

        val completion = provider.complete(prompt())

        assertThat(completion.provider).isEqualTo("fake")
        assertThat(completion.model).isEqualTo("fake-2026")
    }

    @Test
    @DisplayName("실패 차례에는 준비한 예외를 던진다")
    fun `실패 경로를 재현한다`() {
        val provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("anthropic 호출 실패 (HTTP 429)"))))

        assertThatThrownBy { provider.complete(prompt()) }
            .isInstanceOf(LlmProviderException::class.java)
    }

    @Test
    @DisplayName("준비한 응답보다 많이 부르면 조용히 넘어가지 않는다")
    fun `소진되면 실패한다`() {
        val provider = FakeLlmProvider.replying("하나")
        provider.complete(prompt())

        assertThatThrownBy { provider.complete(prompt()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("준비된 응답이 없다")
    }

    @Test
    @DisplayName("maxTokens 0 이하는 만들 수 없다")
    fun `옵션을 검증한다`() {
        assertThatThrownBy { LlmOptions(maxTokens = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("기본 출력 상한은 16,000 이다")
    fun `기본 상한을 유지한다`() {
        assertThat(LlmOptions().maxTokens).isEqualTo(DEFAULT_MAX_TOKENS)
        assertThat(DEFAULT_MAX_TOKENS).isEqualTo(16_000)
    }
}

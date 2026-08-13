package kr.easydoc.core.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LlmCompletionTest {
    private fun completion(finishReason: LlmFinishReason) =
        LlmCompletion(
            text = "쉬운 글 본문",
            provider = "anthropic",
            model = "claude-sonnet-5-관측값",
            finishReason = finishReason,
        )

    @Test
    @DisplayName("출력 상한에 걸린 응답만 truncated 다")
    fun `truncated 는 MAX_TOKENS 에서만 참이다`() {
        assertThat(completion(LlmFinishReason.MAX_TOKENS).truncated).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = LlmFinishReason::class, names = ["MAX_TOKENS"], mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("나머지 종료 사유는 truncated 가 아니다")
    fun `다른 사유는 truncated 가 아니다`(reason: LlmFinishReason) {
        assertThat(completion(reason).truncated).isFalse()
    }

    @Test
    @DisplayName("toString 에 변환 본문이 실리지 않는다")
    fun `toString 은 본문 길이만 남긴다`() {
        val rendered =
            LlmCompletion(
                text = "홍길동 님의 주민등록번호는 900101-1234567 입니다",
                provider = "anthropic",
                model = "claude-sonnet-5",
                inputTokens = 12,
                outputTokens = 34,
                finishReason = LlmFinishReason.END_TURN,
            ).toString()

        assertThat(rendered).doesNotContain("홍길동")
        assertThat(rendered).doesNotContain("900101-1234567")
        assertThat(rendered).contains("provider=anthropic", "model=claude-sonnet-5")
        assertThat(rendered).contains("inputTokens=12", "outputTokens=34")
    }
}

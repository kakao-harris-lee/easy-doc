package kr.easydoc.infrastructure.queue

import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.llm.LlmProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 출력 토큰 상한이 코드 상수가 아니라 `easydoc.llm.max-output-tokens` 구성값에서
 * 실제 호출까지 흐르는지의 회귀 고정판(게이트 ⓪ ⑶).
 */
class ConversionWorkerConfigurationTest {
    @Test
    @DisplayName("convertDocumentUseCase 조립이 easydoc.llm.max-output-tokens 값을 LlmOptions 로 전달한다")
    fun `구성값이 LlmOptions 로 흐른다`() {
        val recordingProvider = RecordingProvider()
        val configuration = ConversionWorkerConfiguration()

        val useCase =
            configuration.convertDocumentUseCase(
                provider = recordingProvider,
                dictionary = NoDictionaryContext,
                properties = LlmProperties(maxOutputTokens = 4_321),
            )

        useCase.convert("변환할 원문입니다.")

        assertThat(recordingProvider.lastOptions?.maxTokens).isEqualTo(4_321)
    }

    /** 실제 완성 요청에 실린 [LlmOptions] 를 기록만 하는 대역. */
    private class RecordingProvider : LlmProvider {
        var lastOptions: LlmOptions? = null
        override val name: String = "recording"

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion {
            lastOptions = options
            return LlmCompletion(
                text = "쉬운 글 결과",
                provider = name,
                model = "recording-model",
                inputTokens = 10,
                outputTokens = 20,
                finishReason = LlmFinishReason.END_TURN,
            )
        }
    }
}

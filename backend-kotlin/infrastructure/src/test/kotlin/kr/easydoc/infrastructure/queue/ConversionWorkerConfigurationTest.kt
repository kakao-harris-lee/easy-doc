package kr.easydoc.infrastructure.queue

import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.DEFAULT_MAX_TOKENS
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.MAX_OUTPUT_TOKENS_CEILING
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 출력 토큰 상한이 코드 상수가 아니라 `easydoc.llm.max-output-tokens` 구성값에서
 * 실제 호출까지 흐르는지의 회귀 고정판(게이트 ⓪ ⑶). 0 이하 값을 운영자 오설정으로
 * 거절하는지도 여기서 고정한다 — `LlmOptions.init` 의 `require` (프로그래밍 오류용
 * `IllegalArgumentException`)가 아니라 이 조립 지점이 `ConfigurationException` 으로
 * 먼저 거절해야 한다.
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

    @Test
    @DisplayName("설정을 안 하면 core 의 DEFAULT_MAX_TOKENS 하나가 그대로 흐른다")
    fun `미설정은 기본값을 유지한다`() {
        val recordingProvider = RecordingProvider()
        val configuration = ConversionWorkerConfiguration()

        val useCase =
            configuration.convertDocumentUseCase(
                provider = recordingProvider,
                dictionary = NoDictionaryContext,
                properties = LlmProperties(),
            )

        useCase.convert("변환할 원문입니다.")

        assertThat(recordingProvider.lastOptions?.maxTokens).isEqualTo(DEFAULT_MAX_TOKENS)
    }

    @Test
    @DisplayName("0 이하 상한은 운영자 오설정으로 거절한다 — IllegalArgumentException 이 아니다")
    fun `0 이하 상한은 ConfigurationException 이다`() {
        val configuration = ConversionWorkerConfiguration()

        assertThatThrownBy {
            configuration.convertDocumentUseCase(
                provider = RecordingProvider(),
                dictionary = NoDictionaryContext,
                properties = LlmProperties(maxOutputTokens = 0),
            )
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.llm.max-output-tokens")
    }

    @Test
    @DisplayName("음수 상한도 같은 방식으로 거절한다")
    fun `음수 상한도 ConfigurationException 이다`() {
        val configuration = ConversionWorkerConfiguration()

        assertThatThrownBy {
            configuration.convertDocumentUseCase(
                provider = RecordingProvider(),
                dictionary = NoDictionaryContext,
                properties = LlmProperties(maxOutputTokens = -1),
            )
        }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("A4 20장 기준 상한을 넘는 값은 운영자 오설정으로 거절한다")
    fun `상한 초과는 ConfigurationException 이다`() {
        val configuration = ConversionWorkerConfiguration()

        assertThatThrownBy {
            configuration.convertDocumentUseCase(
                provider = RecordingProvider(),
                dictionary = NoDictionaryContext,
                properties = LlmProperties(maxOutputTokens = MAX_OUTPUT_TOKENS_CEILING + 1),
            )
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.llm.max-output-tokens")
            .hasMessageContaining("A4 20장")
    }

    @Test
    @DisplayName("상한과 정확히 같은 값은 통과한다 — 경계값")
    fun `상한과 정확히 같은 값은 통과한다`() {
        val recordingProvider = RecordingProvider()
        val configuration = ConversionWorkerConfiguration()

        val useCase =
            configuration.convertDocumentUseCase(
                provider = recordingProvider,
                dictionary = NoDictionaryContext,
                properties = LlmProperties(maxOutputTokens = MAX_OUTPUT_TOKENS_CEILING),
            )

        useCase.convert("변환할 원문입니다.")

        assertThat(recordingProvider.lastOptions?.maxTokens).isEqualTo(MAX_OUTPUT_TOKENS_CEILING)
    }

    @Test
    @DisplayName("기본값은 상한 안에 있다")
    fun `기본값이 상한을 넘지 않는다`() {
        assertThat(DEFAULT_MAX_TOKENS).isLessThanOrEqualTo(MAX_OUTPUT_TOKENS_CEILING)
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

package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.LlmEmptyResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.TEST_API_KEY
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.conversionPrompt
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.emptyBody
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.settings
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.successBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/** 응답 해석과 실패 매핑을 확인한다. 벤더 오류 타입이 core 를 넘어오지 않는 것이 핵심이다. */
class AnthropicProviderResponseTest {
    private lateinit var server: StubLlmServer

    @BeforeEach
    fun start() {
        server = StubLlmServer()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun provider(readTimeout: Duration = ANTHROPIC_READ_TIMEOUT) =
        AnthropicProvider(settings(baseUrl = server.baseUrl).copy(readTimeout = readTimeout))

    @Test
    @DisplayName("모델 이름은 설정값이 아니라 응답에서 관측한다")
    fun `관측 모델을 싣는다`() {
        server.replyWith(body = successBody(model = "claude-sonnet-5-20260101"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.model).isEqualTo("claude-sonnet-5-20260101")
        assertThat(completion.model).isNotEqualTo(DEFAULT_ANTHROPIC_MODEL)
        assertThat(completion.provider).isEqualTo(ANTHROPIC_PROVIDER_NAME)
    }

    @Test
    @DisplayName("본문과 토큰 수를 그대로 옮긴다")
    fun `본문과 사용량을 읽는다`() {
        server.replyWith(body = successBody(text = "쉬운 글입니다.", inputTokens = 1234, outputTokens = 567))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEqualTo("쉬운 글입니다.")
        assertThat(completion.inputTokens).isEqualTo(1234)
        assertThat(completion.outputTokens).isEqualTo(567)
    }

    @Test
    @DisplayName("응답에 모델 이름이 없으면 실패로 다룬다")
    fun `모델 이름이 없으면 실패다`() {
        server.replyWith(
            body =
                """{"content":[{"type":"text","text":"결과"}],"stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("모델 이름")
    }

    @ParameterizedTest(name = "stop_reason={0} → {1}")
    @CsvSource(
        "end_turn, END_TURN",
        "max_tokens, MAX_TOKENS",
        "stop_sequence, STOP_SEQUENCE",
        "refusal, REFUSAL",
        "pause_turn, OTHER",
    )
    @DisplayName("종료 사유를 우리 어휘로 정규화한다")
    fun `finish reason 을 매핑한다`(
        wire: String,
        expected: LlmFinishReason,
    ) {
        server.replyWith(body = successBody(stopReason = wire))

        assertThat(provider().complete(conversionPrompt()).finishReason).isEqualTo(expected)
    }

    @Test
    @DisplayName("출력 상한에 걸린 응답은 truncated 로 드러난다")
    fun `잘린 응답을 알린다`() {
        server.replyWith(body = successBody(stopReason = "max_tokens"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.truncated).isTrue()
    }

    @Test
    @DisplayName("본문이 비어도 던지지 않고 종료 사유·사용량을 함께 돌려준다")
    fun `빈 응답을 사실로 보고한다`() {
        server.replyWith(body = emptyBody(stopReason = "end_turn"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEmpty()
        assertThat(completion.finishReason).isEqualTo(LlmFinishReason.END_TURN)
        assertThat(completion.inputTokens).isEqualTo(5)
    }

    @Test
    @DisplayName("잘려서 본문이 비어 와도 절단 사실과 사용량을 잃지 않는다")
    fun `빈 절단 응답이 절단으로 남는다`() {
        server.replyWith(body = emptyBody(stopReason = "max_tokens"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEmpty()
        assertThat(completion.truncated).isTrue()
        assertThat(completion.inputTokens).isEqualTo(5)
    }

    @Test
    @DisplayName("안전 분류기 거절은 값으로 구분한다 — 예외 메시지가 아니라 finishReason 으로")
    fun `거절을 구분한다`() {
        server.replyWith(body = emptyBody(stopReason = "refusal"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.finishReason).isEqualTo(LlmFinishReason.REFUSAL)
        assertThat(completion.truncated).isFalse()
    }

    @ParameterizedTest(name = "HTTP {0}")
    @CsvSource("400", "401", "429", "500", "529")
    @DisplayName("오류 상태 코드는 도메인 예외로 바뀐다")
    fun `HTTP 오류를 매핑한다`(status: Int) {
        server.replyWith(
            status = status,
            body = """{"error":{"message":"echoed prompt 900101-1234567 and key $TEST_API_KEY"}}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("HTTP $status")
            .hasMessageNotContaining(TEST_API_KEY)
            .hasMessageNotContaining("900101-1234567")
            .hasMessageNotContaining("echoed prompt")
            .hasNoCause()
    }

    @Test
    @DisplayName("응답이 JSON 이 아니면 형식 오류로 바꾼다")
    fun `형식 오류를 매핑한다`() {
        server.replyWith(body = "<html>gateway error 900101-1234567</html>")

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("응답 형식 오류")
            .hasMessageNotContaining("900101-1234567")
            .hasNoCause()
    }

    @Test
    @DisplayName("읽기 타임아웃은 도메인 예외로 바뀐다")
    fun `타임아웃을 매핑한다`() {
        server.replyWith(body = successBody(), delay = Duration.ofSeconds(3))

        assertThatThrownBy {
            provider(readTimeout = Duration.ofMillis(200)).complete(conversionPrompt())
        }.isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("anthropic 호출 실패")
            .hasMessageNotContaining(TEST_API_KEY)
            .hasNoCause()
    }

    @Test
    @DisplayName("모델 이름이 없어도 usage 를 먼저 읽는다 — 인자 안에서 던지지 않는다")
    fun `모델 누락은 인자 평가 사고가 아니다`() {
        server.replyWith(
            body =
                """{"content":[{"type":"text","text":"결과"}],"stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":7,"output_tokens":3}}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("모델 이름")
            .hasNoCause()
    }

    @ParameterizedTest(name = "usage = {0}")
    @ValueSource(
        strings = [
            "{}",
            """{"input_tokens": 5}""",
            """{"input_tokens": null, "output_tokens": 2}""",
            """{"input_tokens": "5", "output_tokens": 2}""",
            """{"input_tokens": 1.5, "output_tokens": 2}""",
            """{"input_tokens": -1, "output_tokens": 2}""",
        ],
    )
    @DisplayName("누락·null·비정수·음수 토큰 수를 0으로 받아들이지 않는다")
    fun `없는 값과 0을 구분한다`(usage: String) {
        server.replyWith(
            body =
                """{"model":"claude-sonnet-5-20260101","content":[{"type":"text","text":"결과"}],""" +
                    """"stop_reason":"end_turn","usage":$usage}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("usage")
    }

    @Test
    @DisplayName("출력 토큰 0은 정상 관측이다 — 누락과 구분한다")
    fun `실려 온 0은 받아들인다`() {
        server.replyWith(body = emptyBody(stopReason = "end_turn"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.inputTokens).isEqualTo(5)
        assertThat(completion.outputTokens).isZero()
    }

    @Test
    @DisplayName("stop_reason 누락을 OTHER 로 접지 않는다")
    fun `종료 사유 누락은 실패다`() {
        server.replyWith(
            body =
                """{"model":"claude-sonnet-5-20260101","content":[{"type":"text","text":"결과"}],""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("stop_reason")
    }

    @Test
    @DisplayName("모르는 stop_reason 값은 OTHER 다 — 누락과 다른 자리다")
    fun `모르는 값은 OTHER 다`() {
        server.replyWith(
            body =
                """{"model":"claude-sonnet-5-20260101","content":[{"type":"text","text":"결과"}],""" +
                    """"stop_reason":"장래에 생길 값","usage":{"input_tokens":1,"output_tokens":1}}""",
        )

        assertThat(provider().complete(conversionPrompt()).finishReason)
            .isEqualTo(LlmFinishReason.OTHER)
    }

    @Test
    @DisplayName("어댑터는 스스로 재시도하지 않는다")
    fun `재시도하지 않는다`() {
        server.replyWith(status = 500, body = """{"error":"boom"}""")

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)

        assertThat(server.received)
            .withFailMessage("어댑터가 재시도했다 — 워커 재시도와 겹쳐 중복 LLM 호출이 된다")
            .hasSize(1)
    }
}

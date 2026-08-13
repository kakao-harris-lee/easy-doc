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
import java.time.Duration

/** 응답 해석과 실패 매핑을 확인한다. 벤더 오류 타입이 core 를 넘어오지 않는 것이 핵심이다. */
class AnthropicProviderResponseTest {
    private lateinit var server: StubAnthropicServer

    @BeforeEach
    fun start() {
        server = StubAnthropicServer()
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
        // 설정은 별칭(claude-sonnet-5)인데 응답은 날짜 붙은 실제 스냅샷을 보고한다.
        // 설정값을 그대로 실으면 "무엇으로 잰 수치인가"가 주장으로 바뀐다(골든셋 기준선 지문).
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
            body = """{"content":[{"type":"text","text":"결과"}],"stop_reason":"end_turn","usage":{}}""",
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
        "'', OTHER",
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

        // 어댑터는 예외를 던지지 않는다 — 재시도·분할 판단은 변환 서비스 몫이다(원본과 같음).
        assertThat(completion.truncated).isTrue()
    }

    @Test
    @DisplayName("본문이 비면 빈 응답 예외를 던진다")
    fun `빈 응답을 실패로 다룬다`() {
        server.replyWith(body = emptyBody(stopReason = "end_turn"))

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmEmptyResultException::class.java)
    }

    @Test
    @DisplayName("안전 분류기 거절은 빈 응답과 구분한다")
    fun `거절을 구분한다`() {
        // HTTP 200 + 빈 content + stop_reason=refusal. 상태 코드만 보면 성공으로 읽힌다.
        server.replyWith(body = emptyBody(stopReason = "refusal"))

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .isNotInstanceOf(LlmEmptyResultException::class.java)
            .hasMessageContaining("거절")
    }

    @ParameterizedTest(name = "HTTP {0}")
    @CsvSource("400", "401", "429", "500", "529")
    @DisplayName("오류 상태 코드는 도메인 예외로 바뀐다")
    fun `HTTP 오류를 매핑한다`(status: Int) {
        // 벤더가 응답 본문에 우리가 보낸 프롬프트를 되비추는 상황을 가정한다.
        server.replyWith(
            status = status,
            body = """{"error":{"message":"echoed prompt 900101-1234567 and key $TEST_API_KEY"}}""",
        )

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("HTTP $status")
            // 응답 본문이 예외 메시지를 타고 로그로 나가면 안 된다.
            .hasMessageNotContaining(TEST_API_KEY)
            .hasMessageNotContaining("900101-1234567")
            .hasMessageNotContaining("echoed prompt")
    }

    @Test
    @DisplayName("응답이 JSON 이 아니면 형식 오류로 바꾼다")
    fun `형식 오류를 매핑한다`() {
        server.replyWith(body = "<html>gateway error 900101-1234567</html>")

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("응답 형식 오류")
            .hasMessageNotContaining("900101-1234567")
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
    }

    @Test
    @DisplayName("어댑터는 스스로 재시도하지 않는다")
    fun `재시도하지 않는다`() {
        // 재시도 책임은 한 계층만 갖는다(계획 §4.6). 어댑터가 조용히 다시 부르면
        // "문서당 최대 2회"라는 제품 계약이 메트릭에서 사라진다.
        server.replyWith(status = 500, body = """{"error":"boom"}""")

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)

        assertThat(server.received)
            .withFailMessage("어댑터가 재시도했다 — 워커 재시도와 겹쳐 중복 LLM 호출이 된다")
            .hasSize(1)
    }
}

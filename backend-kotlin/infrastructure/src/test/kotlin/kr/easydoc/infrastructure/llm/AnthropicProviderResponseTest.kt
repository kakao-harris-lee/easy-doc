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
        // 빈 문자열은 이 표에서 뺐다 — `stop_reason` **누락**은 이제 실패다(게이트 09 M-11).
        // OTHER 의 뜻은 "벤더가 우리가 모르는 값을 줬다"이지 "아무 값도 안 줬다"가 아니다.
        // 누락 쪽은 「stop_reason 누락을 OTHER 로 접지 않는다」가 따로 본다.
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
    @DisplayName("본문이 비어도 던지지 않고 종료 사유·사용량을 함께 돌려준다")
    fun `빈 응답을 사실로 보고한다`() {
        // 교차 종합 C-08. 이전 판은 여기서 예외를 던져 **LlmCompletion 이 만들어지지 않았고**,
        // 그 순간 finishReason 과 usage 가 함께 사라졌다. 어댑터는 사실만 보고한다.
        server.replyWith(body = emptyBody(stopReason = "end_turn"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEmpty()
        assertThat(completion.finishReason).isEqualTo(LlmFinishReason.END_TURN)
        assertThat(completion.inputTokens).isEqualTo(5)
    }

    @Test
    @DisplayName("잘려서 본문이 비어 와도 절단 사실과 사용량을 잃지 않는다")
    fun `빈 절단 응답이 절단으로 남는다`() {
        // **C-08 의 핵심**. 이것이 예외로 나가면 변환 계층은 절단을 EMPTY_RESULT 로 기록하고
        // (사용자가 취할 조치가 달라진다) 토큰도 누계에서 빠진다.
        server.replyWith(body = emptyBody(stopReason = "max_tokens"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEmpty()
        assertThat(completion.truncated).isTrue()
        assertThat(completion.inputTokens).isEqualTo(5)
    }

    @Test
    @DisplayName("안전 분류기 거절은 값으로 구분한다 — 예외 메시지가 아니라 finishReason 으로")
    fun `거절을 구분한다`() {
        // HTTP 200 + 빈 content + stop_reason=refusal. 상태 코드만 보면 성공으로 읽힌다.
        // 예전에는 예외 메시지 문자열로 갈랐는데, 문자열은 분기 조건이 될 수 없어 변환
        // 계층이 실제로는 그것을 읽지 못했다.
        server.replyWith(body = emptyBody(stopReason = "refusal"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.finishReason).isEqualTo(LlmFinishReason.REFUSAL)
        assertThat(completion.truncated).isFalse()
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
            // **cause 를 매달지 않는다** — 교차 리뷰 X-16. 메시지만 정제해도 원인 예외를
            // 체인으로 달면 스택 트레이스를 한 번 찍는 것으로 벤더가 되비춘 프롬프트가
            // 그대로 로그로 나간다(Spring 의 HttpClientErrorException 은 응답 본문을
            // 메시지에 담는다). "키가 새지 않는 다섯 겹" 중 이 한 겹만 단언이 없었다.
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
            // 파싱 실패 메시지에는 본문 조각이 그대로 실린다(어느 위치의 어떤 토큰인지).
            // 메시지도 cause 도 버리고 예외 **타입 이름**만 남긴다.
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
        // 게이트 09 M-10. 이전 판은 requireModel 을 LlmCompletion 생성자 **인자 자리**에서
        // 불러, 던지는 순간 이미 읽어 둔 usage 가 통째로 사라졌다. 어느 인자가 먼저
        // 평가되는지에 결과가 달린 코드였다.
        //
        // 지금도 예외에 토큰을 실어 보내지는 못한다(M-12 · Phase 5). 여기서 고정하는 것은
        // **던지는 자리가 조립 전으로 옮겨졌다**는 것과, 그 실패가 usage 문제와 구분된다는 것이다.
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
        // 게이트 09 M-11(codex K-5). `asInt(0)` 은 "관측하지 못했다"를 "0이더라"로 바꿔 적는다.
        // 사용량은 크레딧 원가의 근거이고, 0으로 접힌 수는 아무도 다시 세지 않는다.
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
        // 빈 응답에서 output_tokens 가 실제로 0으로 온다. "없는 값을 거절한다"가
        // "0을 거절한다"가 되면 그 정상 경로가 막힌다.
        server.replyWith(body = emptyBody(stopReason = "end_turn"))

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.inputTokens).isEqualTo(5)
        assertThat(completion.outputTokens).isZero()
    }

    @Test
    @DisplayName("stop_reason 누락을 OTHER 로 접지 않는다")
    fun `종료 사유 누락은 실패다`() {
        // OTHER 의 뜻은 "벤더가 우리가 모르는 값을 줬다"이지 "아무 값도 안 줬다"가 아니다.
        // 뭉뚱그리면 절단 판정이 조용히 거짓인 응답을 성공으로 넘긴다.
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

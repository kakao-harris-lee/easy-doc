package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.llm.AnthropicProvider
import kr.easydoc.infrastructure.llm.AnthropicSettings
import kr.easydoc.infrastructure.llm.AnthropicTestSupport
import kr.easydoc.infrastructure.llm.StubLlmServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 레인 계측을 **실제 어댑터**에 물려 확인한다. 스텁 서버가 상대라 유료 호출이 아니다.
 *
 * 이 테스트가 있는 이유는 [LaneFaults] 가 어댑터의 예외 **문구**에 기대기 때문이다(예외 타입에는
 * 상태 코드가 없다). 문구가 바뀌면 유료 레인이 조용히 오분류하는 대신 여기가 깨져야 한다.
 */
class GoldenLlmLaneProviderTest {
    @Test
    @DisplayName("429 는 일시적 실패로 보고 상한까지 다시 부른다")
    fun `429 는 다시 부른다`() {
        StubLlmServer().use { server ->
            server.replyWith(status = TOO_MANY_REQUESTS, body = ERROR_BODY)
            val journal = LaneJournal()

            assertThatThrownBy { lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)

            assertThat(server.received).hasSize(ATTEMPTS)
            assertThat(journal.retries).isEqualTo(ATTEMPTS - 1)
            assertThat(journal.distribution()).containsEntry("HTTP $TOO_MANY_REQUESTS", ATTEMPTS)
        }
    }

    @Test
    @DisplayName("5xx 도 일시적 실패다")
    fun `5xx 는 다시 부른다`() {
        StubLlmServer().use { server ->
            server.replyWith(status = SERVER_ERROR, body = ERROR_BODY)
            val journal = LaneJournal()

            assertThatThrownBy { lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)

            assertThat(server.received).hasSize(ATTEMPTS)
        }
    }

    @Test
    @DisplayName("4xx 요청 오류는 다시 불러도 같으므로 한 번만 부른다")
    fun `400 은 다시 부르지 않는다`() {
        StubLlmServer().use { server ->
            server.replyWith(status = BAD_REQUEST, body = ERROR_BODY)
            val journal = LaneJournal()

            assertThatThrownBy { lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)

            assertThat(server.received).hasSize(1)
            assertThat(journal.retries).isZero()
            assertThat(journal.distribution()).containsEntry("HTTP $BAD_REQUEST", 1)
        }
    }

    @Test
    @DisplayName("읽기 타임아웃도 일시적 실패로 보고, 원인에 예외 종류가 남는다")
    fun `타임아웃은 다시 부른다`() {
        StubLlmServer().use { server ->
            server.replyWith(body = AnthropicTestSupport.successBody(), delay = SLOW_REPLY)
            val journal = LaneJournal()
            val provider = AnthropicProvider(settings(server).copy(readTimeout = SHORT_READ_TIMEOUT))

            assertThatThrownBy {
                LaneInstrumentedProvider(provider, journal, pause = {})
                    .complete(AnthropicTestSupport.conversionPrompt(), LlmOptions())
            }.isInstanceOf(LlmProviderException::class.java)

            assertThat(journal.retries).isEqualTo(ATTEMPTS - 1)
            assertThat(journal.distribution().keys.single()).endsWith("Exception")
        }
    }

    @Test
    @DisplayName("응답 해석 실패는 인프라 흔들림이 아니므로 다시 부르지 않는다")
    fun `응답 형식 오류는 다시 부르지 않는다`() {
        StubLlmServer().use { server ->
            server.replyWith(body = "{}")
            val journal = LaneJournal()

            assertThatThrownBy { lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)

            assertThat(server.received).hasSize(1)
            assertThat(journal.retries).isZero()
        }
    }

    @Test
    @DisplayName("레인 전체 재시도 예산을 넘기지 않는다 — 전면 장애를 재시도로 덮지 않는다")
    fun `재시도 예산이 상한이다`() {
        StubLlmServer().use { server ->
            server.replyWith(status = TOO_MANY_REQUESTS, body = ERROR_BODY)
            val journal = LaneJournal(retryBudget = 1)

            assertThatThrownBy { lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)

            assertThat(server.received).hasSize(2)
            assertThat(journal.retries).isEqualTo(1)
            assertThat(journal.budgetExhausted).isTrue()
        }
    }

    @Test
    @DisplayName("성공한 호출은 토큰과 호출 수를 남긴다")
    fun `성공은 사용량을 남긴다`() {
        StubLlmServer().use { server ->
            server.replyWith(body = AnthropicTestSupport.successBody(inputTokens = 11, outputTokens = 22))
            val journal = LaneJournal()

            lane(server, journal).complete(AnthropicTestSupport.conversionPrompt(), LlmOptions())

            assertThat(journal.calls).isEqualTo(1)
            assertThat(journal.inputTokens).isEqualTo(11)
            assertThat(journal.outputTokens).isEqualTo(22)
            assertThat(journal.distribution()).isEmpty()
        }
    }

    @Test
    @DisplayName("보정 호출이 잘려도 변환은 성공으로 끝나지만, 호출 단위 절단으로는 잡힌다")
    fun `보정 절단을 문서 성공 뒤에서 잡는다`() {
        val journal = LaneJournal()
        val provider =
            LaneInstrumentedProvider(
                // ① 스타일 위반(50자 초과 한 문장)이 있는 초안 → 유스케이스가 보정을 부른다.
                // ② 보정 응답이 max_tokens 로 잘림 → `as? Outcome.Body` 가 실패해 원본 초안이 채택된다.
                delegate = ScriptedProvider(listOf(draft(LONG_SENTENCE), truncatedReply())),
                journal = journal,
                pause = { },
            )
        journal.beginDocument("g-001")

        val result = ConvertDocumentUseCase(provider).convert("안내문 원문입니다.")

        // 제품 동작: 문서 단위로는 **성공**이다(master-plan §3.3 「보정 실패 시 원본 채택」).
        assertThat(result).isInstanceOf(ConversionResult.Converted::class.java)
        // 측정: 그럼에도 상한에 닿은 호출이 하나 있었다는 사실이 남는다.
        assertThat(journal.conversionCalls).isEqualTo(2)
        assertThat(journal.truncatedConversionCalls).isEqualTo(1)
        assertThat(journal.truncatedCallsFor("g-001")).isEqualTo(1)
    }

    @Test
    @DisplayName("변환 호출이 잘리면 문서 단위와 호출 단위 양쪽에 잡힌다")
    fun `변환 절단은 양쪽에 잡힌다`() {
        val journal = LaneJournal()
        val provider = LaneInstrumentedProvider(ScriptedProvider(listOf(truncatedReply())), journal, pause = { })
        journal.beginDocument("g-002")

        val result = ConvertDocumentUseCase(provider).convert("안내문 원문입니다.")

        assertThat(result).isInstanceOf(ConversionResult.Failed::class.java)
        assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.TRUNCATED)
        assertThat(journal.truncatedConversionCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("재시도 간격은 지수로 벌어진다 — 서버가 밀어낼수록 더 기다린다")
    fun `backoff 는 지수로 늘어난다`() {
        val policy = LaneRetryPolicy(firstBackoff = Duration.ofSeconds(2), multiplier = 3)

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(6))
    }

    private fun lane(
        server: StubLlmServer,
        journal: LaneJournal,
    ): LlmProvider =
        LaneInstrumentedProvider(
            delegate = AnthropicProvider(settings(server)),
            journal = journal,
            policy = LaneRetryPolicy(maxAttempts = ATTEMPTS),
            // 테스트에서는 기다리지 않는다 — 간격 계산 자체는 위 backoff 테스트가 본다.
            pause = { },
        )

    private fun settings(server: StubLlmServer): AnthropicSettings =
        AnthropicSettings(apiKey = Secret(AnthropicTestSupport.TEST_API_KEY), baseUrl = server.baseUrl)

    private fun draft(text: String): LlmCompletion = reply(text, LlmFinishReason.END_TURN)

    /** `stop_reason=max_tokens` — 어댑터가 이 값을 보고 `LlmCompletion.truncated` 를 참으로 만든다. */
    private fun truncatedReply(): LlmCompletion = reply("잘린 본문", LlmFinishReason.MAX_TOKENS)

    private fun reply(
        text: String,
        finishReason: LlmFinishReason,
    ): LlmCompletion =
        LlmCompletion(
            text = text,
            provider = "scripted",
            model = "scripted-model",
            inputTokens = 100,
            outputTokens = 200,
            finishReason = finishReason,
        )

    /** 정해 둔 응답을 순서대로 돌려주는 대역. 스텁 서버가 필요 없는 만큼 시나리오를 정확히 고정한다. */
    private class ScriptedProvider(private val replies: List<LlmCompletion>) : LlmProvider {
        private var index = 0

        override val name: String = "scripted"

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion = replies[index++]
    }

    private companion object {
        const val ATTEMPTS: Int = 3
        const val TOO_MANY_REQUESTS: Int = 429
        const val SERVER_ERROR: Int = 503
        const val BAD_REQUEST: Int = 400
        const val ERROR_BODY: String = """{"type":"error"}"""
        val SLOW_REPLY: Duration = Duration.ofMillis(300)
        val SHORT_READ_TIMEOUT: Duration = Duration.ofMillis(80)

        /** `MAX_SENTENCE_CHARS`(50자)를 넘는 한 문장. 이것이 보정 패스를 부르는 조건이다. */
        const val LONG_SENTENCE: String =
            "이 안내문은 신청 기간과 제출 서류와 문의 방법과 접수 장소를 한 문장에 모두 담아 " +
                "아주 길게 이어지도록 쓴 문장이라 스타일 규칙의 길이 상한을 확실히 넘깁니다."
    }
}

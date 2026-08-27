package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmOptions
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

    private companion object {
        const val ATTEMPTS: Int = 3
        const val TOO_MANY_REQUESTS: Int = 429
        const val SERVER_ERROR: Int = 503
        const val BAD_REQUEST: Int = 400
        const val ERROR_BODY: String = """{"type":"error"}"""
        val SLOW_REPLY: Duration = Duration.ofMillis(300)
        val SHORT_READ_TIMEOUT: Duration = Duration.ofMillis(80)
    }
}

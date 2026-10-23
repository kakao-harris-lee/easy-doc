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
import java.math.BigDecimal
import java.time.Duration

/**
 * 레인 계측을 **실제 어댑터**에 물려 확인한다. 스텁 서버가 상대라 유료 호출이 아니다.
 *
 * 이 테스트가 있는 이유는 [LaneFaults] 가 어댑터의 예외 **문구**에 기대기 때문이다(예외 타입에는
 * 상태 코드가 없다). 문구가 바뀌면 유료 레인이 조용히 오분류하는 대신 여기가 깨져야 한다.
 */
class GoldenLlmLaneProviderTest {
    @Test
    fun `예산이 부족하면 네트워크 호출 전에 거절한다`() {
        val limit = LaneSpendLimit(BigDecimal("0.000001"), BigDecimal("2"), BigDecimal("10"))
        var calls = 0
        val delegate =
            object : LlmProvider {
                override val name = "test"

                override fun complete(
                    prompt: LlmPrompt,
                    options: LlmOptions,
                ): LlmCompletion {
                    calls++
                    error("호출하면 안 된다")
                }
            }
        val provider = LaneInstrumentedProvider(delegate, LaneJournal(), spendLimit = limit)
        assertThatThrownBy { provider.complete(AnthropicTestSupport.conversionPrompt(), LlmOptions()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("USD")
        assertThat(calls).isZero()
    }

    @Test
    @DisplayName("실패한 시도의 예약은 돌려주지 않는다 — 그 시도가 얼마를 썼는지 알 수 없다")
    fun `예산은 실패한 시도도 돌려주지 않고 누적 예약한다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        val worstCase = worstCaseReservation(prompt)
        val limit = LaneSpendLimit(BigDecimal("1"), PRICE_IN, PRICE_OUT)
        val provider = failingLane(limit)

        repeat(2) {
            assertThatThrownBy { provider.complete(prompt, LlmOptions()) }
                .isInstanceOf(LlmProviderException::class.java)
        }

        assertThat(limit.settledUsd).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(limit.heldUsd).isEqualByComparingTo(worstCase.multiply(BigDecimal("2")))
        assertThat(limit.committedUsd).isEqualByComparingTo(worstCase.multiply(BigDecimal("2")))
    }

    @Test
    @DisplayName("성공한 호출은 예약을 실제 비용으로 정산한다 — 싼 호출이 최악 상계 2건에 막히지 않는다")
    fun `성공한 호출은 실제 비용으로 정산한다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        // 최악 상계로는 두 번이면 차는 상한 — 정산하지 않으면 세 번째 호출에서 하드 스톱이다.
        val limit = LaneSpendLimit(worstCaseReservation(prompt).multiply(BigDecimal("2.5")), PRICE_IN, PRICE_OUT)
        val delegate = ScriptedProvider(List(CHEAP_CALLS) { costed(outputTokens = 20, cost = CHEAP_COST) })
        val provider = LaneInstrumentedProvider(delegate, LaneJournal(), spendLimit = limit, pause = { })

        repeat(CHEAP_CALLS) { provider.complete(prompt, LlmOptions()) }

        val actualTotal = BigDecimal(CHEAP_COST).multiply(CHEAP_CALLS.toBigDecimal())
        assertThat(limit.settledUsd).isEqualByComparingTo(actualTotal)
        assertThat(limit.heldUsd).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(limit.committedUsd).isEqualByComparingTo(actualTotal)
    }

    @Test
    @DisplayName("단가가 없는 응답은 실제 비용을 모르므로 예약을 그대로 붙잡아 둔다")
    fun `단가 없는 응답은 예약을 유지한다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        val worstCase = worstCaseReservation(prompt)
        val limit = LaneSpendLimit(BigDecimal("1"), PRICE_IN, PRICE_OUT)
        val delegate = ScriptedProvider(listOf(draft("단가 없는 응답")))
        val provider = LaneInstrumentedProvider(delegate, LaneJournal(), spendLimit = limit, pause = { })

        provider.complete(prompt, LlmOptions())

        assertThat(limit.settledUsd).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(limit.heldUsd).isEqualByComparingTo(worstCase)
        assertThat(limit.committedUsd).isEqualByComparingTo(worstCase)
    }

    @Test
    @DisplayName("정산 뒤에도 다음 최악 상계가 상한을 넘으면 provider 를 부르기 전에 멈춘다")
    fun `정산 뒤에도 하드 스톱은 그대로다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        val cheap = BigDecimal(CHEAP_COST)
        // 최악 상계 하나가 겨우 들어가는 상한. 싼 호출은 정산 덕에 통과하지만, 정산액이
        // 쌓이면 「정산액 + 다음 상계」가 먼저 상한을 넘는다.
        val limit =
            LaneSpendLimit(
                worstCaseReservation(prompt) + cheap.multiply(CAP_HEADROOM_IN_CHEAP_CALLS),
                PRICE_IN,
                PRICE_OUT,
            )
        // 대역에 응답이 통과할 횟수만큼만 있다 — 그다음 시도가 provider 까지 갔다면 IndexOutOfBounds 로 깨진다.
        val delegate = ScriptedProvider(List(SETTLED_CALLS) { costed(outputTokens = 20, cost = CHEAP_COST) })
        val provider = LaneInstrumentedProvider(delegate, LaneJournal(), spendLimit = limit, pause = { })

        repeat(SETTLED_CALLS) { provider.complete(prompt, LlmOptions()) }

        assertThatThrownBy { provider.complete(prompt, LlmOptions()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("USD")
        assertThat(limit.settledUsd).isEqualByComparingTo(cheap.multiply(SETTLED_CALLS.toBigDecimal()))
        assertThat(limit.heldUsd).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("일시적 실패 뒤 성공하면 실패한 시도의 예약만 남고 성공한 시도는 정산한다")
    fun `재시도로 성공하면 실패한 시도의 예약만 남는다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        val worstCase = worstCaseReservation(prompt)
        val limit = LaneSpendLimit(worstCase.multiply(BigDecimal("3")), PRICE_IN, PRICE_OUT)
        val delegate =
            FlakyProvider(
                fault = "test 호출 실패 (HTTP $TOO_MANY_REQUESTS)",
                reply = costed(outputTokens = 20, cost = CHEAP_COST),
            )
        val provider =
            LaneInstrumentedProvider(
                delegate = delegate,
                journal = LaneJournal(),
                policy = LaneRetryPolicy(maxAttempts = 2),
                pause = { },
                spendLimit = limit,
            )

        provider.complete(prompt, LlmOptions())

        assertThat(delegate.attempts).isEqualTo(2)
        // 실패한 시도의 예약은 그대로, 성공한 시도의 예약만 실제 비용으로 바뀐다.
        assertThat(limit.heldUsd).isEqualByComparingTo(worstCase)
        assertThat(limit.settledUsd).isEqualByComparingTo(BigDecimal(CHEAP_COST))
        assertThat(limit.committedUsd).isEqualByComparingTo(worstCase + BigDecimal(CHEAP_COST))
    }

    @Test
    @DisplayName("음수 실제 비용은 호출 코드의 실수다 — 정산으로 지출을 깎지 못한다")
    fun `음수 실제 비용은 정산하지 않는다`() {
        val limit = LaneSpendLimit(BigDecimal("1"), PRICE_IN, PRICE_OUT)

        assertThatThrownBy { limit.settle(BigDecimal.ZERO, BigDecimal("-0.000001")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("붙잡고 있지 않은 예약은 정산하지 못한다 — 예약 합계가 음수로 내려가지 않는다")
    fun `붙잡은 예약보다 큰 정산은 거절한다`() {
        val prompt = AnthropicTestSupport.conversionPrompt()
        val limit = LaneSpendLimit(BigDecimal("1"), PRICE_IN, PRICE_OUT)
        val reservation = limit.reserve(prompt, LlmOptions())

        assertThatThrownBy { limit.settle(reservation.multiply(BigDecimal("2")), BigDecimal(CHEAP_COST)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(limit.heldUsd).isEqualByComparingTo(reservation)
    }

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
                // ① 자동 보정 대상인 이중 피동이 있는 초안 → 유스케이스가 보정을 부른다.
                // ② 보정 응답이 max_tokens 로 잘림 → `as? Outcome.Body` 가 실패해 원본 초안이 채택된다.
                delegate = ScriptedProvider(listOf(draft(REPAIRABLE_TEXT), truncatedReply())),
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
    @DisplayName("호출 비용은 변환·judge 구분 없이 BigDecimal 로 합산한다")
    fun `비용을 합산한다`() {
        val journal = LaneJournal()

        journal.recordCall(costed(outputTokens = 100, cost = "1.50"))
        journal.beginJudge("g-001")
        journal.recordCall(costed(outputTokens = 5, cost = "0.25"))

        assertThat(journal.estimatedCostUsd).isEqualByComparingTo(BigDecimal("1.75"))
        assertThat(journal.costUnknownCalls).isZero()
        assertThat(journal.pricingInputUsdPerMtok).isEqualByComparingTo(BigDecimal("2"))
        assertThat(journal.pricingOutputUsdPerMtok).isEqualByComparingTo(BigDecimal("10"))
    }

    @Test
    @DisplayName("비용은 합계와 별도로 문서(회차)별 변환·judge 몫으로도 갈라 센다")
    fun `문서별로 변환 비용과 judge 비용을 가른다`() {
        val journal = LaneJournal()

        journal.beginDocument("g-001")
        journal.recordCall(costed(outputTokens = 100, cost = "1.50"))
        journal.recordCall(costed(outputTokens = 80, cost = "0.90"))
        journal.beginJudge("g-001")
        journal.recordCall(costed(outputTokens = 5, cost = "0.25"))
        journal.beginDocument("g-002")
        journal.recordCall(costed(outputTokens = 20, cost = "0.10"))

        assertThat(journal.conversionCostFor("g-001")).isEqualByComparingTo(BigDecimal("2.40"))
        assertThat(journal.judgeCostFor("g-001")).isEqualByComparingTo(BigDecimal("0.25"))
        assertThat(journal.conversionCostFor("g-002")).isEqualByComparingTo(BigDecimal("0.10"))
        // judge 를 부르지 않은 문서의 judge 비용은 0달러가 아니라 없는 값이다.
        assertThat(journal.judgeCostFor("g-002")).isNull()
        assertThat(journal.estimatedCostUsd).isEqualByComparingTo(BigDecimal("2.75"))
    }

    @Test
    @DisplayName("단가가 없으면 문서별 비용도 0달러가 아니라 null 이다")
    fun `단가가 없으면 문서별 비용도 null 이다`() {
        val journal = LaneJournal()

        journal.beginDocument("g-001")
        journal.recordCall(draft("단가 없는 호출"))

        assertThat(journal.conversionCostFor("g-001")).isNull()
        assertThat(journal.judgeCostFor("g-001")).isNull()
        assertThat(journal.costUnknownCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("단가가 통째로 없으면 합계도 0달러가 아니라 null 이고 건수만 센다")
    fun `단가가 전부 없으면 합계는 null 이다`() {
        val journal = LaneJournal()

        journal.recordCall(draft("본문"))
        journal.recordCall(draft("본문2"))

        assertThat(journal.estimatedCostUsd).isNull()
        assertThat(journal.costUnknownCalls).isEqualTo(2)
    }

    @Test
    @DisplayName("일부 호출만 단가가 없으면 합계는 아는 만큼만 더하고 모르는 건수를 따로 센다")
    fun `일부만 단가가 없으면 부분합과 건수를 함께 낸다`() {
        val journal = LaneJournal()

        journal.recordCall(costed(outputTokens = 100, cost = "1.50"))
        journal.recordCall(draft("단가 없는 호출"))

        assertThat(journal.estimatedCostUsd).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(journal.costUnknownCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("단가 없는 호출이 먼저 와도 단가 스냅샷이 null 로 굳지 않는다")
    fun `단가 없는 호출이 먼저 와도 스냅샷이 굳지 않는다`() {
        val journal = LaneJournal()

        journal.recordCall(draft("단가 없는 첫 호출"))
        journal.recordCall(costed(outputTokens = 100, cost = "1.50"))

        assertThat(journal.pricingInputUsdPerMtok).isEqualByComparingTo(BigDecimal("2"))
        assertThat(journal.pricingOutputUsdPerMtok).isEqualByComparingTo(BigDecimal("10"))
        assertThat(journal.estimatedCostUsd).isEqualByComparingTo(BigDecimal("1.50"))
        assertThat(journal.costUnknownCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("judge 호출 실패는 변환 실패 원인을 덮지 않는다 — 회차 줄이 읽는 값은 변환 쪽이다")
    fun `judge 실패는 변환 원인을 덮지 않는다`() {
        val journal = LaneJournal()

        journal.beginDocument("g-001")
        journal.recordFault(LaneFault("ResourceAccessException", status = null, transient = true), retried = true)
        journal.beginJudge("g-001")
        journal.recordFault(LaneFault("HTTP 429", status = 429, transient = true), retried = false)

        assertThat(journal.lastConversionFault("g-001")?.label).isEqualTo("ResourceAccessException")
        // 두 갈래를 가려 센 뒤에도 흔들림 분포에는 둘 다 남는다 — 가려진 실패를 지우지 않는다.
        assertThat(journal.distribution()).containsEntry("HTTP 429", 1).containsEntry("ResourceAccessException", 1)
    }

    @Test
    @DisplayName("재시도 간격은 지수로 벌어진다 — 서버가 밀어낼수록 더 기다린다")
    fun `backoff 는 지수로 늘어난다`() {
        val policy = LaneRetryPolicy(firstBackoff = Duration.ofSeconds(2), multiplier = 3)

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(6))
    }

    /** 하드 스톱에 걸리지 않을 넉넉한 상한으로 상계식만 빌려 쓴다 — 시험 상한을 상계에 맞추기 위해서다. */
    private fun worstCaseReservation(prompt: LlmPrompt): BigDecimal =
        LaneSpendLimit(BigDecimal("1000"), PRICE_IN, PRICE_OUT).reservationFor(prompt, LlmOptions())

    /** 한 시도로 끝나는 실패 레인 — 재시도가 예약 수를 늘리지 않도록 시도 상한을 1로 둔다. */
    private fun failingLane(limit: LaneSpendLimit): LlmProvider =
        LaneInstrumentedProvider(
            delegate =
                object : LlmProvider {
                    override val name = "test"

                    override fun complete(
                        prompt: LlmPrompt,
                        options: LlmOptions,
                    ): LlmCompletion = throw LlmProviderException("test 호출 실패 (HTTP 400)")
                },
            journal = LaneJournal(),
            policy = LaneRetryPolicy(maxAttempts = 1),
            pause = { },
            spendLimit = limit,
        )

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

    /** 단가가 설정된(비용을 낸) 응답. 입력 2 / 출력 10 USD/MTok 고정 — [cost] 만 호출마다 바꾼다. */
    private fun costed(
        outputTokens: Int,
        cost: String,
    ): LlmCompletion =
        LlmCompletion(
            text = "결과",
            provider = "scripted",
            model = "scripted-model",
            inputTokens = 10,
            outputTokens = outputTokens,
            finishReason = LlmFinishReason.END_TURN,
            estimatedCostUsd = BigDecimal(cost),
            pricingInputUsdPerMtok = BigDecimal("2"),
            pricingOutputUsdPerMtok = BigDecimal("10"),
        )

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

    /** 첫 시도만 [fault] 로 실패하고 다음 시도에 [reply] 를 돌려주는 대역 — 유료 레인이 실제로 걷는 길이다. */
    private class FlakyProvider(
        private val fault: String,
        private val reply: LlmCompletion,
    ) : LlmProvider {
        var attempts: Int = 0
            private set

        override val name: String = "flaky"

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion {
            attempts++
            if (attempts == 1) {
                throw LlmProviderException(fault)
            }
            return reply
        }
    }

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

        /** 예산 테스트가 쓰는 단가(USD/MTok) — [costed] 가 내는 응답의 단가 스냅샷과 같은 값이다. */
        val PRICE_IN: BigDecimal = BigDecimal("2")
        val PRICE_OUT: BigDecimal = BigDecimal("10")

        /** 최악 상계보다 두 자리 이상 싼 실제 비용. 정산하면 상한 안에서 여러 번 부를 수 있다. */
        const val CHEAP_COST: String = "0.001000"
        const val CHEAP_CALLS: Int = 5

        /** 하드 스톱 시험의 상한 여유 — 최악 상계 하나에 싼 호출 몇 건분만 더 얹는다. */
        val CAP_HEADROOM_IN_CHEAP_CALLS: BigDecimal = BigDecimal("3.5")

        /** 그 상한에서 정산으로 통과하는 호출 수. 다음 호출은 「정산액 + 최악 상계」가 상한을 넘는다. */
        const val SETTLED_CALLS: Int = 4

        const val TOO_MANY_REQUESTS: Int = 429
        const val SERVER_ERROR: Int = 503
        const val BAD_REQUEST: Int = 400
        const val ERROR_BODY: String = """{"type":"error"}"""
        val SLOW_REPLY: Duration = Duration.ofMillis(300)
        val SHORT_READ_TIMEOUT: Duration = Duration.ofMillis(80)

        /** 문서 길이에 관계없이 보정을 요청하는 이중 피동 표현이다. */
        const val REPAIRABLE_TEXT: String = "신청 안내문이 보여지고 있습니다."
    }
}

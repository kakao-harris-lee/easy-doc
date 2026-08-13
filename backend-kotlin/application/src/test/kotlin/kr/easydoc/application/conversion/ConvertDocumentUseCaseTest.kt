package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.exceptions.LlmEmptyResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmFinishReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 변환 오케스트레이션 — CNV-01(호출 상한)·CNV-02(4대 예외)·CNV-04(보정 채택).
 *
 * 요구 정본은 `00_requirements-inventory.md` §3.1 이고, 같은 성질을 fixture 25건이
 * `ConversionParityTest` 에서 값으로 판정한다. **이 파일은 그 중복이 아니다** — parity 는
 * "만족하는가"를, 여기서는 "왜 그런가"와 fixture 가 못 담는 것(예산 초과 시 터지는가,
 * 관대한 provider 를 줘도 멈추는가)을 본다.
 *
 * **실제 LLM API 를 부르지 않는다.** 전부 `FakeLlmProvider` 다.
 */
class ConvertDocumentUseCaseTest {
    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    /** fixture 가 쓰는 것과 같은 원문. 위반이 있는 1차 결과를 만들기 위한 입력이다. */
    private val source = "금일 서류를 제출하십시오."

    /** 규칙 위반이 남아 있는 1차 변환 결과 — '금일'이 어려운 말 사전에 있다. */
    private val draftWithIssue = "금일 서류를 내세요."

    /** 위반이 없는 결과. */
    private val cleanText = "오늘 서류를 내세요."

    private fun useCase(provider: FakeLlmProvider) = ConvertDocumentUseCase(provider, fixedIds)

    private fun reply(
        text: String,
        truncated: Boolean = false,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ) = FakeLlmTurn.Reply(
        text = text,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        finishReason = if (truncated) LlmFinishReason.MAX_TOKENS else LlmFinishReason.END_TURN,
    )

    private fun converted(result: ConversionResult): ConversionResult.Converted =
        result as? ConversionResult.Converted ?: error("변환이 실패했다: $result")

    @Test
    @DisplayName("전제 확인 — 이 파일이 쓰는 두 본문의 위반 유무")
    fun `테스트 전제가 성립한다`() {
        // 이 전제가 깨지면 아래 케이스들이 "보정을 부르지 않아서" 우연히 통과한다.
        // 어려운 말 사전이 바뀌면 여기서 먼저 빨개져야 원인을 찾을 수 있다.
        assertThat(checkStyle(draftWithIssue).issues)
            .withFailMessage("'$draftWithIssue' 에 위반이 없다 — 보정 경로를 타지 않는다")
            .isNotEmpty()
        assertThat(checkStyle(cleanText).issues)
            .withFailMessage("'$cleanText' 에 위반이 있다 — 깨끗한 결과 경로를 탈 수 없다")
            .isEmpty()
    }

    @Nested
    @DisplayName("호출 상한 (CNV-01)")
    inner class CallBudget {
        @Test
        @DisplayName("위반이 없으면 보정을 부르지 않는다 — 정확히 1회")
        fun `깨끗하면 한 번만 부른다`() {
            // '항상 2회'는 상한을 지키면서 요구를 어긴다 — 크레딧 원가 산정이 두 배로 어긋난다.
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            val result = useCase(provider).convert(source)

            assertThat(result.usage.llmCalls).isEqualTo(1)
            assertThat(provider.calls).hasSize(1)
        }

        @Test
        @DisplayName("보정 결과에 위반이 남아 있어도 다시 부르지 않는다 — 관대한 provider 로 잰다")
        fun `루프가 아니다`() {
            // **엄격한 fake 로는 이 성질을 못 잰다.** 대본을 2건만 주면 3번째 호출에서
            // 하네스가 죽어 "터졌다"는 사실만 남고 몇 번 불렀는지는 알 수 없다. 응답을 넉넉히
            // 주고 남은 수를 보는 것이 "루프가 아니다"의 직접 증거다.
            val provider = FakeLlmProvider(List(10) { reply(draftWithIssue) })

            val result = useCase(provider).convert(source)

            assertThat(result.usage.llmCalls).isEqualTo(2)
            assertThat(provider.calls).hasSize(2)
            assertThat(provider.unusedTurns).isEqualTo(8)
        }

        @Test
        @DisplayName("전송 재전송은 완성 요청 수에 들어가지 않는다 — 분리 계측")
        fun `전송 시도와 완성 요청을 따로 센다`() {
            // 계측 지점을 HTTP 요청으로 잡으면 상한이 어댑터 재시도 설정에 따라 흔들리고,
            // 모델에게 실제로 몇 번 물었는지도 잃는다.
            val provider = FakeLlmProvider(listOf(reply(cleanText)), transportAttemptsPerCall = 3)

            val result = useCase(provider).convert(source)

            assertThat(result.usage.llmCalls).isEqualTo(1)
            assertThat(provider.transportAttempts).isEqualTo(3)
        }

        @Test
        @DisplayName("예산은 사후 카운터가 아니라 즉시 터지는 장치다")
        fun `예산을 넘기면 던진다`() {
            val budget = CompletionBudget(limit = 2)
            budget.spend { }
            budget.spend { }

            assertThatThrownBy { budget.spend { } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("상한")
        }
    }

    @Nested
    @DisplayName("1차 호출의 4대 예외 — 변환 실패 (CNV-02·CNV-03)")
    inner class FirstCallFailures {
        @Test
        @DisplayName("응답이 잘리면 실패다 — 잘린 본문을 성공으로 내보내지 않는다")
        fun `절단은 실패다`() {
            val provider = FakeLlmProvider(listOf(reply("쉬운 글이 도중에", truncated = true)))

            val result = useCase(provider).convert(source)

            assertThat(result).isInstanceOf(ConversionResult.Failed::class.java)
            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.TRUNCATED)
            // 보정으로 덮지 않는다 — 1차가 잘렸는데 보정을 부르면 잘린 본문을 다듬는 셈이다.
            assertThat(result.usage.llmCalls).isEqualTo(1)
        }

        @Test
        @DisplayName("후처리 뒤 본문이 남지 않으면 실패다 — 껍데기만 온 응답도 같다")
        fun `빈 결과는 실패다`() {
            val provider = FakeLlmProvider(listOf(reply("```\n```")))

            val result = useCase(provider).convert(source)

            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.EMPTY_RESULT)
            assertThat(result.usage.llmCalls).isEqualTo(1)
        }

        @Test
        @DisplayName("호출 자체가 실패하면 실패다 — 보정 위치와 대칭이 아니다")
        fun `호출 실패는 실패다`() {
            val provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패"))))

            val result = useCase(provider).convert(source)

            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.PROVIDER_ERROR)
        }

        @Test
        @DisplayName("provider 예외의 종류를 실패 코드로 옮긴다 — 메시지를 파싱하지 않는다")
        fun `예외 타입으로 가른다`() {
            val truncated = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmTruncatedException("x"))))
            val empty = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmEmptyResultException("x"))))

            assertThat((useCase(truncated).convert(source) as ConversionResult.Failed).kind)
                .isEqualTo(ConversionFailureKind.TRUNCATED)
            assertThat((useCase(empty).convert(source) as ConversionResult.Failed).kind)
                .isEqualTo(ConversionFailureKind.EMPTY_RESULT)
        }
    }

    @Nested
    @DisplayName("보정 호출의 같은 사건 — 1차 결과 채택 (CNV-02·CNV-04)")
    inner class RepairFailuresAreSwallowed {
        @Test
        @DisplayName("보정이 잘리면 1차 결과를 채택하고 변환은 성공한다")
        fun `보정 절단을 삼킨다`() {
            val provider =
                FakeLlmProvider(listOf(reply(draftWithIssue), reply("오늘 서류를", truncated = true)))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.repaired).isFalse()
            // 실패한 보정 호출도 상한에는 센다 — 다시 부르지 않는다.
            assertThat(result.usage.llmCalls).isEqualTo(2)
        }

        @Test
        @DisplayName("보정이 비면 1차 결과를 채택한다")
        fun `보정 빈 결과를 삼킨다`() {
            val provider = FakeLlmProvider(listOf(reply(draftWithIssue), reply("   ")))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.repaired).isFalse()
        }

        @Test
        @DisplayName("보정 호출이 실패해도 변환은 성공한다 — 받을 수 있었던 결과를 뺏지 않는다")
        fun `보정 호출 실패를 삼킨다`() {
            val provider =
                FakeLlmProvider(listOf(reply(draftWithIssue), FakeLlmTurn.Fail(LlmProviderException("실패"))))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.usage.llmCalls).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("보정 채택 판정 (CNV-04)")
    inner class RepairAdoption {
        @Test
        @DisplayName("자리표시자를 지키며 위반을 줄이면 보정문을 채택한다")
        fun `개선하면 채택한다`() {
            // 이 케이스가 없으면 '보정을 항상 버리는' 구현이 나머지를 전부 통과한다 —
            // 보정 호출 비용만 치르고 품질은 그대로인 상태다.
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply(draftWithIssue, inputTokens = 120, outputTokens = 45),
                        reply(cleanText, inputTokens = 80, outputTokens = 30),
                    ),
                )

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(cleanText)
            assertThat(result.repaired).isTrue()
            assertThat(result.usage.inputTokens).isEqualTo(200)
            assertThat(result.usage.outputTokens).isEqualTo(75)
        }

        @Test
        @DisplayName("악화되면 1차 결과를 채택하되 토큰은 두 호출의 합이다")
        fun `악화되면 기각하고 토큰은 합산한다`() {
            // 부른 순간 비용은 발생했다. 버린 호출의 토큰을 빼면 원가가 실제보다 적게 잡힌다.
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply(draftWithIssue, inputTokens = 120, outputTokens = 45),
                        reply(source, inputTokens = 80, outputTokens = 30),
                    ),
                )

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.repaired).isFalse()
            assertThat(result.usage.inputTokens).isEqualTo(200)
            assertThat(result.usage.outputTokens).isEqualTo(75)
        }

        @Test
        @DisplayName("보정이 자리표시자를 잃으면 기각하고, 유실 목록은 채택본 기준으로 비어 있다")
        fun `자리표시자를 잃으면 기각한다`() {
            val withRrn = "금일 등록번호 900101-1234567 을 확인하십시오."
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply("금일 [[주민등록번호1]]을 확인하세요."),
                        reply("오늘 번호를 확인하세요."),
                    ),
                )

            val result = converted(useCase(provider).convert(withRrn))

            assertThat(result.easyText.value).isEqualTo("금일 [[주민등록번호1]]을 확인하세요.")
            assertThat(result.repaired).isFalse()
            // 1차 결과에 대고 산출하면 여기서 유실이 잘못 보고돼 내보내기가 409 로 막힌다.
            assertThat(result.missingPlaceholders).isEmpty()
        }
    }

    @Nested
    @DisplayName("자리표시자 유실 보고 (CNV-02 · INV-03 인접)")
    inner class MissingPlaceholders {
        private val withRrn = "금일 등록번호 900101-1234567 을 확인하십시오."

        @Test
        @DisplayName("지워지면 라벨을 담되 예외로 막지 않는다")
        fun `유실은 보고하되 실패시키지 않는다`() {
            // 개인정보가 새는 방향이 아니라 표시가 사라지는 방향이다. 여기서 실패로 처리하면
            // 쓸 만한 결과를 통째로 버린다 — 사람이 원문과 대조하도록 검수 화면으로 넘긴다.
            val provider = FakeLlmProvider(listOf(reply("오늘 번호를 확인하세요.")))

            val result = converted(useCase(provider).convert(withRrn))

            assertThat(result.missingPlaceholders).containsExactly("[[주민등록번호1]]")
        }

        @Test
        @DisplayName("기준 본문은 채택된 최종 결과다 — 보정이 되살렸으면 목록은 비어 있다")
        fun `기준은 채택본이다`() {
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply("금일 번호를 확인하세요."),
                        reply("오늘 [[주민등록번호1]]을 확인하세요."),
                    ),
                )

            val result = converted(useCase(provider).convert(withRrn))

            assertThat(result.repaired).isTrue()
            assertThat(result.missingPlaceholders).isEmpty()
        }

        @Test
        @DisplayName("둘 중 하나만 사라지면 사라진 것만 보고한다")
        fun `사라진 것만 담는다`() {
            val both = "금일 등록번호 900101-1234567 과 카드 4111-1111-1111-1111 을 확인하십시오."
            val provider = FakeLlmProvider(listOf(reply("오늘 [[주민등록번호1]]을 확인하세요.")))

            val result = converted(useCase(provider).convert(both))

            assertThat(result.missingPlaceholders).containsExactly("[[카드번호1]]")
        }
    }

    @Nested
    @DisplayName("마스킹 선행 불변식")
    inner class MaskingComesFirst {
        @Test
        @DisplayName("원문 개인정보는 어느 프롬프트에도 실리지 않는다")
        fun `프롬프트에 원문이 없다`() {
            val withRrn = "금일 신청자 900101-1234567 님께 안내하십시오."
            val provider = FakeLlmProvider(listOf(reply(draftWithIssue), reply(cleanText)))

            useCase(provider).convert(withRrn)

            assertThat(provider.calls).hasSize(2)
            provider.calls.forEach { call ->
                assertThat(call.prompt.user)
                    .withFailMessage("마스킹 전 원문이 프롬프트에 실렸다 — 개인정보가 그대로 외부 모델로 나간다")
                    .doesNotContain("900101-1234567")
                assertThat(call.prompt.system).doesNotContain("900101-1234567")
            }
        }

        @Test
        @DisplayName("결과 문자열 표현에 본문이 실리지 않는다")
        fun `toString 이 본문을 흘리지 않는다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            val rendered = useCase(provider).convert(source).toString()

            assertThat(rendered).doesNotContain(cleanText)
            assertThat(rendered).contains("자")
        }
    }
}

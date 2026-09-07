package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.StructureHintOptions
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.renderStructureSection
import kr.easydoc.core.exceptions.LlmEmptyResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import kr.easydoc.core.segment.splitUnits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** 변환 오케스트레이션 — CNV-01(호출 상한)·CNV-02(4대 예외)·CNV-04(보정 채택). */
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
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            val result = useCase(provider).convert(source)

            assertThat(result.usage.llmCalls).isEqualTo(1)
            assertThat(provider.calls).hasSize(1)
        }

        @Test
        @DisplayName("보정 결과에 위반이 남아 있어도 다시 부르지 않는다 — 관대한 provider 로 잰다")
        fun `루프가 아니다`() {
            val provider = FakeLlmProvider(List(10) { reply(draftWithIssue) })

            val result = useCase(provider).convert(source)

            assertThat(result.usage.llmCalls).isEqualTo(2)
            assertThat(provider.calls).hasSize(2)
            assertThat(provider.unusedTurns).isEqualTo(8)
        }

        @Test
        @DisplayName("전송 재전송은 완성 요청 수에 들어가지 않는다 — 분리 계측")
        fun `전송 시도와 완성 요청을 따로 센다`() {
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
    @DisplayName("빈 본문 응답의 분류 — 교차 종합 C-08")
    inner class EmptyBodyClassification {
        private fun emptyReply(
            truncated: Boolean = false,
            refusal: Boolean = false,
            inputTokens: Int = 0,
            outputTokens: Int = 0,
        ) = FakeLlmTurn.Reply(
            text = "",
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            finishReason =
                when {
                    truncated -> LlmFinishReason.MAX_TOKENS
                    refusal -> LlmFinishReason.REFUSAL
                    else -> LlmFinishReason.END_TURN
                },
        )

        @Test
        @DisplayName("1차: MAX_TOKENS + 빈 본문은 절단이다 (빈 결과가 아니다)")
        fun `빈 절단은 절단으로 기록된다`() {
            val provider = FakeLlmProvider(listOf(emptyReply(truncated = true, inputTokens = 40)))

            val result = useCase(provider).convert(source)

            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.TRUNCATED)

            assertThat(result.usage.inputTokens).isEqualTo(40)
        }

        @Test
        @DisplayName("1차: END_TURN + 빈 본문은 빈 결과다")
        fun `빈 정상종료는 빈 결과다`() {
            val provider = FakeLlmProvider(listOf(emptyReply(inputTokens = 40)))

            val result = useCase(provider).convert(source)

            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.EMPTY_RESULT)
            assertThat(result.usage.inputTokens).isEqualTo(40)
        }

        @Test
        @DisplayName("1차: REFUSAL 은 빈 결과와 구분한다 — 값으로 가른다")
        fun `거절은 빈 결과가 아니다`() {
            val provider = FakeLlmProvider(listOf(emptyReply(refusal = true)))

            val result = useCase(provider).convert(source)

            assertThat((result as ConversionResult.Failed).kind).isEqualTo(ConversionFailureKind.PROVIDER_ERROR)
        }

        @Test
        @DisplayName("보정: MAX_TOKENS + 빈 본문이어도 1차 결과를 채택하고 토큰은 합산한다")
        fun `보정의 빈 절단을 삼키고 토큰은 합산한다`() {
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply(draftWithIssue, inputTokens = 120, outputTokens = 45),
                        emptyReply(truncated = true, inputTokens = 80, outputTokens = 30),
                    ),
                )

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.repaired).isFalse()

            assertThat(result.usage.inputTokens).isEqualTo(200)
            assertThat(result.usage.outputTokens).isEqualTo(75)
        }

        @Test
        @DisplayName("보정: END_TURN + 빈 본문도 1차 결과를 채택하고 토큰은 합산한다")
        fun `보정의 빈 정상종료를 삼키고 토큰은 합산한다`() {
            val provider =
                FakeLlmProvider(
                    listOf(
                        reply(draftWithIssue, inputTokens = 120, outputTokens = 45),
                        emptyReply(inputTokens = 80, outputTokens = 30),
                    ),
                )

            val result = converted(useCase(provider).convert(source))

            assertThat(result.easyText.value).isEqualTo(draftWithIssue)
            assertThat(result.usage.inputTokens).isEqualTo(200)
            assertThat(result.usage.outputTokens).isEqualTo(75)
        }
    }

    @Nested
    @DisplayName("보정 채택 판정 (CNV-04)")
    inner class RepairAdoption {
        @Test
        @DisplayName("위반을 줄이면 보정문을 채택한다")
        fun `개선하면 채택한다`() {
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
    }

    @Nested
    @DisplayName("사전 컨텍스트 주입")
    inner class DictionaryContextInjection {
        private val context = "[문서 사전]\n- 금일: 오늘"

        @Test
        @DisplayName("1차 변환 프롬프트에만 싣는다 — A/B 에서 바뀌는 변수는 하나여야 한다")
        fun `보정 프롬프트에는 싣지 않는다`() {
            val provider = FakeLlmProvider(listOf(reply(draftWithIssue), reply(cleanText)))

            useCase(provider).convert(source, dictionaryContext = context)

            assertThat(provider.calls).hasSize(2)
            assertThat(provider.calls[0].prompt.user).startsWith(context)
            assertThat(provider.calls[1].prompt.user).doesNotContain(context)
        }

        @Test
        @DisplayName("주지 않으면 프롬프트가 달라지지 않는다 — 베이스라인 측정 조건")
        fun `기본값은 주입하지 않는다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            useCase(provider).convert(source)

            assertThat(provider.calls).hasSize(1)
            assertThat(provider.calls[0].prompt.user).startsWith("<문서 id=")
        }

        @Test
        @DisplayName("명시 인자가 없으면 포트에 묻는다 — 제품 worker 가 타는 경로")
        fun `포트가 준 컨텍스트를 싣는다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))
            val useCase = ConvertDocumentUseCase(provider, fixedIds) { context }

            useCase.convert(source)

            assertThat(provider.calls[0].prompt.user).startsWith(context)
        }

        @Test
        @DisplayName("명시 인자가 포트를 이긴다 — 골든 LLM 레인의 문서별 A/B 가 계속 성립해야 한다")
        fun `명시 인자가 우선한다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))
            val fromPort = "[문서 사전]\n- 포트가 준 것"
            val useCase = ConvertDocumentUseCase(provider, fixedIds) { fromPort }

            useCase.convert(source, dictionaryContext = context)

            assertThat(provider.calls[0].prompt.user).startsWith(context)
            assertThat(provider.calls[0].prompt.user).doesNotContain(fromPort)
        }

        @Test
        @DisplayName("포트는 실제로 프롬프트에 실릴 문서 본문을 그대로 받는다")
        fun `포트에 문서 본문이 그대로 간다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))
            val seen = mutableListOf<String>()
            val useCase =
                ConvertDocumentUseCase(provider, fixedIds) { documentText ->
                    seen += documentText
                    null
                }

            useCase.convert("금일 신청 안내입니다.")

            assertThat(seen).containsExactly("금일 신청 안내입니다.")
        }
    }

    @Nested
    @DisplayName("사실 보존 트리거 (backlog §1.3)")
    inner class FactPreservationTrigger {
        @Test
        @DisplayName("문체는 깨끗해도 사실이 빠지면 보정을 부른다 — 값이 프롬프트에 실린다")
        fun `사실 누락이 보정을 부른다`() {
            val source = "02-1234-5678로 문의하세요."
            val draftMissingPhone = "문의하세요."
            val repaired = "문의는 02-1234-5678입니다."
            val provider = FakeLlmProvider(listOf(reply(draftMissingPhone), reply(repaired)))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.usage.llmCalls).isEqualTo(2)
            assertThat(result.repaired).isTrue()
            assertThat(result.easyText.value).isEqualTo(repaired)
            assertThat(provider.calls[1].prompt.user).contains("[빠진 사실]")
            assertThat(provider.calls[1].prompt.user).contains("02-1234-5678")
        }

        @Test
        @DisplayName("문체와 사실이 모두 깨끗하면 보정을 부르지 않는다")
        fun `사실과 문체가 깨끗하면 한 번만 부른다`() {
            val source = "02-1234-5678로 9월 4일까지 문의하세요."
            val draft = "문의는 02-1234-5678입니다. 마감은 9월 4일입니다."
            val provider = FakeLlmProvider(listOf(reply(draft)))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.usage.llmCalls).isEqualTo(1)
            assertThat(result.repaired).isFalse()
            assertThat(result.easyText.value).isEqualTo(draft)
        }

        @Test
        @DisplayName("보정문이 다른 사실을 새로 빠뜨리면 기각한다 — 문체만 봤으면 채택했을 사례")
        fun `보정이 다른 사실을 빠뜨리면 기각한다`() {
            val source = "금일 02-1234-5678로 문의하세요."
            // 1차 결과: 문체 위반('금일')은 있지만 사실(전화번호)은 지켜졌다.
            val draftWithStyleIssue = "금일 문의는 02-1234-5678입니다."
            // 보정 후보: 문체는 고쳤지만 전화번호를 통째로 날렸다.
            val candidateDroppingFact = "오늘 문의하세요."
            val provider = FakeLlmProvider(listOf(reply(draftWithStyleIssue), reply(candidateDroppingFact)))

            val result = converted(useCase(provider).convert(source))

            assertThat(result.usage.llmCalls).isEqualTo(2)
            assertThat(result.repaired)
                .withFailMessage("문체만 봤으면 채택됐을 보정이 사실 누락 때문에 기각되지 않았다")
                .isFalse()
            assertThat(result.easyText.value).isEqualTo(draftWithStyleIssue)
        }
    }

    @Nested
    @DisplayName("구조 힌트 전달 — P0-4 S8-2")
    inner class StructureHintPropagation {
        @Test
        @DisplayName("구조를 주지 않으면 프롬프트가 기존과 달라지지 않는다")
        fun `기본값은 구조 절을 만들지 않는다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            useCase(provider).convert(source)

            assertThat(provider.calls[0].prompt.user).doesNotContain("[구조]")
        }

        @Test
        @DisplayName("표 칸 구조를 주면 1차·보정 두 프롬프트 모두에 같은 [구조] 절이 실린다")
        fun `1차와 보정이 같은 구조 절을 쓴다`() {
            val provider = FakeLlmProvider(listOf(reply(draftWithIssue), reply(cleanText)))
            val tableSource = "구분\n금액"
            val structure = SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL))
            // fixedIds 는 항상 같은 값을 내므로(생성자 상단), 여기서 직접 렌더링한 절이
            // 유스케이스가 실제로 프롬프트에 실은 절과 글자 단위로 같아야 한다.
            val expectedSection =
                renderStructureSection(structure, splitUnits(tableSource), fixedIds, StructureHintOptions().maxRuns)!!

            useCase(provider).convert(tableSource, structure = structure)

            assertThat(provider.calls).hasSize(2)
            assertThat(provider.calls[0].prompt.user).contains(expectedSection)
            assertThat(provider.calls[1].prompt.user).contains(expectedSection)
        }

        @Test
        @DisplayName("구조의 단위 수가 원문 줄 수와 다르면 전부 BODY 로 접혀 구조 절이 없다")
        fun `크기가 어긋나면 전부 BODY 로 접힌다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))
            // source 는 한 줄인데 구조는 두 칸짜리다 — 불변식 위반.
            val mismatched = SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL))

            useCase(provider).convert(source, structure = mismatched)

            assertThat(provider.calls[0].prompt.user).doesNotContain("[구조]")
        }
    }

    @Nested
    @DisplayName("결과 toString 안전성")
    inner class ResultToStringSafety {
        @Test
        @DisplayName("결과 문자열 표현에 본문이 실리지 않는다")
        fun `toString 이 본문을 흘리지 않는다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            val rendered = useCase(provider).convert(source).toString()

            assertThat(rendered).doesNotContain(cleanText)
            assertThat(rendered).contains("자")
        }
    }

    @Nested
    @DisplayName("LLM 호출 원장 (U1)")
    inner class LlmCallLedgerRecords {
        @Test
        @DisplayName("깨끗한 1차 결과는 CONVERT 행 하나를 남기고 charCount는 프롬프트 입력 길이다")
        fun `1차만 통과하면 CONVERT 한 행이다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText)))

            val result = useCase(provider).convert(source)

            assertThat(result.usage.calls).hasSize(1)
            val call = result.usage.calls.single()
            assertThat(call.purpose).isEqualTo(LlmCallPurpose.CONVERT)
            assertThat(call.provider).isEqualTo(provider.name)
            assertThat(call.charCount).isEqualTo(source.length)
        }

        @Test
        @DisplayName("보정까지 가면 CONVERT·REPAIR 두 행이 남는다")
        fun `보정까지 가면 두 행이다`() {
            val provider = FakeLlmProvider(List(10) { reply(draftWithIssue) })

            val result = useCase(provider).convert(source)

            assertThat(result.usage.calls.map { it.purpose })
                .containsExactly(LlmCallPurpose.CONVERT, LlmCallPurpose.REPAIR)
        }

        @Test
        @DisplayName("호출 자체가 실패하면 원장에 아무것도 남지 않는다")
        fun `provider 예외는 0행이다`() {
            val provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패"))))

            val result = useCase(provider).convert(source)

            assertThat(result.usage.calls).isEmpty()
        }

        @Test
        @DisplayName("단가가 설정되지 않으면 비용·단가 스냅샷이 모두 null이다 — 0으로 섞이지 않는다")
        fun `단가 미설정은 스냅샷도 null이다`() {
            val provider = FakeLlmProvider(listOf(reply(cleanText, inputTokens = 10, outputTokens = 5)))

            val result = useCase(provider).convert(source)

            val call = result.usage.calls.single()
            assertThat(call.estimatedCostUsd).isNull()
            assertThat(call.pricingInputUsdPerMtok).isNull()
            assertThat(call.pricingOutputUsdPerMtok).isNull()
            assertThat(call.latencyMs).isNull()
        }

        @Test
        @DisplayName("보정까지 두 번 부르면 각 행의 calledAt은 호출 시각이라 서로 다르고 시간순으로 증가한다")
        fun `두 호출의 calledAt은 서로 다르고 증가한다`() {
            val first = Instant.parse("2026-09-07T00:00:00Z")
            val second = Instant.parse("2026-09-07T00:00:05Z")
            val clock = SteppingClock(listOf(first, second))
            val provider = FakeLlmProvider(List(10) { reply(draftWithIssue) })
            val useCase = ConvertDocumentUseCase(provider, fixedIds, clock = clock)

            val result = useCase.convert(source)

            assertThat(result.usage.calls.map { it.calledAt }).containsExactly(first, second)
        }
    }
}

/**
 * 호출마다 미리 정해 둔 시각을 순서대로 내주는 시계 — **호출 시각과 저장 시각을 가르는 것**이
 * 그 테스트의 요점이라 `Clock.fixed` 로는 재지 못한다.
 */
private class SteppingClock(times: List<Instant>) : Clock() {
    private val queue = ArrayDeque(times)

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = queue.removeFirst()
}

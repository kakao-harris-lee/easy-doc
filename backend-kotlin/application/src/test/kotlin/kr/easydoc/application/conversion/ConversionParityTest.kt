package kr.easydoc.application.conversion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.parity.ParityActual
import kr.easydoc.core.parity.ParityCase
import kr.easydoc.core.parity.ParityFixtureCase
import kr.easydoc.core.parity.ParityFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `repair-adoption` 도메인 parity 산출물 생산자. */
class ConversionParityTest {
    private companion object {
        const val DOMAIN = "repair-adoption"

        /** 프롬프트 구분자 id 를 고정한다. 산출물에 실리지는 않지만 실행을 결정적으로 만든다. */
        val FIXED_IDS = DocumentIdGenerator { "0123456789ab" }

        /** 레거시 시나리오(대본 없음)가 쓰는 문서. 명세 §3.1 이 "하네스가 지어" 쓰라고 한 것. */
        const val LEGACY_SOURCE = "금일 서류를 제출하십시오."

        /** 규칙 위반이 남아 있는 변환 결과 — '금일'이 어려운 말 사전에 있다. */
        const val LEGACY_DRAFT_WITH_ISSUE = "금일 서류를 내세요."

        /** 규칙 위반이 없는 변환 결과. */
        const val LEGACY_CLEAN = "오늘 서류를 내세요."
    }

    @Test
    @Tag("parity")
    @DisplayName("repair-adoption fixture 전건을 돌려 parity/actual 에 산출물을 쓴다")
    fun `산출물을 만든다`() {
        check(checkStyle(LEGACY_CLEAN).issues.isEmpty()) {
            "레거시 시나리오의 '깨끗한 결과'($LEGACY_CLEAN)에 규칙 위반이 있다 — 호출 1회 케이스를 만들 수 없다."
        }
        check(checkStyle(LEGACY_DRAFT_WITH_ISSUE).issues.isNotEmpty()) {
            "레거시 시나리오의 '위반 있는 결과'($LEGACY_DRAFT_WITH_ISSUE)에 위반이 없다 — 보정 경로를 탈 수 없다."
        }

        val cases = ParityFixtures.cases(DOMAIN)
        val produced = cases.map { ParityCase(id = it.id, actual = runCase(it)) }

        val written = ParityActual.write(DOMAIN, "$DOMAIN.json", produced)

        assertThat(produced).hasSameSizeAs(cases)
        assertThat(written.fileName.toString()).isEqualTo("$DOMAIN.json")
    }

    /** 케이스 입력의 모양으로 경로를 가른다 (명세 §3.1). */
    private fun runCase(case: ParityFixtureCase): JsonElement {
        val input = case.input
        return when {
            input.containsKey("candidate") -> policyActual(input)
            input.containsKey("provider_script") -> scenarioActual(input)
            else -> legacyScenarioActual(input, case.id)
        }
    }

    /** 순수 판정 — 실행 없이 정책 함수만 부른다. */
    private fun policyActual(input: JsonObject): JsonElement {
        val decision =
            decideRepairAdoption(
                original = input.getValue("original").jsonPrimitive.content,
                candidate = input.getValue("candidate").jsonPrimitive.content,
                placeholders =
                    input["placeholders"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        return JsonObject(
            mapOf(
                "accepted" to JsonPrimitive(decision.accepted),
                "original_issue_count" to JsonPrimitive(decision.originalIssueCount),
                "candidate_issue_count" to JsonPrimitive(decision.candidateIssueCount),
            ),
        )
    }

    /** 대본이 있는 시나리오. */
    private fun scenarioActual(input: JsonObject): JsonElement {
        val turns = input.getValue("provider_script").jsonArray.map(::scriptedTurn)
        val provider =
            FakeLlmProvider(
                turns = turns,
                transportAttemptsPerCall = input["transport_attempts_per_call"]?.jsonPrimitive?.int ?: 1,
            )
        val source = input.getValue("source_text").jsonPrimitive.content
        return conversionActual(ConvertDocumentUseCase(provider, FIXED_IDS).convert(source), provider)
    }

    /** 대본이 없는 레거시 시나리오 2건(`repair-call-budget-clean`·`repair-call-budget-violations`). */
    private fun legacyScenarioActual(
        input: JsonObject,
        caseId: String,
    ): JsonElement {
        val scenario = input["scenario"]?.jsonPrimitive?.content
        val turns =
            when (scenario) {
                "no-style-violations" -> listOf(reply(LEGACY_CLEAN))
                "style-violations-detected" -> listOf(reply(LEGACY_DRAFT_WITH_ISSUE), reply(LEGACY_CLEAN))
                else -> error("알 수 없는 레거시 시나리오 '$scenario' (케이스 $caseId) — fixture 형식이 바뀌었는지 확인하라")
            }
        val provider = FakeLlmProvider(turns)
        return conversionActual(ConvertDocumentUseCase(provider, FIXED_IDS).convert(LEGACY_SOURCE), provider)
    }

    /** 대본 한 줄을 fake 의 한 차례로 옮긴다 (명세 §3.2). */
    private fun scriptedTurn(entry: JsonElement): FakeLlmTurn {
        val turn = entry.jsonObject

        turn["error"]?.let { return FakeLlmTurn.Fail(LlmProviderException("대본이 지정한 호출 실패")) }
        return reply(
            text = turn.getValue("text").jsonPrimitive.content,
            truncated = turn["truncated"]?.jsonPrimitive?.boolean ?: false,
            inputTokens = turn["input_tokens"]?.jsonPrimitive?.int ?: 0,
            outputTokens = turn["output_tokens"]?.jsonPrimitive?.int ?: 0,
        )
    }

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

    /** 산출물 필드 (명세 §3.3). */
    private fun conversionActual(
        result: ConversionResult,
        provider: FakeLlmProvider,
    ): JsonElement {
        val body =
            mutableMapOf<String, JsonElement>(
                "llm_calls" to JsonPrimitive(result.usage.llmCalls),
                "transport_attempts" to JsonPrimitive(provider.transportAttempts),
                "input_tokens" to JsonPrimitive(result.usage.inputTokens),
                "output_tokens" to JsonPrimitive(result.usage.outputTokens),
            )

        when (result) {
            is ConversionResult.Converted -> {
                body["outcome"] = JsonPrimitive("ok")
                body["failure_kind"] = JsonNull
                body["repaired"] = JsonPrimitive(result.repaired)
                body["easy_text"] = JsonPrimitive(result.easyText.value)
                body["missing_placeholders"] = JsonArray(result.missingPlaceholders.map(::JsonPrimitive))
            }

            is ConversionResult.Failed -> {
                body["outcome"] = JsonPrimitive("error")
                body["failure_kind"] = JsonPrimitive(wireName(result.kind))
                body["repaired"] = JsonPrimitive(false)
                body["easy_text"] = JsonNull
                body["missing_placeholders"] = JsonArray(emptyList())
            }
        }
        return JsonObject(body)
    }

    /**
     * fixture 가 쓰는 요구 수준 이름. 계약의 `failure_code` 가 아니다
     * (`ConversionFailureKind` KDoc · 명세 §6 갈림 후보 ②).
     */
    private fun wireName(kind: ConversionFailureKind): String =
        when (kind) {
            ConversionFailureKind.TRUNCATED -> "truncated"
            ConversionFailureKind.EMPTY_RESULT -> "empty_result"
            ConversionFailureKind.PROVIDER_ERROR -> "provider_error"
        }
}

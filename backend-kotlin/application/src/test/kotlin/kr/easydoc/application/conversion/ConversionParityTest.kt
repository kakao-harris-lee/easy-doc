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

/**
 * `repair-adoption` 도메인 parity 산출물 생산자.
 *
 * 하네스 계약의 정본은 `02_parity-verifier_conversion-spec.md` **§3** 이다 — 입력 3형태,
 * `provider_script` 어휘, 산출물 필드가 거기 적혀 있다. 이름은 `repair-adoption` 이지만
 * 범위는 **변환 오케스트레이션 전체**다(같은 문서 §4.3).
 *
 * ## 값을 판정하지 않는다
 *
 * fixture 의 `assert`·`reference` 를 읽지 않는다([ParityFixtures] 가 애초에 주지 않는다).
 * 판정은 `compare_parity.py` 의 몫이고, 여기서 하는 일은 시나리오를 **실제로 돌려** 결과를
 * 적는 것뿐이다.
 *
 * ## fake provider 를 엄격하게 만든다
 *
 * 대본이 소진된 뒤 호출하면 `FakeLlmProvider` 가 던진다. 그것이 **호출 상한의 1차
 * 방어선**이다 — 관대한 대역을 쓰면 3회째 호출이 값으로만 드러나고, 하네스가 죽어서
 * 알려 주는 경로가 사라진다(명세 §5.2 의 `repair-loop` 두 변형 비교).
 */
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
        // 레거시 시나리오가 기대는 전제를 먼저 확인한다. 사전이 바뀌어 이 전제가 깨지면
        // `repair-call-budget-clean` 이 "구현이 2회 부른다"처럼 보이는데 실제 원인은 여기다.
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

    /**
     * 케이스 입력의 모양으로 경로를 가른다 (명세 §3.1).
     *
     * | 모양 | 판별 | 하는 일 |
     * |---|---|---|
     * | 정책 | `original`·`candidate`·`placeholders` | 채택 판정 함수만 부른다. provider 불필요 |
     * | 대본 시나리오 | `scenario` + `source_text` + `provider_script` | 대본대로 응답하는 fake 로 변환 1건 |
     * | 레거시 시나리오 | `scenario` 만 | 문서를 하네스가 지어 변환 1건 |
     */
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
                // 비교기가 이 두 수를 **정책 판정의 입력으로 되먹여** accepted 를 다시 계산한다.
                // 건수 자체가 옳은지는 style 도메인의 질문이다.
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

    /**
     * 대본이 없는 레거시 시나리오 2건(`repair-call-budget-clean`·`repair-call-budget-violations`).
     *
     * 확장하면서 대본을 갖게 통일할 수도 있었지만 **기존 케이스 불변** 원칙을 지킨 결과다
     * (명세 §3.1). 여기서 지어내는 문서는 그 두 케이스가 재는 성질 — "위반이 없으면 1회,
     * 있어도 2회를 넘지 않는다" — 만 성립시키면 된다.
     */
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
        // `{"error": "provider"}` — 그 호출이 응답 없이 실패한다.
        turn["error"]?.let { return FakeLlmTurn.Fail(LlmProviderException("대본이 지정한 호출 실패")) }
        return reply(
            text = turn.getValue("text").jsonPrimitive.content,
            // provider 는 "출력 상한에서 잘렸다"는 **사실**만 보고한다. 실패로 볼지는 변환 정책이다.
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

    /**
     * 산출물 필드 (명세 §3.3).
     *
     * - `llm_calls` 는 **완성 요청 수**다. `transport_attempts` 는 어댑터가 실제로 전송한
     *   횟수이고, 이 계층은 그 수를 모른다 — fake 가 센 값을 하네스가 옮긴다. 두 수를 따로
     *   들고 있다는 것 자체가 CNV-01 의 "분리 계측"이다.
     * - `easy_text` 는 실패 시 **키를 빼지 않고 `null` 을 싣는다.** 키를 빼면 게이트가
     *   "경로가 산출물에 없다"로 막고, 그것은 계약의 null 규약과도 어긋난다.
     */
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
     * fixture 가 쓰는 **요구 수준 이름**. 계약의 `failure_code` 가 아니다
     * (`ConversionFailureKind` KDoc · 명세 §6 갈림 후보 ②).
     *
     * `name.lowercase()` 로 유도하지 않고 손으로 적는다 — enum 상수 이름을 바꾸는 리팩터링이
     * 산출물 값을 조용히 바꾸면, 게이트는 구현 변경을 요구 변경으로 착각한다.
     */
    private fun wireName(kind: ConversionFailureKind): String =
        when (kind) {
            ConversionFailureKind.TRUNCATED -> "truncated"
            ConversionFailureKind.EMPTY_RESULT -> "empty_result"
            ConversionFailureKind.PROVIDER_ERROR -> "provider_error"
        }
}

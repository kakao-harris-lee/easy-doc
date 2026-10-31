package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRunner
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionProviderCall
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionRunResult
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionAnalysis
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionParser
import kr.easydoc.core.illustration.suggestion.isNumericOnlySource
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.segment.MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS
import kr.easydoc.core.segment.splitUnits
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * 외부 호출 없이 **결정적인 제안 JSON** 을 만들어 실제 파서·검증기에 그대로 통과시키는 fake
 * (프로필 `illustration-suggestion-fake`, 명세 §6).
 *
 * 값을 반환하는 대신 JSON 을 거쳐 가는 이유는 이 fake 가 재는 것이 「응답을 받으면 무슨 일이
 * 일어나는가」이기 때문이다 — 파서·앵커 검증·사실 대조를 건너뛴 fake 는 e2e 에서 실제 경로와
 * 다른 것을 확인하게 된다.
 *
 * 정해진 절차 표현이 원문과 저장 본문에 각각 있을 때만 그 줄들에 근거를 둔다. 숫자-only·
 * 연락처-only·이모지-only처럼 절차 문맥이 없으면 '제안 없음'(빈 배열)을 낸다. 장면·대체텍스트에는
 * 숫자·날짜를 쓰지 않는다 — 원문 근거로 확인할 수 없는 사실이 있으면 검증기가 그 제안을 버리기
 * 때문이다(명세 §4).
 */
class FakeIllustrationSuggestionJobRunner(
    private val input: IllustrationSuggestionInputSource,
    private val clock: Clock = Clock.systemUTC(),
) : IllustrationSuggestionJobRunner {
    override fun prepare(job: StoredIllustrationSuggestionJob): IllustrationSuggestionProviderCall? {
        val loaded = input.load(job) ?: return null
        return IllustrationSuggestionProviderCall { call(loaded) }
    }

    private fun call(loaded: IllustrationSuggestionGenerationInput): IllustrationSuggestionRunResult {
        val sourceUnits = splitUnits(loaded.sourceText)
        val savedBodyUnits = splitUnits(loaded.savedBody)
        val analysis =
            IllustrationSuggestionParser.parseAndValidate(
                rawJson(sourceUnits, savedBodyUnits),
                sourceUnits,
                savedBodyUnits,
            )
        return when (analysis) {
            is IllustrationSuggestionAnalysis.Valid -> {
                IllustrationSuggestionRunResult.Valid(record(), analysis.suggestions)
            }

            IllustrationSuggestionAnalysis.InvalidStructure -> {
                IllustrationSuggestionRunResult.Invalid(record())
            }
        }
    }

    private fun rawJson(
        sourceUnits: List<String>,
        savedBodyUnits: List<String>,
    ): String {
        val root = JSON.createObjectNode()
        root.put("schema_version", SCHEMA_VERSION)
        val suggestions = root.putArray("suggestions")
        if (isNumericOnlySource(sourceUnits)) return JSON.writeValueAsString(root)

        val anchorIndex = sourceUnits.indexOfFirst { it.hasProcedureExpression() }
        val bodyIndex = savedBodyUnits.indexOfFirst { it.hasProcedureExpression() }
        if (anchorIndex >= 0 && bodyIndex >= 0) {
            val suggestion = suggestions.addObject()
            suggestion.put("purpose", "procedure")
            suggestion.put("reason", REASON)
            suggestion.putObject("body_range").put("start", bodyIndex).put("end", bodyIndex)
            val anchor = suggestion.putArray("source_anchors").addObject()
            anchor.putArray("source_unit_indexes").add(anchorIndex)
            val source = sourceUnits[anchorIndex]
            val quoteLength = minOf(source.codePointCount(0, source.length), MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS)
            anchor.put("quote", source.substring(0, source.offsetByCodePoints(0, quoteLength)))
            suggestion.putArray("scenes").add(SCENE)
            suggestion.putArray("preserved_facts")
            suggestion.put("alt_text_draft", ALT_TEXT)
        }
        return JSON.writeValueAsString(root)
    }

    private fun String.hasProcedureExpression(): Boolean = PROCEDURE_EXPRESSIONS.any { contains(it) }

    private fun record(): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ILLUSTRATION_SUGGESTION,
            provider = "fake",
            model = "fake-illustration-suggestion-r7",
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = 0,
            estimatedCostUsd = null,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = 0,
            calledAt = clock.instant(),
            outcome = LlmCallOutcome.COMPLETED,
        )

    private companion object {
        /** 값 이스케이프를 손으로 적지 않기 위한 writer. 원문 줄이 인용부호를 담을 수 있다. */
        val JSON = ObjectMapper()

        const val SCHEMA_VERSION = 1

        val PROCEDURE_EXPRESSIONS =
            listOf(
                "신청서를 제출합니다",
                "가까운 지사를 방문하세요",
                "서류를 내세요",
                "신청서를 냅니다",
            )

        // 숫자·날짜·금액을 쓰지 않는다 — 원문에서 확인할 수 없는 사실은 제안을 탈락시킨다.
        const val REASON = "행동 순서를 그림으로 보면 이해하기 쉬워집니다"
        const val SCENE = "안내문에서 해야 할 일을 차례대로 보여 주는 그림"
        const val ALT_TEXT = "해야 할 일을 차례대로 보여 주는 그림"
    }
}

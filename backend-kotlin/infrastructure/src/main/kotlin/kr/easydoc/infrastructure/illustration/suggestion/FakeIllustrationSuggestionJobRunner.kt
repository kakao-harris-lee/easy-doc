package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRunner
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionProviderCall
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionRunResult
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionAnalysis
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionParser
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
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
 * 제안은 **원문의 첫 비어 있지 않은 줄**에 근거를 단다. 장면·대체텍스트에는 숫자·날짜를 쓰지
 * 않는다 — 인용으로 확인할 수 없는 사실이 있으면 검증기가 그 제안을 버리기 때문이다(명세 §4).
 * 비어 있지 않은 줄이 하나도 없으면 근거를 달 수 없으므로 '제안 없음'(빈 배열)을 낸다.
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
                rawJson(sourceUnits),
                sourceUnits,
                savedBodyUnits,
            )
        return when (analysis) {
            is IllustrationSuggestionAnalysis.Valid -> {
                IllustrationSuggestionRunResult.Valid(record(), analysis.suggestions)
            }

            is IllustrationSuggestionAnalysis.AllDropped -> {
                IllustrationSuggestionRunResult.Invalid(record())
            }

            IllustrationSuggestionAnalysis.InvalidStructure -> {
                IllustrationSuggestionRunResult.Invalid(record())
            }
        }
    }

    private fun rawJson(sourceUnits: List<String>): String {
        val anchorIndex = sourceUnits.indexOfFirst { it.isNotBlank() }
        val root = JSON.createObjectNode()
        root.put("schema_version", SCHEMA_VERSION)
        val suggestions = root.putArray("suggestions")
        if (anchorIndex >= 0) {
            val suggestion = suggestions.addObject()
            suggestion.put("purpose", "procedure")
            suggestion.put("reason", REASON)
            suggestion.putObject("body_range").put("start", 0).put("end", 0)
            val anchor = suggestion.putArray("source_anchors").addObject()
            anchor.putArray("source_unit_indexes").add(anchorIndex)
            anchor.put("quote", sourceUnits[anchorIndex])
            suggestion.putArray("scenes").add(SCENE)
            suggestion.putArray("preserved_facts")
            suggestion.put("alt_text_draft", ALT_TEXT)
        }
        return JSON.writeValueAsString(root)
    }

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

        // 숫자·날짜·금액을 쓰지 않는다 — 인용으로 확인할 수 없는 사실은 제안을 탈락시킨다.
        const val REASON = "행동 순서를 그림으로 보면 이해하기 쉬워집니다"
        const val SCENE = "안내문에서 해야 할 일을 차례대로 보여 주는 그림"
        const val ALT_TEXT = "해야 할 일을 차례대로 보여 주는 그림"
    }
}

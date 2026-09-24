package kr.easydoc.core.illustration.suggestion

import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.segment.isSourceAnchorSupported
import java.util.UUID

/**
 * 구조 상한과 원문 대조만 본다(명세 §4). 그림이 정말 도움이 되는지, 제안이 적절한지는
 * 판단하지 않는다 — 그 품질은 ER-20 평가 전까지 주장하지 않는다.
 *
 * 두 층이 다르게 끝난다.
 * - **구조**(개수·길이·줄 범위): 하나라도 어기면 결과 **전체**가 무효다. 어떤 제안이
 *   틀렸는지를 LLM 출력의 나머지로 추정하지 않는다.
 * - **의미**(앵커가 원문과 맞는가, 근거에 없는 사실을 더했는가): **제안 단위**로 버리고
 *   개수만 센다.
 */
internal object IllustrationSuggestionValidator {
    /** 계약이 고정한 불변식 — 저장된 결과의 해석이 달라지므로 구성값이 아니다. */
    internal const val SCHEMA_VERSION = 1

    // 아래 상한은 전부 명세 §4 표의 값이다. 운영 중 바뀌는 정책이 아니라 계약과 함께
    // 고정되는 불변식이라 구성값이 아니라 코드 상수로 둔다. 길이는 코드 포인트로 센다.
    private const val MAX_SUGGESTIONS = 5
    private const val MAX_REASON_CODE_POINTS = 300
    private const val MIN_SCENES = 1
    private const val MAX_SCENES = 6
    private const val MAX_SCENE_CODE_POINTS = 200
    private const val MAX_PRESERVED_FACTS = 10
    private const val MAX_PRESERVED_FACT_CODE_POINTS = 200
    private const val MAX_ALT_TEXT_CODE_POINTS = 300
    private const val MIN_ANCHORS = 1
    private const val MAX_ANCHORS = 10

    /** 인용 길이는 명세가 「R2와 같은 규칙」이라 행동 안내 후보와 같은 값을 쓴다. */
    private const val MAX_ANCHOR_QUOTE_CODE_POINTS = 1_000

    fun validate(
        drafts: List<IllustrationSuggestionDraft>,
        sourceUnits: List<String>,
        savedBodyLineCount: Int,
        suggestionIds: IllustrationSuggestionIdGenerator,
    ): IllustrationSuggestionAnalysis {
        if (drafts.size > MAX_SUGGESTIONS || drafts.any { !isStructurallyValid(it, savedBodyLineCount) }) {
            return IllustrationSuggestionAnalysis.InvalidStructure
        }
        val kept = drafts.filter { isSupportedBySource(it, sourceUnits) }
        return if (drafts.isNotEmpty() && kept.isEmpty()) {
            IllustrationSuggestionAnalysis.AllDropped(drafts.size)
        } else {
            IllustrationSuggestionAnalysis.Valid(
                IllustrationSuggestionSet(
                    schemaVersion = SCHEMA_VERSION,
                    analysisVersion = LlmPrompt.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                    suggestions = kept.map { it.withId(suggestionIds.next()) },
                    droppedCount = drafts.size - kept.size,
                ),
            )
        }
    }

    private fun isStructurallyValid(
        draft: IllustrationSuggestionDraft,
        savedBodyLineCount: Int,
    ): Boolean =
        hasValidTexts(draft) &&
            hasValidSizes(draft) &&
            isWithinSavedBody(draft.bodyRange, savedBodyLineCount)

    private fun hasValidTexts(draft: IllustrationSuggestionDraft): Boolean =
        draft.reason.hasContentWithin(MAX_REASON_CODE_POINTS) &&
            draft.altTextDraft.hasContentWithin(MAX_ALT_TEXT_CODE_POINTS) &&
            draft.scenes.all { it.hasContentWithin(MAX_SCENE_CODE_POINTS) } &&
            draft.preservedFacts.all { it.hasContentWithin(MAX_PRESERVED_FACT_CODE_POINTS) }

    private fun hasValidSizes(draft: IllustrationSuggestionDraft): Boolean =
        draft.scenes.size in MIN_SCENES..MAX_SCENES &&
            draft.preservedFacts.size <= MAX_PRESERVED_FACTS &&
            draft.sourceAnchors.size in MIN_ANCHORS..MAX_ANCHORS &&
            draft.sourceAnchors.all(::hasValidAnchorShape)

    /** 행동 안내 후보와 같은 앵커 형식 규칙 — 빈 목록·중복·역순을 받지 않는다. */
    private fun hasValidAnchorShape(anchor: IllustrationSuggestionSourceAnchor): Boolean =
        anchor.quote.hasContentWithin(MAX_ANCHOR_QUOTE_CODE_POINTS) &&
            anchor.sourceUnitIndexes.isNotEmpty() &&
            anchor.sourceUnitIndexes == anchor.sourceUnitIndexes.distinct().sorted()

    /** 저장 본문 줄 수 안의 0 기반 포함 범위인가. 줄 수가 0이면 어떤 범위도 들어가지 못한다. */
    private fun isWithinSavedBody(
        bodyRange: IllustrationSuggestionBodyRange,
        savedBodyLineCount: Int,
    ): Boolean =
        bodyRange.start in 0 until savedBodyLineCount &&
            bodyRange.end in bodyRange.start until savedBodyLineCount

    /**
     * 앵커가 원문과 맞고, 그림으로 그릴 내용에 **근거에 없는 사실**이 없는가.
     *
     * 사실 대조 대상은 `scenes`·`preserved_facts`·`alt_text_draft` 다(명세 §4) — `reason` 은
     * 그림이 왜 도움이 되는지를 적는 설명이라 원문 사실의 재진술이 아니다.
     * 행동 안내 후보와 같은 [findMissingFacts] 를 쓰며, 숫자·날짜·금액처럼 그 함수가 잡는
     * 항목만 본다. 의미 오류 전부를 잡는다고 주장하지 않는다.
     */
    private fun isSupportedBySource(
        draft: IllustrationSuggestionDraft,
        sourceUnits: List<String>,
    ): Boolean {
        val anchored =
            draft.sourceAnchors.all { isSourceAnchorSupported(it.sourceUnitIndexes, it.quote, sourceUnits) }
        if (!anchored) return false
        val evidence = draft.sourceAnchors.joinToString("\n") { it.quote }
        val claimed = (draft.scenes + draft.preservedFacts + draft.altTextDraft).joinToString("\n")
        return findMissingFacts(claimed, evidence).isEmpty()
    }
}

private fun IllustrationSuggestionDraft.withId(suggestionId: UUID): IllustrationSuggestion =
    IllustrationSuggestion(
        suggestionId = suggestionId,
        purpose = purpose,
        reason = reason,
        bodyRange = bodyRange,
        sourceAnchors = sourceAnchors,
        scenes = scenes,
        preservedFacts = preservedFacts,
        altTextDraft = altTextDraft,
    )

/** 빈 값·공백뿐인 값은 내용이 없다고 본다. 길이는 코드 포인트로 센다(명세 §4). */
private fun String.hasContentWithin(maxCodePoints: Int): Boolean =
    isNotBlank() && codePointCount(0, length) <= maxCodePoints

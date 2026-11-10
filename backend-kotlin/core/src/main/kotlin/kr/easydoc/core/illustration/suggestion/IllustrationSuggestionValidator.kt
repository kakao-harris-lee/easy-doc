package kr.easydoc.core.illustration.suggestion

import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.segment.MAX_SOURCE_ANCHORS
import kr.easydoc.core.segment.isSourceAnchorShapeValid
import kr.easydoc.core.segment.isSourceAnchorSupported
import java.text.Normalizer
import java.util.UUID

/**
 * 구조 상한과 원문 대조만 본다(명세 §4). 그림이 정말 도움이 되는지, 제안이 적절한지는
 * 판단하지 않는다 — 그 품질은 ER-20 평가 전까지 주장하지 않는다.
 *
 * 두 층이 다르게 끝난다.
 * - **구조**(개수·길이·줄 범위): 하나라도 어기면 결과 **전체**가 무효다. 어떤 제안이
 *   틀렸는지를 LLM 출력의 나머지로 추정하지 않는다.
 * - **의미**(앵커가 원문과 맞는가, 근거에 없는 사실을 더했는가, 사용자에게 보이는 필드가
 *   한국어인가): **제안 단위**로 버리고 개수만 센다.
 *
 * 숫자-only 원문 가드는 구조 검증 뒤 모든 제안을 버리는 좁은 입력 형태 검사다.
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

    // 앵커 개수·인용 길이·형식은 명세가 「R2와 같은 규칙」이라 `core.segment` 의 공용
    // 상한·판정([MAX_SOURCE_ANCHORS]·[isSourceAnchorShapeValid])을 그대로 쓴다.

    fun validate(
        drafts: List<IllustrationSuggestionDraft>,
        sourceUnits: List<String>,
        savedBodyUnits: List<String>,
        suggestionIds: IllustrationSuggestionIdGenerator,
    ): IllustrationSuggestionAnalysis {
        if (drafts.size > MAX_SUGGESTIONS || drafts.any { !isStructurallyValid(it, savedBodyUnits.size) }) {
            return IllustrationSuggestionAnalysis.InvalidStructure
        }
        val kept =
            if (isNumericOnlySource(sourceUnits)) {
                emptyList()
            } else {
                drafts.filter { isKoreanUserVisibleText(it) && isSupportedBySource(it, sourceUnits) }
            }
        return IllustrationSuggestionAnalysis.Valid(
            IllustrationSuggestionSet(
                schemaVersion = SCHEMA_VERSION,
                analysisVersion = ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                suggestions = kept.map { it.withId(suggestionIds.next()) },
                droppedCount = drafts.size - kept.size,
            ),
        )
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
            draft.sourceAnchors.isNotEmpty() &&
            draft.sourceAnchors.size <= MAX_SOURCE_ANCHORS &&
            draft.sourceAnchors.all { isSourceAnchorShapeValid(it.sourceUnitIndexes, it.quote) }

    /** 저장 본문 줄 수 안의 0 기반 포함 범위인가. 줄 수가 0이면 어떤 범위도 들어가지 못한다. */
    private fun isWithinSavedBody(
        bodyRange: IllustrationSuggestionBodyRange,
        savedBodyLineCount: Int,
    ): Boolean =
        bodyRange.start in 0 until savedBodyLineCount &&
            bodyRange.end in bodyRange.start until savedBodyLineCount

    /**
     * 사용자에게 보이는 네 필드가 모두 한국어인가(계획 §12.4 ②).
     *
     * 1차 평가(2026-11-10)에서 한 제안의 `reason` 이 일본어로 돌아왔다. 제품 화면은 이 네
     * 필드를 그대로 보여 주므로 다른 언어면 읽을 수 없는 제안이다 — 구조 위반이 아니라
     * **제안 단위 탈락**이다(언어는 그 제안 하나의 문제다).
     *
     * 판정은 네 필드를 통째로가 아니라 **항목 하나하나**에 적용한다(`scenes`·`preserved_facts`
     * 는 배열이다). 항목이 아래 둘 중 하나에 걸리면 그 제안을 버린다.
     * - 일본어 가나가 섞여 있다([isKana]).
     * - **글자**([Character.isLetter])가 하나라도 있는데 한글 음절(U+AC00~U+D7A3)이 없다.
     *   로마자 낱말만 적은 항목(예: `LPG`)이 여기서 걸리고, 「LPG 차량 등록증을 준비하는
     *   모습」처럼 한국어 문장에 섞인 로마자는 남는다.
     *
     * **글자가 하나도 없는 항목은 통과시킨다** — 숫자·날짜·기호·공백뿐인 값(예: `2026. 3. 15.`·
     * `02-123-4567`·`50%`)이다. `preserved_facts` 는 원문의 숫자·날짜를 그대로 옮긴 값이 정상인
     * 자리라, 모든 항목에 한글을 요구하면 멀쩡한 제안이 통째로 사라진다. `reason` 과
     * `alt_text_draft` 는 문장이라 늘 글자를 포함하므로 여전히 한글이 있어야 한다.
     *
     * 한글 음절 판정 전에 각 항목을 NFC 로 정규화한다. 자모로 분해된(NFD) 한국어는 음절
     * 구간에 들어오지 않아 한글이 없는 글로 보인다.
     */
    private fun isKoreanUserVisibleText(draft: IllustrationSuggestionDraft): Boolean =
        (listOf(draft.reason, draft.altTextDraft) + draft.scenes + draft.preservedFacts).all(::isKorean)

    private fun isKorean(text: String): Boolean {
        val codePoints = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray()
        if (codePoints.any(::isKana)) return false
        return codePoints.none { Character.isLetter(it) } || codePoints.any { it in HANGUL_SYLLABLES }
    }

    private fun isKana(code: Int): Boolean = KANA_RANGES.any { code in it } && code !in KANA_PUNCTUATION

    /** 한글 음절 U+AC00~U+D7A3. 글자가 아니라 코드 포인트로 비교해 가독성을 지킨다. */
    private val HANGUL_SYLLABLES = 0xAC00..0xD7A3

    /**
     * 일본어 가나 구간 — 히라가나 U+3040~U+309F 와 가타카나 U+30A0~U+30FF 는 맞붙은 한 구간이고,
     * 가타카나 음성 확장(U+31F0~U+31FF)과 반각 가타카나(U+FF66~U+FF9D)가 따로 떨어져 있다.
     * 떨어진 두 구간도 함께 보는 이유는 「ｼﾝｾｲ 신청」처럼 한글과 섞여 오면 위 글자 규칙만으로는
     * 걸리지 않기 때문이다.
     */
    private val KANA_RANGES = listOf(0x3040..0x30FF, 0x31F0..0x31FF, 0xFF66..0xFF9D)

    /**
     * 가나 구간에 있지만 일본어로 보지 않는 `・`(U+30FB)·`ー`(U+30FC).
     *
     * 한국어 공문서가 가운뎃점(`·`)이나 줄표(`—`) 자리에 **잘못 가져다 쓰는** 글자다(예:
     * 「수집・이용」). 한국어에 이런 장음 부호가 있어서가 아니라, 오용이 흔해서 버리면 멀쩡한
     * 제안이 사라지기 때문에 빼 둔다.
     */
    private val KANA_PUNCTUATION = setOf(0x30FB, 0x30FC)

    /**
     * 앵커가 원문과 맞고, 그림으로 그릴 내용에 **근거에 없는 사실**이 없는가.
     *
     * 사실 대조 대상은 `scenes`·`preserved_facts`·`alt_text_draft` 다(명세 §4) — `reason` 은
     * 그림이 왜 도움이 되는지를 적는 설명이라 원문 사실의 재진술이 아니다.
     * 행동 안내 후보와 같은 [findMissingFacts] 를 쓰며, 숫자·날짜·금액처럼 그 함수가 잡는
     * 항목만 본다. 인용을 확인한 뒤 앵커의 원문 줄 전체를 근거로 쓰므로 같은 줄의 무관한
     * 숫자도 근거가 될 수 있다. 사실의 존재만 확인하며 의미 관련성이나 오류 전부를 판정하지 않는다.
     */
    private fun isSupportedBySource(
        draft: IllustrationSuggestionDraft,
        sourceUnits: List<String>,
    ): Boolean {
        val anchored =
            draft.sourceAnchors.all { isSourceAnchorSupported(it.sourceUnitIndexes, it.quote, sourceUnits) }
        if (!anchored) return false
        val evidence =
            draft.sourceAnchors
                .flatMap { it.sourceUnitIndexes }
                .distinct()
                .joinToString("\n") { sourceUnits[it] }
        val claimed = (draft.scenes + draft.preservedFacts + draft.altTextDraft).joinToString("\n")
        return findMissingFacts(claimed.replace(ORDINAL_BEON, " "), evidence).isEmpty()
    }

    private val ORDINAL_BEON = Regex("번(?=\\s*째)")
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

package kr.easydoc.core.illustration.suggestion

import java.util.UUID

// R7 ER-17 문맥 기반 그림 제안의 모델
// (`docs/plans/2026-09-24-r7-er17-suggestion-spec.md` §4 표).
//
// 이 타입들은 **검증을 통과한 결과**만 담는다 — 구조·상한·원문 대조는
// `IllustrationSuggestionValidator` 하나가 지고, 생성자에 `require` 를 두지 않는다
// (행동 안내 후보 `ActionGuideCandidate` 와 같은 판단: 검사 자리를 한 곳에 모은다).
//
// 모든 타입의 `toString()` 은 개수·번호만 낸다. 원문 인용·장면·대체텍스트는 사용자 문서에서
// 나온 본문이라 로그에 실리면 안 된다(명세 §4 마지막 줄, §7).

/**
 * 제안 분석 프롬프트의 버전. 결과와 함께 저장돼 「어느 프롬프트가 낸 제안인가」를 나중에
 * 잇는다(명세 §4 `analysis_version`). LLM 출력이 아니라 **서버가 찍는 계약 고정값**이다.
 *
 * `LlmPrompt.forIllustrationSuggestions` 의 시스템 프롬프트 문구나 출력 스키마를 바꾸면 이
 * 값을 함께 올린다. 상수가 프롬프트가 아니라 제안 패키지에 있는 이유는 이 값을 읽는 쪽이
 * 검증기·저장이기 때문이다 — 도메인 규칙이 프롬프트 생성기에 의존하게 두지 않는다.
 */
const val ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION: String = "r7-illustration-suggestion-1"

/** 제안 한 건이 그림으로 설명하려는 것. 계약 wire 값과 1:1 이다(명세 §4). */
enum class IllustrationSuggestionPurpose(val wireName: String) {
    /** 행동 순서·절차. */
    PROCEDURE("procedure"),

    /** 대상·경로 비교. */
    COMPARISON("comparison"),

    /** 구성·사용 관계. */
    RELATIONSHIP("relationship"),
    ;

    companion object {
        /** 모르는 값이면 `null` — 호출부(파서)가 결과 전체를 무효로 판정한다. */
        fun fromWireName(wireName: String): IllustrationSuggestionPurpose? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * 제안이 가리키는 저장 본문 줄 범위. 0 기반이고 [start]·[end] 양 끝을 포함하며 여러 문단을
 * 덮을 수 있다. 줄 번호만 들어 본문 글자를 담지 않으므로 기본 `toString()` 을 그대로 둔다.
 */
data class IllustrationSuggestionBodyRange(
    val start: Int,
    val end: Int,
)

/** 원문 근거 한 건 — 0 기반 원문 줄 번호와 그 줄에 실제로 있는 인용. */
data class IllustrationSuggestionSourceAnchor(
    val sourceUnitIndexes: List<Int>,
    val quote: String,
) {
    override fun toString(): String = "IllustrationSuggestionSourceAnchor(indexCount=${sourceUnitIndexes.size})"
}

/**
 * 검증을 통과해 남은 제안 하나.
 *
 * [suggestionId] 는 **서버가 부여한다** — LLM 출력에는 식별자 자리가 없다(명세 §4).
 */
data class IllustrationSuggestion(
    val suggestionId: UUID,
    val purpose: IllustrationSuggestionPurpose,
    val reason: String,
    val bodyRange: IllustrationSuggestionBodyRange,
    val sourceAnchors: List<IllustrationSuggestionSourceAnchor>,
    val scenes: List<String>,
    val preservedFacts: List<String>,
    val altTextDraft: String,
) {
    override fun toString(): String =
        "IllustrationSuggestion(suggestionId=$suggestionId, purpose=$purpose, " +
            "anchorCount=${sourceAnchors.size}, sceneCount=${scenes.size}, factCount=${preservedFacts.size})"
}

/**
 * 분석 한 번이 남기는 결과 전체. [suggestions] 가 비어 있으면 '제안 없음'이고, 버린 제안은
 * [droppedCount] 로 **개수만** 남긴다 — 내용은 저장하지 않는다(명세 §4).
 */
data class IllustrationSuggestionSet(
    val schemaVersion: Int,
    val analysisVersion: String,
    val suggestions: List<IllustrationSuggestion>,
    val droppedCount: Int,
) {
    override fun toString(): String =
        "IllustrationSuggestionSet(schemaVersion=$schemaVersion, analysisVersion=$analysisVersion, " +
            "suggestionCount=${suggestions.size}, droppedCount=$droppedCount)"
}

/**
 * 제안 식별자를 만든다. 순수 함수 안에서 직접 난수를 뽑지 않도록 이음매로 둔다 —
 * `DocumentIdGenerator` 와 같은 이유다(실행마다 달라지는 값은 단언할 수 없다).
 * 실제 구현은 [RandomIllustrationSuggestionIds] 하나뿐이고 테스트만 고정 생성기를 넘긴다.
 */
fun interface IllustrationSuggestionIdGenerator {
    fun next(): UUID
}

/**
 * `UUID.randomUUID()` 하나. 식별자는 추측 방어 대상이 아니라 결과 안에서 제안을 가리키는
 * 이름이지만, 이 구현 자체가 `SecureRandom` 을 쓴다.
 */
object RandomIllustrationSuggestionIds : IllustrationSuggestionIdGenerator {
    override fun next(): UUID = UUID.randomUUID()
}

/**
 * 분석 한 번의 판정. 호출부(ER-17-2 worker)가 이용량 소비·반환을 이 갈래로 정한다.
 *
 * 예외 대신 결과 타입을 쓰는 이유는 세 갈래가 모두 **정상 흐름**이기 때문이다 — LLM 이
 * 형식을 어기는 것은 이 기능에서 예외 상황이 아니라 예상된 결과 중 하나다.
 */
sealed interface IllustrationSuggestionAnalysis {
    /**
     * 구조가 유효하다. [suggestions] 의 목록이 비어 있으면 '제안 없음'이며 정상 결과로
     * 저장·소비한다(명세 §3·§4).
     */
    data class Valid(val suggestions: IllustrationSuggestionSet) : IllustrationSuggestionAnalysis {
        override fun toString(): String = "Valid($suggestions)"
    }

    /**
     * LLM 이 제안을 1건 이상 냈는데 전부 의미 검사에서 탈락했다 — 호출부는 `result_invalid`
     * 로 보고 이용량을 반환한다(명세 §4).
     */
    data class AllDropped(val droppedCount: Int) : IllustrationSuggestionAnalysis

    /** JSON 구조·상한 위반. 결과 전체가 `result_invalid` 다(명세 §4). */
    data object InvalidStructure : IllustrationSuggestionAnalysis
}

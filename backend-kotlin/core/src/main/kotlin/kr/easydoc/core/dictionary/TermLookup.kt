package kr.easydoc.core.dictionary

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.UserContent
import kr.easydoc.core.text.stripControlChars
import kr.easydoc.core.text.stripUnpairedSurrogates

/**
 * 검수 화면 조회 질의 — 담당자가 지목한 문자열 하나 (P0-5 §3.4 위치 계약).
 *
 * **좌표를 갖지 않는다.** `easy_unit_index`·`start`·`end` 같은 위치 정보는 편집기의
 * 클라이언트 전용 상태이고, 조회 wire 계약이 받는 것은 선택된 문자열뿐이다(계획 §3.4).
 *
 * 정제 후 비었거나 [MAX_LENGTH] 를 넘으면 [InvalidInputException] 으로 거절한다. `100` 은
 * 계획 §3.4 가 wire 계약 상한으로 정한 값이다 — 계약(조각 1)이 아직 없어 여기서는 core 도메인
 * 기본값으로 먼저 고정해 두고, 계약이 생기면 그 값을 그대로 옮긴다.
 *
 * ## `@UserContent` 를 붙이는 이유
 *
 * [text] 는 **사용자 문서에서 담당자가 지목해 잘라낸 조각**이라 [DictionaryMatch] 와 같은
 * 이유로 `SensitiveToStringReachTest` 의 민감 이름 토큰에 걸리지 않는다(`text` 라는 이름 자체는
 * 걸리지만, 이 타입은 `data class` 가 아니라 손으로 쓴 일반 class 라 R-10 축이 이름 자체는
 * 물론이고 `@UserContent` 로 넓힌 판정도 함께 본다 — 어느 쪽이든 [toString] 이 값을 가려야
 * 한다는 결론은 같다). 2026-09-05 심사에서 지적됐다.
 */
@UserContent
class TermQuery private constructor(val text: String) {
    /** [DictionaryMatch] 와 같은 사유로 [text] 를 가린다 — 길이는 진단에 남긴다. */
    override fun toString(): String = "TermQuery(text=$CONTENT_MASK, length=${text.length})"

    companion object {
        /** 계획 §3.4 가 적어 둔 wire 계약 상한(입력 제한 확장 노드) 후보값. 계약(조각 1)은 아직 없다. */
        const val MAX_LENGTH: Int = 100

        /** 정제 후 이어붙는 공백류(스페이스·탭·개행·복귀)를 한 칸으로 뭉친다. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /**
         * 원문 문자열을 정제해 질의를 만든다.
         *
         * 정제 순서는 [kr.easydoc.core.document.resolveTitle] 이 쓰는 것과 같은 두 함수
         * ([stripControlChars]·[stripUnpairedSurrogates])로 시작한다 — 이름 정제와 같은 문제(XML 이
         * 못 담는 제어문자, 인코딩이 못 담는 짝 없는 서로게이트)를 여기서 다시 만들지 않는다.
         * `stripControlChars` 는 탭·개행·복귀를 **남긴다**(문서 구조로 본다) — 그래서 그 다음
         * 단계로 남은 공백류를 전부 한 칸으로 뭉갠다. 담당자가 편집기에서 **여러 줄에 걸친
         * 선택**을 지목하면 그 사이 개행이 그대로 남아, 원래는 이어져 있던 한 낱말이 줄바꿈으로
         * 갈라진 두 낱말처럼 보여 사전 조회가 실패할 수 있다 — 뭉개면 그 선택이 한 줄짜리 조회처럼
         * 동작한다. 마지막으로 앞뒤 공백을 자른다.
         *
         * 정제 후 비면, 또는 [MAX_LENGTH] 를 넘으면 거절한다 — 이 예외는
         * [kr.easydoc.core.exceptions.InvalidInputException] 이라 나중에 HTTP 경계(조각 4)가
         * 그대로 422 로 옮길 수 있다.
         */
        fun of(raw: String): TermQuery {
            val stripped = stripUnpairedSurrogates(stripControlChars(raw))
            val sanitized = WHITESPACE_RUN.replace(stripped, " ").trim()
            if (sanitized.isEmpty()) {
                throw InvalidInputException("조회할 문자열이 비어 있다")
            }
            if (sanitized.length > MAX_LENGTH) {
                throw InvalidInputException("조회 문자열이 상한(${MAX_LENGTH}자)을 넘는다")
            }
            return TermQuery(sanitized)
        }
    }
}

/**
 * 조회 후보의 매칭 종류 (P0-5 §3.4).
 *
 * - [EXACT]: 매치 표면형이 표제어와 같고, 질의에 남는 것은 조사뿐이다.
 * - [INFLECTED]: 표면형이 표제어와 다르다([DictionaryMatch.isInflected]) — 활용형·이형태.
 * - [COMPOUND_PART]: 뜻의 관계를 확인한 복합어 안의 용어를 설명한다. 임의로 자른 부분
 *   문자열은 후보가 아니다. [TermCandidate.applicable] 은 항상 거짓이다.
 */
enum class TermMatchKind { EXACT, INFLECTED, COMPOUND_PART }

/**
 * 조회 후보 하나 — 팝업이 그대로 보여줄 사전 지침 (P0-5 §3.4 응답 계약).
 *
 * [DictionaryEntry] 를 그대로 노출하지 않는다 — 조회 응답의 공개 경계는 [matchKind]·
 * [applicable] 처럼 이 유스케이스에서만 의미가 있는 값을 더해 그 위치에서 결정한다.
 *
 * [applicable] 은 치환 버튼을 줄지의 단일 출처다: [strategy] 가 [ReplaceStrategy.SUBSTITUTE]
 * 이고 [matchKind] 가 [TermMatchKind.COMPOUND_PART] 가 아닐 때만 참이다. 복합어 부분 일치는
 * 원어 일부만 지우는 편집이 되어 문서를 훼손하므로, 매칭된 엔트리의 전략과 무관하게 항상
 * 거짓이다(계획 §3.1 "대체어 버튼은 주지 않는다").
 *
 * [entryId] 는 [DictionaryMatch.entryId] 를 그대로 옮긴 것이다 — `term` 만으로는 표제어를
 * 공유하는 엔트리(전체 2,179건 중 465건, 예: "급여")를 가려낼 수 없어 픽스처 실측
 * (`TermLookupFixtureTest`)이 "어느 엔트리가 이겼나"를 검증할 유일한 확실한 키다.
 */
data class TermCandidate(
    val term: String,
    val easyTerm: String,
    val strategy: ReplaceStrategy,
    val risk: RiskLevel,
    val definition: String?,
    val caution: String?,
    val tags: List<String>,
    val examples: List<DictionaryExample>,
    val matchKind: TermMatchKind,
    val applicable: Boolean,
    val entryId: Int,
)

/**
 * 선택된 문자열 하나에서 사전 후보를 산출한다 (P0-5 조각 2).
 *
 * 문서용 색인의 경계 검사와 조사 목록을 재사용하되 선택 범위 전체를 추가로 검사한다.
 * 조회에만 적용하는 복합어 정책은 생성용 RAG와 분리한다.
 */
object TermLookup {
    /**
     * 기존 손작성 조회 사례에서 뜻의 관계를 확인한 복합어만 허용한다.
     * 임의의 접두·접미 조각을 찾지 않는다. 항목을 늘릴 때는 전체 표현의 의미와
     * 선택 범위를 함께 검토해야 하며, 이 목록은 원문 생성용 RAG에는 쓰이지 않는다.
     */
    private val REVIEWED_COMPOUNDS =
        mapOf(
            "저소득가구" to "저소득",
            "고령운전자" to "고령",
            "무직저소득" to "저소득",
        )

    /**
     * 질의에 대한 후보 목록을 만든다. 매칭이 없으면 빈 목록이다(예외가 아니다).
     *
     * 1. 원래 경계 검사에 더해, 선택 범위가 매치 표면형과 조사로만 구성된 경우에만
     *    EXACT/INFLECTED로 반환한다. 문장 안에서 찾은 단어를 전체 선택의 대체어로 쓰지 않는다.
     * 2. 전체 일치가 없으면 [REVIEWED_COMPOUNDS]에 있는 표현만 설명 후보로 조회한다.
     *    나머지는 빈 목록이다. 단어를 잘라 문서 끝으로 보이게 만들던 경계 우회를 하지 않는다.
     */
    fun candidates(
        query: TermQuery,
        index: DictionaryIndex,
    ): List<TermCandidate> {
        val direct =
            index.findAll(query.text).filter { match ->
                match.start == 0 && index.coversSelection(query.text, match.surface)
            }
        return if (direct.isNotEmpty()) {
            direct.map { match ->
                toCandidate(match, if (match.isInflected) TermMatchKind.INFLECTED else TermMatchKind.EXACT)
            }
        } else {
            findReviewedCompound(query.text, index)
                ?.let { embedded -> listOf(toCandidate(embedded, TermMatchKind.COMPOUND_PART)) }
                ?: emptyList()
        }
    }

    /** 확인된 복합어 전체와 조사만 선택됐을 때, 그 안의 확인된 용어를 설명으로 돌려준다. */
    private fun findReviewedCompound(
        text: String,
        index: DictionaryIndex,
    ): DictionaryMatch? {
        val term = REVIEWED_COMPOUNDS.entries.firstOrNull { index.coversSelection(text, it.key) }?.value ?: return null
        return index.findAll(term).singleOrNull()?.takeIf { match ->
            match.start == 0 && match.end == term.length &&
                match.entry.risk != RiskLevel.HIGH && "needs_review" !in match.entry.tags
        }
    }

    private fun toCandidate(
        match: DictionaryMatch,
        kind: TermMatchKind,
    ): TermCandidate {
        val entry = match.entry
        return TermCandidate(
            term = entry.term,
            easyTerm = entry.easyTerm,
            strategy = entry.strategy,
            risk = entry.risk,
            definition = entry.definition,
            caution = entry.caution,
            tags = entry.tags,
            examples = entry.examples,
            matchKind = kind,
            applicable = kind != TermMatchKind.COMPOUND_PART && entry.strategy == ReplaceStrategy.SUBSTITUTE,
            entryId = match.entryId,
        )
    }
}

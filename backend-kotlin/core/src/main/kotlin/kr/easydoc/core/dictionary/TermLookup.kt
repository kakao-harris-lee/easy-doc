package kr.easydoc.core.dictionary

import kr.easydoc.core.exceptions.InvalidInputException

/**
 * 검수 화면 조회 질의 — 담당자가 지목한 문자열 하나 (P0-5 §3.4 위치 계약).
 *
 * **좌표를 갖지 않는다.** `easy_unit_index`·`start`·`end` 같은 위치 정보는 편집기의
 * 클라이언트 전용 상태이고, 조회 wire 계약이 받는 것은 선택된 문자열뿐이다(계획 §3.4).
 *
 * 제어문자를 제거하고 앞뒤 공백을 자른 뒤 비었거나 [MAX_LENGTH] 를 넘으면 [InvalidInputException]
 * 으로 거절한다. `100` 은 계획 §3.4 가 wire 계약 상한으로 정한 값이다 — 계약(조각 1)이 아직
 * 없어 여기서는 core 도메인 기본값으로 먼저 고정해 두고, 계약이 생기면 그 값을 그대로 옮긴다.
 */
class TermQuery private constructor(val text: String) {
    companion object {
        /** 계획 §3.4 가 적어 둔 wire 계약 상한(입력 제한 확장 노드) 후보값. 계약(조각 1)은 아직 없다. */
        const val MAX_LENGTH: Int = 100

        /**
         * 원문 문자열을 정제해 질의를 만든다.
         *
         * 제어문자만 있거나 정제 후 비면, 또는 [MAX_LENGTH] 를 넘으면 거절한다 — 이 예외는
         * [kr.easydoc.core.exceptions.InvalidInputException] 이라 나중에 HTTP 경계(조각 4)가
         * 그대로 422 로 옮길 수 있다.
         */
        fun of(raw: String): TermQuery {
            val sanitized = raw.filterNot { it.isISOControl() }.trim()
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
 * - [COMPOUND_PART]: 질의 전체가 아니라 그 일부(앞·뒤 부분 문자열)만 사전에 있다. 복합어
 *   안에 든 아는 말을 설명으로만 보여주는 자리라 [TermCandidate.applicable] 은 항상 거짓이다.
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
)

/**
 * 선택된 문자열 하나에서 사전 후보를 산출한다 (P0-5 조각 2).
 *
 * 기존 `DictionaryIndex`·`DictionaryEntry` 는 손대지 않는다 — 이 객체는 그 공개 표면
 * ([DictionaryIndex.findAll]) 만 쓰는 소비자다.
 */
object TermLookup {
    /**
     * 질의에 대한 후보 목록을 만든다. 매칭이 없으면 빈 목록이다(예외가 아니다).
     *
     * 1. 질의 전체에 [DictionaryIndex.findAll] 을 돌린다. 결과가 있으면 그 매칭들이 이미
     *    경계 규칙(어절 경계·조사 연쇄·로마자/숫자 경계)을 통과한 것이므로, 매칭마다
     *    [DictionaryMatch.isInflected] 로 [TermMatchKind.EXACT]/[TermMatchKind.INFLECTED] 만
     *    가르면 된다.
     * 2. 1차 결과가 없으면 — 복합어라 전체 일치가 없다는 뜻이다 — [findEmbeddedTerm] 으로
     *    질의의 앞·뒤 부분 문자열 중 사전에 있는 가장 긴 것을 찾는다. 그것도 없으면 빈 목록,
     *    즉 정직한 "사전에 없는 말"이다.
     */
    fun candidates(
        query: TermQuery,
        index: DictionaryIndex,
    ): List<TermCandidate> {
        val direct = index.findAll(query.text)
        return if (direct.isNotEmpty()) {
            direct.map { match ->
                toCandidate(match, if (match.isInflected) TermMatchKind.INFLECTED else TermMatchKind.EXACT)
            }
        } else {
            findEmbeddedTerm(query.text, index)
                ?.let { embedded -> listOf(toCandidate(embedded, TermMatchKind.COMPOUND_PART)) }
                ?: emptyList()
        }
    }

    /**
     * 질의 전체가 사전에 없을 때, 앞에서 줄이거나 뒤에서 줄인 부분 문자열 중 사전에 있는
     * 가장 긴 것을 찾는다(§3.1 "복합어 부분 일치").
     *
     * [DictionaryIndex.findAll] 을 그대로 재사용한다 — 새 매칭 엔진을 만들지 않는다. 부분
     * 문자열 자체를 독립된 텍스트로 넘기면, 그 문자열의 끝이 곧 원래 함수가 보는 "문서 끝"이라
     * 오른쪽 경계 검사([DictionaryIndex] 의 `boundaryOk`)가 항상 통과한다 — 복합어 안에서
     * 조사가 아닌 다른 낱말이 뒤따른다는 이유로 매칭이 거부되는 것을 피할 수 있는 이유가
     * 그것이다. 접두·접미 양쪽에서 각각 가장 긴 것부터 시도해 처음 찾은 것을 후보로 모으고,
     * 그중 매칭 길이가 가장 긴 것을 채택한다 — 가운데에 파묻힌 낱말은 다루지 않는다(§3.1의
     * 요구는 접두·접미 복합어이지 임의 부분 문자열 스캐너가 아니다).
     */
    private fun findEmbeddedTerm(
        text: String,
        index: DictionaryIndex,
    ): DictionaryMatch? {
        val prefixes = (text.length - 1 downTo 1).map { text.substring(0, it) }
        val suffixes = (text.length - 1 downTo 1).map { text.substring(text.length - it) }
        return (prefixes + suffixes)
            .asSequence()
            .mapNotNull { candidate -> index.findAll(candidate).firstOrNull() }
            .maxByOrNull { it.end - it.start }
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
        )
    }
}

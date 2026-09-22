package kr.easydoc.core.dictionary

import kr.easydoc.core.easyread.SourceAnchor
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.UserContent

/**
 * 추가 설명의 근거 종류 (R6, wire `definition_source`).
 *
 * v1 은 검수된 사전 정의 하나뿐이다 - 근거 없는 설명은 만들지 않는다(계약
 * `ExplanationDefinitionSource`).
 */
enum class ExplanationDefinitionSource(val wire: String) {
    DICTIONARY_REVIEWED("dictionary_reviewed"),
    ;

    companion object {
        fun ofWire(wire: String): ExplanationDefinitionSource =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("알 수 없는 definition_source: $wire")
    }
}

/**
 * 본문에서 검수된 사전 정의를 가진 용어가 나타난 자리 하나.
 *
 * [ReviewedDefinitionSource] 가 본문 전체를 훑어 등장할 때마다 하나씩 만든다 - 같은
 * 용어가 여러 번 나오면 여러 건이 생기고, [deriveExplanations] 가 용어별로 하나의
 * [Explanation] 으로 모은다. [surface] 는 본문에서 실제로 매치된 부분 문자열이라
 * [DictionaryMatch.surface] 처럼 가린다. [@UserContent] 는 마스킹 대상을 필드 이름이
 * 아니라 타입 전체로 넓히므로, 사전 어휘인 [term] 과 검수된 정의문 [explanation] 도
 * 값 대신 길이만 남긴다 - 게이트는 어느 텍스트 필드가 문서에서 온 것인지 구분하지 않는다.
 */
@UserContent
data class ReviewedDefinition(
    val term: String,
    val surface: String,
    val definitionSource: ExplanationDefinitionSource,
    val explanation: String,
) {
    override fun toString(): String =
        "ReviewedDefinition(term=${term.length}자, surface=$CONTENT_MASK, " +
            "definitionSource=${definitionSource.wire}, explanation=${explanation.length}자)"
}

/**
 * 조회 응답의 설명 한 건 (계약 `Explanation`).
 *
 * [sourceAnchors] 는 [SourceAnchor] 를 그대로 재사용한다 - 그 [SourceAnchor.toString] 이
 * 이미 본문 발췌([SourceAnchor.quote])를 가리므로 중첩된 발췌는 따로 가릴 것이 없다.
 * 다만 [term]·[explanation] 은 이 타입이 직접 든 텍스트라 [ReviewedDefinition] 과 같은
 * 규약으로 값 대신 길이만 남긴다. [@UserContent] 는 이름 휴리스틱이 놓치는 이 자리를
 * 게이트의 탐지 범위 안에 두기 위한 것이다 - 없으면 「통과」가 아니라 **미검사**가 된다.
 */
@UserContent
data class Explanation(
    val term: String,
    val definitionSource: ExplanationDefinitionSource,
    val explanation: String,
    val sourceAnchors: List<SourceAnchor>,
) {
    override fun toString(): String =
        "Explanation(term=${term.length}자, definitionSource=${definitionSource.wire}, " +
            "explanation=${explanation.length}자, sourceAnchors=${sourceAnchors.size})"
}

/**
 * 본문에서 찾은 정의 자리들을 조회 응답용 설명 목록으로 모은다.
 *
 * 같은 용어는 본문에 **처음 나온 자리**의 정의만 쓰고 한 건으로 합친다(계약: 용어마다
 * 하나). [definitions] 가 이미 본문 등장 순서로 들어온다는 전제 위에서, 그 순서를
 * 그대로 결과 순서로 쓴다.
 *
 * 근거는 [analyzeReviewSupport] 의 관례를 그대로 따른다 - 매치된 [ReviewedDefinition.surface]
 * (활용형이 섞여 있으면 그 전부)를 담은 [sourceUnits] 색인을 부분 문자열 검사로 찾고,
 * 하나도 못 찾으면 그 용어는 빈 배열로 둔다 - 없는 위치를 추정하지 않는다(R6 계약).
 */
fun deriveExplanations(
    definitions: List<ReviewedDefinition>,
    sourceUnits: List<String>,
): List<Explanation> {
    val occurrencesByTerm = LinkedHashMap<String, MutableList<ReviewedDefinition>>()
    definitions.forEach { definition ->
        occurrencesByTerm.getOrPut(definition.term) { mutableListOf() }.add(definition)
    }

    return occurrencesByTerm.values.map { occurrences ->
        val first = occurrences.first()
        val surfaces = occurrences.map { it.surface }.distinct()
        val sourceIndexes =
            sourceUnits.mapIndexedNotNull { index, unit ->
                index.takeIf { surfaces.any { surface -> surface in unit } }
            }
        Explanation(
            term = first.term,
            definitionSource = first.definitionSource,
            explanation = first.explanation,
            sourceAnchors =
                if (sourceIndexes.isEmpty()) emptyList() else listOf(SourceAnchor(sourceIndexes, first.surface)),
        )
    }
}

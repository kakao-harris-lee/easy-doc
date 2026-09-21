package kr.easydoc.infrastructure.document

import kr.easydoc.application.dictionary.ReviewedDefinitionSource
import kr.easydoc.core.dictionary.DefinitionReviewStatus
import kr.easydoc.core.dictionary.ExplanationDefinitionSource
import kr.easydoc.core.dictionary.ReviewedDefinition
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.infrastructure.dictionary.DictionaryIndexHolder

/**
 * R6 `ReviewedDefinitionSource` 실물 - 이미 있는 사전 색인에서 **검수를 마친** 정의만 골라낸다.
 *
 * ## 왜 `DictionaryIndexHolder` 를 들고, `DictionaryIndex` 를 직접 들지 않는가
 *
 * `DictionaryIndexHolder` 의 KDoc이 정한 계약이 그대로 이 자리에 적용된다 - 색인은 1.5MB 라
 * 조립 시점(`preInstantiateSingletons`)에 즉시 읽으면 안 되고, 실제로 [find] 가 불릴 때만
 * `by lazy` 로 미뤄 읽어야 한다. 이 어댑터가 `DictionaryIndex` 를 생성자에서 직접 받으면 그
 * 즉시 읽기를 강제하게 되므로, 홀더를 받아 매 호출마다 [DictionaryIndexHolder.indexOrNull] 로
 * 늦게 묻는다.
 *
 * ## 왜 사전이 꺼져 있으면 빈 목록이 아니라 [ConfigurationException] 인가
 *
 * 빈 목록은 "이 본문에 검수된 정의를 가진 용어가 없다"는 정상 상태([ReviewedDefinitionSource.find]
 * 의 계약)다. 사전 자체가 꺼져 있는 것은 전혀 다른 상태 - 이 어댑터가 근거를 찾을 수단이 아예
 * 없다는 구성 오류다. 둘을 같은 빈 목록으로 합치면 "R6 를 켰는데 사전을 안 켜서 설명이 항상
 * 비어 나온다"는 배포 실수가 조용히 정상 응답으로 위장한다. 그래서 여기서는 즉시 실패한다.
 *
 * ## 왜 [ReviewedDefinition.surface] 를 표제어와 따로 들고 다니는가
 *
 * [kr.easydoc.core.dictionary.DictionaryMatch.surface] 는 본문에 실제로 쓰인 활용형·표기이고
 * `entry.term` 은 표제어 원형이다. 둘이 다를 수 있으므로([DictionaryMatch] KDoc의 `isInflected`)
 * [ReviewedDefinition] 도 두 값을 각각 보존한다 - `deriveExplanations` 가 본문에서 근거 위치를
 * 찾을 때는 실제로 쓰인 표기([surface])로 부분 문자열 검사를 해야 하고, 응답에 노출하는 용어
 * 이름은 표제어([term])여야 하기 때문이다.
 */
class DictionaryReviewedDefinitionSource(private val holder: DictionaryIndexHolder) : ReviewedDefinitionSource {
    override fun find(body: String): List<ReviewedDefinition> {
        val index = holder.indexOrNull() ?: throw ConfigurationException(DICTIONARY_DISABLED_MESSAGE)
        return index
            .findAll(body)
            .filter { match ->
                match.entry.definitionReviewStatus == DefinitionReviewStatus.REVIEWED &&
                    !match.entry.definition.isNullOrBlank()
            }.map { match ->
                ReviewedDefinition(
                    term = match.entry.term,
                    surface = match.surface,
                    definitionSource = ExplanationDefinitionSource.DICTIONARY_REVIEWED,
                    explanation = requireNotNull(match.entry.definition),
                )
            }
    }

    private companion object {
        const val DICTIONARY_DISABLED_MESSAGE =
            "R6 추가 설명이 근거를 찾으려면 사전 색인이 필요한데 꺼져 있습니다 " +
                "(easydoc.dictionary.lookup.enabled 와 easydoc.dictionary.enabled 를 확인한다)"
    }
}

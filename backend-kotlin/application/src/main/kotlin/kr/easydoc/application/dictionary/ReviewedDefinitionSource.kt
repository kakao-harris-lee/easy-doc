package kr.easydoc.application.dictionary

import kr.easydoc.core.dictionary.ReviewedDefinition

/**
 * 본문에서 **검수된 사전 정의**를 찾는 포트 (R6).
 *
 * v1 실물은 하나뿐이다 — 사전 색인에 이미 올라간, 요청보다 **먼저 검수된** 정의. 이 자리에서
 * 모델을 부르지 않는다: R6 의 설명은 요청 시점에 새로 생성하는 것이 아니라 그 전에 검수를 마친
 * 정의에서 그대로 파생하므로, 근거가 있고(정의가 검수됐다는 사실 자체가 근거다) 추가 지연이나
 * 비용이 붙지 않는다. `fun interface` 로 둔 것은 [SegmentMapDerivation] 과 같은 이유다 — 실물이
 * 하나뿐이어도 테스트가 새 구현이 아니라 데코레이터로 호출 인자를 기록할 수 있게 포트로 남긴다.
 *
 * 호출부는 매번 **현재** 본문을 넘긴다. 본문을 고치면 다음 호출이 그 본문으로 다시 찾을 뿐이라,
 * 저장된 설명 행이 따로 없고 그래서 낡을 수도 없다.
 */
fun interface ReviewedDefinitionSource {
    /**
     * [body] 에서 검수된 정의를 가진 용어가 나타난 자리를 전부 찾는다.
     *
     * 빈 목록은 정상이고 오류가 아니다 — 이 본문에 검수된 정의를 가진 용어가 하나도 없다는
     * 뜻일 뿐이다.
     */
    fun find(body: String): List<ReviewedDefinition>
}

package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.dictionary.TermCandidateSource
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.exceptions.InvalidInputException

/**
 * `easydoc.dictionary.lookup.enabled=false` 일 때의 null object (P0-5 조각 4).
 *
 * [DictionaryConfiguration.dictionaryIndex] 가 꺼져 있으면 `null` 을 돌려주던 것을,
 * 소비자 쪽에서 이 타입으로 흡수한다 — S1 KDoc(`DictionaryConfiguration` "2026-09-05
 * 리뷰" 문단)이 예고한 정리다. 빈 목록을 돌려주지 **않는다** — "사전에 없는 말"(정상
 * 200, 빈 배열)과 "조회 기능 자체가 꺼졌다"(422)는 다른 사건이고, 뒤섞으면 운영자가
 * 스위치를 끈 것을 사용자가 "사전이 텅 비었다"로 오해한다.
 */
object NoTermCandidateSource : TermCandidateSource {
    override fun candidatesFor(query: TermQuery): List<TermCandidate> =
        throw InvalidInputException(LOOKUP_DISABLED_MESSAGE)

    /** 계약 `POST /dictionary/lookup` 422 문자열 예시와 같은 값. */
    const val LOOKUP_DISABLED_MESSAGE: String = "사전 조회가 꺼져 있습니다"
}

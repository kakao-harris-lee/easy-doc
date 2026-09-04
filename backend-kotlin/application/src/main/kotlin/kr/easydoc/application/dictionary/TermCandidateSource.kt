package kr.easydoc.application.dictionary

import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermQuery

/**
 * 정제된 조회 질의에서 사전 후보를 구해 오는 포트 (P0-5 조각 3).
 *
 * `DictionaryContextSource`(변환 경로가 쓰는 포트)와 같은 자리다 - 유스케이스는 사전 색인이
 * 어떻게 적재되는지 모르고, infrastructure 어댑터가 그것을 구현한다.
 */
fun interface TermCandidateSource {
    fun candidatesFor(query: TermQuery): List<TermCandidate>
}

package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.dictionary.TermCandidateSource
import kr.easydoc.core.dictionary.DictionaryIndex
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermLookup
import kr.easydoc.core.dictionary.TermQuery

/** 적재된 색인으로 조회 후보를 만드는 어댑터 (P0-5 조각 3). [TermLookup] 이 실제 매칭을 한다. */
class IndexedTermCandidateSource(private val index: DictionaryIndex) : TermCandidateSource {
    override fun candidatesFor(query: TermQuery): List<TermCandidate> = TermLookup.candidates(query, index)
}

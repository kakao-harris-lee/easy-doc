package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.TermMatchKind
import kr.easydoc.core.dictionary.TermQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 실제 색인을 얹은 어댑터가 [TermLookup][kr.easydoc.core.dictionary.TermLookup] 을 그대로 부르는지 확인한다. */
class IndexedTermCandidateSourceTest {
    private val source = IndexedTermCandidateSource(DictionaryIndexJsonReader().readClasspathResource())

    @Test
    @DisplayName("구비서류 -> 실제 색인에서 substitute 후보를 준다")
    fun `실제 색인으로 후보를 만든다`() {
        val candidates = source.candidatesFor(TermQuery.of("구비서류"))

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.term).isEqualTo("구비서류")
        assertThat(candidate.easyTerm).isEqualTo("준비할 서류")
        assertThat(candidate.strategy).isEqualTo(ReplaceStrategy.SUBSTITUTE)
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.EXACT)
        assertThat(candidate.applicable).isTrue()
    }
}

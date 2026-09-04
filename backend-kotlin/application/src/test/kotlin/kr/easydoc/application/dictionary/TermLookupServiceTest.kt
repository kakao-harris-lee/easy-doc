package kr.easydoc.application.dictionary

import kr.easydoc.core.dictionary.DictionaryExample
import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.RiskLevel
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermMatchKind
import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [TermLookupService] 가 원문을 [TermQuery] 로 정제한 뒤 포트에 넘기는지 확인한다. */
class TermLookupServiceTest {
    private val candidate =
        TermCandidate(
            term = "구비서류",
            easyTerm = "준비할 서류",
            strategy = ReplaceStrategy.SUBSTITUTE,
            risk = RiskLevel.NONE,
            definition = null,
            caution = null,
            tags = emptyList(),
            examples = emptyList<DictionaryExample>(),
            matchKind = TermMatchKind.EXACT,
            applicable = true,
        )

    @Test
    @DisplayName("정제된 질의를 그대로 포트에 넘기고 결과를 돌려준다")
    fun `포트가 준 후보를 그대로 돌려준다`() {
        var receivedText: String? = null
        val source =
            TermCandidateSource { query ->
                receivedText = query.text
                listOf(candidate)
            }
        val service = TermLookupService(source)

        val result = service.lookup(" 구비서류 ")

        assertThat(receivedText).isEqualTo("구비서류")
        assertThat(result).containsExactly(candidate)
    }

    @Test
    @DisplayName("빈 문자열은 포트를 부르지 않고 InvalidInputException 이다")
    fun `빈 문자열은 거절된다`() {
        var called = false
        val source =
            TermCandidateSource {
                called = true
                emptyList()
            }
        val service = TermLookupService(source)

        assertThatThrownBy { service.lookup("") }.isInstanceOf(InvalidInputException::class.java)
        assertThat(called).isFalse()
    }

    @Test
    @DisplayName("상한을 넘는 문자열은 InvalidInputException 이다")
    fun `상한 초과는 거절된다`() {
        val source = TermCandidateSource { emptyList() }
        val service = TermLookupService(source)

        assertThatThrownBy {
            service.lookup("가".repeat(TermQuery.MAX_LENGTH + 1))
        }.isInstanceOf(InvalidInputException::class.java)
    }
}

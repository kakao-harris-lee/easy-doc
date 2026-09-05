package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 조회 기능이 꺼졌을 때의 null object (P0-5 조각 4). */
class NoTermCandidateSourceTest {
    @Test
    @DisplayName("조회를 부르면 계약이 문서화한 문구로 InvalidInputException 을 던진다")
    fun `조회가 꺼져 있음을 알린다`() {
        assertThatThrownBy { NoTermCandidateSource.candidatesFor(TermQuery.of("구비서류")) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(NoTermCandidateSource.LOOKUP_DISABLED_MESSAGE)

        assertThat(NoTermCandidateSource.LOOKUP_DISABLED_MESSAGE).isEqualTo("사전 조회가 꺼져 있습니다")
    }
}

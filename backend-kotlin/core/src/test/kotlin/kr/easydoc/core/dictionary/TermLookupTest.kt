package kr.easydoc.core.dictionary

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P0-5 조각 2 회귀 - docs/plans/2026-09-04-p0-5-easy-word-dictionary-rag.md 4장.
 *
 * 여기 쓰는 색인은 DictionaryFixture 로 만든 작은 손짜기 색인이다(다른 dictionary
 * 테스트와 같은 관례). 계획 3.6절이 실측으로 적어 둔 실제 색인 값(id 2165, 2142, 1775 등)은
 * 사람이 읽는 참고용이고, 이 테스트가 확인하는 것은 그 값 자체가 아니라 TermLookup 이
 * 각 시나리오(정확 일치, 활용형, 최장일치, 복합어 부분 일치, 무결과)를 올바르게 분류하는지다.
 * 실제 색인에 대한 정확도 측정은 TermLookupFixtureTest(infrastructure) 몫이다.
 */
class TermLookupTest {
    private val index =
        DictionaryFixture()
            .add(
                DictionaryEntry(
                    term = "구비서류",
                    easyTerm = "준비할 서류",
                    strategy = ReplaceStrategy.SUBSTITUTE,
                    risk = RiskLevel.NONE,
                    priority = 140,
                ),
            ).add(
                DictionaryEntry(
                    term = "과태료",
                    easyTerm = "규칙을 안 지켜서 내는 돈",
                    strategy = ReplaceStrategy.GLOSS,
                    risk = RiskLevel.HIGH,
                    priority = 130,
                ),
            ).add(
                // 짧은 표제어 "시행"이 최장일치를 방해할 수 있는지 보는 경쟁 엔트리.
                DictionaryEntry(
                    term = "시행",
                    easyTerm = "실제로 함",
                    strategy = ReplaceStrategy.GLOSS,
                    risk = RiskLevel.LOW,
                    priority = 100,
                ),
            ).add(
                DictionaryEntry(
                    term = "시행령",
                    easyTerm = "법을 자세히 정한 대통령 규정",
                    strategy = ReplaceStrategy.GLOSS,
                    risk = RiskLevel.LOW,
                    priority = 130,
                ),
            ).add(
                // 복합어 "저소득가구"는 등재돼 있지 않지만 "저소득"은 등재돼 있다.
                DictionaryEntry(
                    term = "저소득",
                    easyTerm = "적은 수입",
                    strategy = ReplaceStrategy.GLOSS,
                    risk = RiskLevel.LOW,
                    priority = 130,
                ),
            ).add(
                // 활용형 표면형 "산정하여"가 표제어 "산정"과 다르다 - INFLECTED 분류 대상.
                DictionaryEntry(
                    term = "산정",
                    easyTerm = "계산함",
                    strategy = ReplaceStrategy.SUBSTITUTE,
                    risk = RiskLevel.NONE,
                    priority = 120,
                ),
                "산정하여",
            ).build()

    @Test
    @DisplayName("구비서류 -> 후보 1건, substitute, applicable=true, exact")
    fun `정확 일치는 exact 다`() {
        val candidates = TermLookup.candidates(TermQuery.of("구비서류"), index)

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.term).isEqualTo("구비서류")
        assertThat(candidate.easyTerm).isEqualTo("준비할 서류")
        assertThat(candidate.strategy).isEqualTo(ReplaceStrategy.SUBSTITUTE)
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.EXACT)
        assertThat(candidate.applicable).isTrue()
    }

    @Test
    @DisplayName("과태료를 -> gloss, risk=high, applicable=false, exact(조사는 매치 밖)")
    fun `조사가 붙어도 exact 다`() {
        val candidates = TermLookup.candidates(TermQuery.of("과태료를"), index)

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.term).isEqualTo("과태료")
        assertThat(candidate.risk).isEqualTo(RiskLevel.HIGH)
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.EXACT)
        assertThat(candidate.applicable).isFalse()
    }

    @Test
    @DisplayName("시행령 -> 시행령 단독. 시행으로 시작하는 짧은 후보가 앞서지 않는다")
    fun `최장일치가 짧은 경쟁 엔트리를 이긴다`() {
        val candidates = TermLookup.candidates(TermQuery.of("시행령"), index)

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().term).isEqualTo("시행령")
    }

    @Test
    @DisplayName("게시판 -> 후보 0건 (예외 아님, 빈 목록)")
    fun `사전에 없는 말은 빈 목록이다`() {
        assertThat(TermLookup.candidates(TermQuery.of("게시판"), index)).isEmpty()
    }

    @Test
    @DisplayName("저소득가구 -> compound_part + applicable=false")
    fun `복합어는 부분 일치로 잡히고 적용 불가다`() {
        val candidates = TermLookup.candidates(TermQuery.of("저소득가구"), index)

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.term).isEqualTo("저소득")
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.COMPOUND_PART)
        assertThat(candidate.applicable).isFalse()
    }

    @Test
    @DisplayName("산정하여 -> 활용형 표면형은 inflected 고, substitute 면 applicable=true")
    fun `활용형은 inflected 다`() {
        val candidates = TermLookup.candidates(TermQuery.of("산정하여"), index)

        assertThat(candidates).hasSize(1)
        val candidate = candidates.single()
        assertThat(candidate.term).isEqualTo("산정")
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.INFLECTED)
        assertThat(candidate.applicable).isTrue()
    }

    @Test
    @DisplayName("빈 문자열과 제어문자만 있는 질의는 TermQuery 생성을 거절한다")
    fun `빈 질의는 생성을 거절한다`() {
        assertThatThrownBy { TermQuery.of("") }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy { TermQuery.of("   ") }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("제어문자는 지우고 남는 내용으로 정제한다")
    fun `제어문자는 정제된다`() {
        val query = TermQuery.of("\u0020구비서류")

        assertThat(query.text).isEqualTo("구비서류")
    }

    @Test
    @DisplayName("상한을 넘는 질의는 생성을 거절한다")
    fun `상한을 넘으면 거절한다`() {
        val tooLong = "가".repeat(TermQuery.MAX_LENGTH + 1)
        assertThatThrownBy { TermQuery.of(tooLong) }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("상한과 정확히 같은 길이는 통과한다")
    fun `상한과 같은 길이는 허용한다`() {
        val exactly = "가".repeat(TermQuery.MAX_LENGTH)
        assertThat(TermQuery.of(exactly).text).hasSize(TermQuery.MAX_LENGTH)
    }
}

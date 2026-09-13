package kr.easydoc.core.dictionary

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @ParameterizedTest
    @ValueSource(strings = ["소득", "소득은", "주소", "소식", "자녀", "자연", "고지대"])
    fun `무관한 단어를 잘라 후보를 만들지 않는다`(query: String) {
        val ambiguous =
            DictionaryFixture()
                .add(DictionaryEntry("소", "소송", ReplaceStrategy.GLOSS, RiskLevel.HIGH, 110))
                .add(DictionaryEntry("자", "사람", ReplaceStrategy.SUBSTITUTE, RiskLevel.NONE, 110))
                .add(DictionaryEntry("고지", "공식으로 알림", ReplaceStrategy.GLOSS, RiskLevel.LOW, 120))
                .build()

        assertThat(TermLookup.candidates(TermQuery.of(query), ambiguous)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["구비서류 제출", "필요한 구비서류", "구비서류, 과태료", "구비서류 2부", "구비서류는 필요합니다"])
    fun `선택한 문구 일부의 일치를 전체 일치로 반환하지 않는다`(query: String) {
        assertThat(TermLookup.candidates(TermQuery.of(query), index)).isEmpty()
    }

    @Test
    fun `조사 연쇄만 남은 선택은 정상 조회한다`() {
        val candidate = TermLookup.candidates(TermQuery.of("구비서류에서만은"), index).single()

        assertThat(candidate.term).isEqualTo("구비서류")
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.EXACT)
        assertThat(candidate.applicable).isTrue()
    }

    @Test
    fun `확인된 복합어에는 조사를 허용하되 다른 단어까지 확장하지 않는다`() {
        val candidate = TermLookup.candidates(TermQuery.of("저소득가구는"), index).single()

        assertThat(candidate.term).isEqualTo("저소득")
        assertThat(candidate.matchKind).isEqualTo(TermMatchKind.COMPOUND_PART)
        assertThat(candidate.applicable).isFalse()
        assertThat(TermLookup.candidates(TermQuery.of("저소득가구원"), index)).isEmpty()
        assertThat(TermLookup.candidates(TermQuery.of("저소득가구 지원"), index)).isEmpty()
    }

    @Test
    fun `부분 설명의 뜻풀이가 고위험이거나 검토 필요하면 노출하지 않는다`() {
        listOf(
            DictionaryEntry("저소득", "적은 수입", ReplaceStrategy.GLOSS, RiskLevel.HIGH, 130),
            DictionaryEntry("저소득", "적은 수입", ReplaceStrategy.GLOSS, RiskLevel.LOW, 130, tags = listOf("needs_review")),
        ).forEach { entry ->
            val unsafe = DictionaryFixture().add(entry).build()

            assertThat(TermLookup.candidates(TermQuery.of("저소득가구"), unsafe)).isEmpty()
        }
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
    @DisplayName("XML이 못 담는 C0 제어문자는 걷어낸다 - 2026-09-05 리뷰(항목 3)")
    fun `실제 제어문자는 걷어낸다`() {
        val query = TermQuery.of("가\u0001나")

        assertThat(query.text).isEqualTo("가나")
    }

    @Test
    @DisplayName("짝을 이루지 않은 서로게이트는 걷어낸다 - core/text 정제 재사용 (2026-09-05 리뷰(항목 3))")
    fun `짝 없는 서로게이트는 걷어낸다`() {
        val query = TermQuery.of("가\uD800나")

        assertThat(query.text).isEqualTo("가나")
    }

    @Test
    @DisplayName("여러 줄에 걸친 선택은 한 줄로 뭉친다 - 편집기 다중 라인 선택 대응 (2026-09-05 리뷰(항목 3))")
    fun `여러 줄 선택은 한 칸 공백으로 뭉쳐진다`() {
        val query = TermQuery.of("구비\n서류")

        assertThat(query.text).isEqualTo("구비 서류")
    }

    @Test
    @DisplayName("이어지는 공백류는 한 칸으로 뭉친다")
    fun `연속 공백은 한 칸으로 뭉쳐진다`() {
        val query = TermQuery.of("구비   서류\t\t문서")

        assertThat(query.text).isEqualTo("구비 서류 문서")
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

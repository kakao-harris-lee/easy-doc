package kr.easydoc.core.quality

import kr.easydoc.core.easyread.MAX_SENTENCE_CHARS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 스타일 규칙 평가는 외부 API 없이 돈다. */
class GoldenStyleEvaluationTest {
    @Test
    @DisplayName("쉬운 문장은 스타일 규칙을 통과한다")
    fun `쉬운 문장은 통과한다`() {
        val evaluation = evaluateStyle("001", "오늘 안에 주민센터에 오세요.")

        assertThat(evaluation.passed).isTrue()
        assertThat(evaluation.toString()).doesNotContain("오늘 안에")
    }

    @Test
    @DisplayName("긴 문장과 어려운 말은 스타일 규칙 위반이다")
    fun `위반을 기계 검출한다`() {
        val long = "안".repeat(MAX_SENTENCE_CHARS + 1)
        val evaluation = evaluateStyle("002", long)

        assertThat(evaluation.passed).isFalse()
        assertThat(evaluation.result.issues).isNotEmpty()
        assertThat(evaluation.toString()).doesNotContain(long)
    }

    @Test
    @DisplayName("변환문에 필수 사실이 없으면 사실 평가가 실패한다")
    fun `사실 누락을 검출한다`() {
        val facts = listOf(RequiredFact("만 65세", listOf("65세")), RequiredFact("매월 25일"))

        val passed = evaluateFacts("001", "만 65세부터 매월 25일에 받습니다.", facts)
        val missing = evaluateFacts("001", "지원 대상에게 돈을 드립니다.", facts)

        assertThat(passed.passed).isTrue()
        assertThat(missing.passed).isFalse()
        assertThat(missing.missing.map { it.canonical }).containsExactly("만 65세", "매월 25일")
    }
}

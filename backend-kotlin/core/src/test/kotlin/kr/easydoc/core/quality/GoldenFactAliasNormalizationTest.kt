package kr.easydoc.core.quality

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `evaluateFacts`(정확히는 [RequiredFact.presentIn])의 표기 별칭 정규화 단위 검사.
 * 판단거리 10 권고 ⓒ — 8차 회차의 `required_facts` 오탐 4건을 재현해 고정한다.
 */
class GoldenFactAliasNormalizationTest {
    @Test
    @DisplayName("퍼센트 기호가 한글 표기로 바뀌어도 사실이 있다고 판정한다 — 099")
    fun `퍼센트 기호 별칭을 인정한다`() {
        val facts = listOf(RequiredFact("1.6%"))

        val evaluation = evaluateFacts("099", "이자율은 0에서 1.6퍼센트를 더한 값입니다.", facts)

        assertThat(evaluation.passed).isTrue()
    }

    @Test
    @DisplayName("복수 퍼센트 사실도 한글 표기로 바뀌면 모두 있다고 판정한다 — 101")
    fun `복수 퍼센트 별칭을 인정한다`() {
        val facts = listOf(RequiredFact("25%"), RequiredFact("5%"))

        val evaluation =
            evaluateFacts(
                "101",
                "이 중 25퍼센트는 지원금이고, 이자율은 연 5퍼센트입니다.",
                facts,
            )

        assertThat(evaluation.passed).isTrue()
    }

    @Test
    @DisplayName("공백 유무 차이는 사실이 있다고 판정한다 — 6차 40일이내")
    fun `공백 별칭을 인정한다`() {
        val facts = listOf(RequiredFact("40일이내"))

        val evaluation = evaluateFacts("063", "40일 이내로 늘릴 수 있습니다.", facts)

        assertThat(evaluation.passed).isTrue()
    }

    @Test
    @DisplayName("퍼센트포인트 흡수는 막지 않는다(의도된 통과) — 099")
    fun `퍼센트포인트 흡수를 의도적으로 허용한다`() {
        // 리뷰 지적: `presentIn` 이 %→퍼센트 로 정규화한 뒤 부분 문자열로 보므로,
        // 필수 사실 "1.6%" 가 "1.6%포인트"·"1.6퍼센트포인트" 양쪽에 걸려 통과한다.
        // 막지 않는 이유는 셋이다 — ⑴ 같은 흡수가 "%" 표기로는 정규화 이전부터 이미
        // 일어난다(9차 유료 회차, `099` 변환문 "1.6%포인트를 더한 값입니다"에서 필수
        // 사실 "1.6%" 가 그대로 통과했다). ⑵ 그러니 "퍼센트" 철자만 막으면 같은 뜻의
        // 두 표기(%포인트 / 퍼센트포인트)가 다르게 판정되는 앞뒤 안 맞는 게이트가 된다.
        // ⑶ `099` 의 실제 문맥(기준금리 가산)에서는 "%포인트"가 더 정확한 표기다 —
        // 막으면 옳게 쓴 변환문을 실패로 만든다. 그래서 이 테스트는 회귀 방지가 아니라
        // **현행 동작(양쪽 다 통과)을 고정**한다.
        val facts = listOf(RequiredFact("1.6%"))

        val viaSymbol = evaluateFacts("099", "이자율은 0에서 1.6%포인트를 더한 값입니다.", facts)
        val viaHangul = evaluateFacts("099", "이자율은 0에서 1.6퍼센트포인트를 더한 값입니다.", facts)

        assertThat(viaSymbol.passed).isTrue()
        assertThat(viaHangul.passed).isTrue()
    }

    @Test
    @DisplayName("전각 숫자로 쓰인 사실도 있다고 판정한다")
    fun `전각 숫자 별칭을 인정한다`() {
        val facts = listOf(RequiredFact("60만원"))

        val evaluation = evaluateFacts("fixture", "６０만 원까지 드립니다.", facts)

        assertThat(evaluation.passed).isTrue()
    }

    @Test
    @DisplayName("미탐 방어 1 — 이내가 실제로 빠지면 여전히 누락으로 잡는다 — 8차 063")
    fun `이내 누락은 여전히 검출한다`() {
        val facts = listOf(RequiredFact("40일이내"))

        val evaluation = evaluateFacts("063", "한 번 더 40일을 늘릴 수 있습니다.", facts)

        assertThat(evaluation.passed).isFalse()
    }

    @Test
    @DisplayName("미탐 방어 2 — 문장 재구성은 흡수하지 않는다 — 8차 049")
    fun `문장 재구성은 흡수하지 않는다`() {
        val facts = listOf(RequiredFact("9세 이상"))

        val evaluation = evaluateFacts("049", "9세부터 24세까지입니다.", facts)

        assertThat(evaluation.passed).isFalse()
    }
}

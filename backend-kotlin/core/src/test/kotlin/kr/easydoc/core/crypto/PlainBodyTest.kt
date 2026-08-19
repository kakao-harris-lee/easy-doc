package kr.easydoc.core.crypto

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **평문의 정의역** — `PlainBody` 로 만들 수 있는 값이 곧 저장 왕복이 보장되는 값이다.
 *
 * ## 이 파일이 재는 것
 *
 * 게이트 25 X1 이 든 반례는 `"x\uD800y"` 였다. `String.toByteArray(UTF_8)` 가 짝 없는
 * 서로게이트를 `?`(U+003F)로 바꿔 버리므로, 그 값을 저장하면 **인증은 통과하는데 본문이
 * 달라진다.** 아래 첫 케이스가 그 손실을 JDK 에서 직접 재현하고(즉 「고쳤으니 없어졌다」가
 * 아니라 **여전히 실재하는 위험**임을 붙잡아 둔다), 나머지가 그 값이 `PlainBody` 로
 * 들어오지 못한다는 것을 단언한다.
 *
 * 두 단언이 함께 있어야 의미가 있다 — 손실 재현만 있으면 「그래서 무엇을 했나」가 없고,
 * 거부 단언만 있으면 **왜 거부하는지**가 산문으로만 남아 다음 사람이 완화한다.
 */
class PlainBodyTest {
    @Test
    @DisplayName("짝 없는 서로게이트는 UTF-8 왕복에서 `?` 로 바뀐다 — 거부의 근거를 재현한다")
    fun `UTF-8 왕복이 짝 없는 서로게이트를 잃는다`() {
        val lone = "x\uD800y"

        val roundTripped = String(lone.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

        assertThat(roundTripped)
            .describedAs("이 JDK 는 짝 없는 서로게이트를 보존한다 — 그렇다면 아래 거부의 근거를 다시 판정하라")
            .isNotEqualTo(lone)
        assertThat(roundTripped).isEqualTo("x?y")
        assertThat(roundTripped[1]).isEqualTo('?')
    }

    @Test
    @DisplayName("짝 없는 서로게이트를 담은 본문은 만들어지지 않는다 (상위·하위·끝 잘림)")
    fun `짝 없는 서로게이트를 거부한다`() {
        listOf(
            "상위 서로게이트 뒤에 하위가 없다" to "x\uD800y",
            "하위 서로게이트가 홀로 나온다" to "x\uDC00y",
            "상위 서로게이트로 문자열이 끝난다" to "안내문\uD83D",
            "상위 뒤에 또 상위가 온다" to "\uD800\uD800",
        ).forEach { (label, broken) ->
            assertThatThrownBy { PlainBody(broken) }
                .describedAs("%s — 저장하면 본문이 조용히 바뀐다", label)
                .isInstanceOf(InvalidInputException::class.java)
                .hasMessage(PlainBody.UNPAIRED_SURROGATE_MESSAGE)
        }
    }

    @Test
    @DisplayName("거부 문구에 입력이 실리지 않는다 — 응답 detail 로 그대로 나가는 문자열이다")
    fun `거부 문구가 입력을 담지 않는다`() {
        val probe = "민원인 홍길동\uD800"

        val thrown = runCatching { PlainBody(probe) }.exceptionOrNull()

        assertThat(thrown?.message)
            .describedAs("예외 메시지에 입력 조각이 실렸다")
            .isEqualTo(PlainBody.UNPAIRED_SURROGATE_MESSAGE)
        assertThat(thrown?.message).doesNotContain("홍길동")
    }

    @Test
    @DisplayName("정상 텍스트는 그대로 통과한다 — 거부가 넓어지지 않았다")
    fun `정상 텍스트는 통과한다`() {
        listOf(
            "빈 값" to "",
            "한글" to "행정복지센터에서 신청하세요.",
            "ASCII" to "Please visit the community center.",
            "개행·탭" to "첫째 줄\n\t들여쓴 둘째 줄\r\n셋째 줄",
            // 짝을 이룬 서로게이트 쌍(이모지)은 정상 텍스트다. 여기가 빨개지면 거부가
            // BMP 밖 문자 전체로 번진 것이다.
            "짝 맞는 서로게이트 쌍" to "이모지 🙂 와 결합 문자 각́",
            // 제어문자·NUL 은 여기서 막지 않는다. UTF-8 로 손실 없이 왕복하므로 이 정의역의
            // 문제가 아니고, 정규화는 `core/text` 의 몫이다(02 control-char 판정).
            "NUL 과 제어문자" to "\u0000앞\u0007뒤",
        ).forEach { (label, plain) ->
            assertThatCode { PlainBody(plain) }.describedAs("%s 가 거부됐다", label).doesNotThrowAnyException()
            assertThat(PlainBody(plain).value).isEqualTo(plain)
        }
    }

    @Test
    @DisplayName("toString 이 본문을 내지 않는다 — 길이만 남긴다")
    fun `toString 이 본문을 내지 않는다`() {
        val body = "주민등록번호가 든 본문"

        assertThat(PlainBody(body).toString()).doesNotContain(body).contains("${body.length}자")
    }
}

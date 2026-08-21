package kr.easydoc.core.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 제어문자·보이지 않는 문자는 소스에 리터럴로 적지 않는다 — diff 에서 보이지 않고,
 * 편집기나 붙여넣기가 조용히 지워도 테스트는 계속 통과한다(무엇을 검증하는지가 사라진다).
 * 전부 `\uXXXX` 로 적는다.
 */
class TextNormalizationTest {
    @ParameterizedTest(name = "제어문자 {0} 은 지운다")
    @ValueSource(
        chars = [
            '\u0000', // NUL — 범위 시작
            '\u0008', // BACKSPACE — 범위 끝
            '\u000B', // VERTICAL TAB
            '\u000C', // FORM FEED
            '\u000E', // SHIFT OUT — 범위 시작
            '\u001F', // UNIT SEPARATOR — 범위 끝
            '\u007F', // DEL
        ],
    )
    fun `XML 이 담을 수 없는 제어문자를 지운다`(control: Char) {
        assertThat(stripControlChars("앞${control}뒤")).isEqualTo("앞뒤")
    }

    @ParameterizedTest(name = "구조 문자 {0} 은 남긴다")
    @ValueSource(chars = ['\u0009', '\u000A', '\u000D'])
    @DisplayName("탭·개행·복귀는 문서 구조를 이루므로 남긴다")
    fun `구조를 이루는 공백은 유지한다`(structural: Char) {
        assertThat(stripControlChars("앞${structural}뒤")).isEqualTo("앞${structural}뒤")
    }

    @Test
    fun `제어문자가 없으면 한 글자도 바뀌지 않는다`() {
        val text = "이 안내문에는 제어문자가 없습니다.\n두 번째 줄입니다."
        assertThat(stripControlChars(text)).isEqualTo(text)
    }

    @Test
    fun `빈 문자열에서 예외를 던지지 않는다`() {
        assertThat(stripControlChars("")).isEmpty()
    }

    @Test
    @DisplayName("보이지 않지만 제어문자가 아닌 문자는 건드리지 않는다")
    fun `제어문자가 아닌 보이지 않는 문자는 유지한다`() {
        val text = "소프트하이픈\u00AD폭없는공백\u200B비오엠\uFEFF"
        assertThat(stripControlChars(text)).isEqualTo(text)
    }

    @Test
    fun `여러 제어문자가 섞여도 모두 지운다`() {
        assertThat(stripControlChars("가\u0000 나\u0007다\u007F라")).isEqualTo("가 나다라")
    }
}

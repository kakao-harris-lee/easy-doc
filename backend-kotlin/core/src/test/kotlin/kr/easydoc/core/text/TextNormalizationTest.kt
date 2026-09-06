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

/**
 * 저장 경계에서 개행을 `\n` 하나로 통일한다 — `splitUnits`(`\n` 기준 분리)가 `\r` 을 삼키지
 * 않으므로, CRLF 문서가 저장되면 각 줄 끝에 `\r` 이 남아 문단 대응·문체 판정·화면 지도로
 * 새 나간다(2026-09-06 PR #51 리뷰에서 발견). `splitUnits`/`joinUnits` 자체는 바꾸지 않는다 —
 * 그 왕복 불변식(`SourceUnitsTest`)을 지키는 것이 이 함수가 존재하는 이유다.
 */
class NormalizeLineEndingsTest {
    @Test
    fun `CRLF 를 LF 로 바꾼다`() {
        assertThat(normalizeLineEndings("a\r\nb")).isEqualTo("a\nb")
    }

    @Test
    fun `단독 CR 도 LF 로 바꾼다`() {
        assertThat(normalizeLineEndings("a\rb")).isEqualTo("a\nb")
    }

    @Test
    fun `연속된 CRLF 두 쌍도 모두 바꾼다`() {
        assertThat(normalizeLineEndings("a\r\n\r\nb")).isEqualTo("a\n\nb")
    }

    @Test
    fun `CRLF 하나뿐이어도 바뀐다`() {
        assertThat(normalizeLineEndings("\r\n")).isEqualTo("\n")
    }

    @Test
    fun `CR 이 없는 텍스트는 한 글자도 바뀌지 않는다`() {
        val text = "이 안내문에는 복귀 문자가 없습니다.\n두 번째 줄입니다."
        assertThat(normalizeLineEndings(text)).isEqualTo(text)
    }

    @Test
    fun `LF 뿐인 텍스트는 그대로다`() {
        assertThat(normalizeLineEndings("\n")).isEqualTo("\n")
    }
}

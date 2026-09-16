package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** [cleanEasyText] — 표식만 남은 줄과 2연속 이상 빈 줄을 지운다. 본문은 절대 건드리지 않는다. */
class EasyTextCleanupTest {
    @Test
    @DisplayName("기호 표식만 있는 줄은 지운다")
    fun `기호 표식만 있는 줄은 지운다`() {
        assertThat(cleanEasyText("○\n본문")).isEqualTo("본문")
    }

    @Test
    @DisplayName("숫자 나열자만 있는 줄은 지운다")
    fun `숫자 나열자만 있는 줄은 지운다`() {
        assertThat(cleanEasyText("1.\n2.\n항목")).isEqualTo("항목")
    }

    @Test
    @DisplayName("빈 줄 4연속은 한 줄로 접는다")
    fun `빈 줄 4연속은 한 줄로 접는다`() {
        assertThat(cleanEasyText("본문\n\n\n\n본문")).isEqualTo("본문\n\n본문")
    }

    @Test
    @DisplayName("표식 제거로 새로 생긴 빈 줄 런도 접힌다")
    fun `표식 제거로 새로 생긴 빈 줄 런도 접힌다`() {
        assertThat(cleanEasyText("본문\n\n○\n\n본문")).isEqualTo("본문\n\n본문")
    }

    @Test
    @DisplayName("표의 빈 칸을 나타내는 단독 하이픈 줄은 표식으로 보고 지운다 — 본문 하이픈과 구분되지 않기 때문이다")
    fun `단독 하이픈 줄은 표식으로 지운다`() {
        assertThat(cleanEasyText("표\n-\n끝")).isEqualTo("표\n끝")
    }

    @Test
    @DisplayName("단독 ※ 줄은 다음 줄에 붙인다 — DocumentMarkers는 ※를 본문 없이도 표식으로 센다")
    fun `단독 ※ 줄은 다음 줄에 붙인다`() {
        assertThat(cleanEasyText("※\n신청할 때는 서류를 내야 합니다.")).isEqualTo("※ 신청할 때는 서류를 내야 합니다.")
    }

    @Test
    @DisplayName("붙일 다음 줄이 빈 줄이면 ※ 줄은 그냥 지우고 빈 줄 런도 접는다")
    fun `다음 줄이 빈 줄이면 ※ 줄만 지운다`() {
        assertThat(cleanEasyText("본문\n※\n\n다음")).isEqualTo("본문\n\n다음")
    }

    @Test
    @DisplayName("붙일 다음 줄이 없으면(마지막 줄) ※ 줄은 그냥 지운다")
    fun `다음 줄이 없으면 ※ 줄만 지운다`() {
        assertThat(cleanEasyText("본문\n※")).isEqualTo("본문")
    }

    @Test
    @DisplayName("다음 줄이 표식만 있는 줄이면 붙이지 않고 ※ 줄만 지운다")
    fun `다음 줄이 표식만 있는 줄이면 ※ 줄만 지운다`() {
        assertThat(cleanEasyText("※\n○\n본문")).isEqualTo("본문")
    }

    @Test
    @DisplayName("※ 뒤에 본문이 있는 줄은 건드리지 않는다")
    fun `※ 뒤에 본문이 있는 줄은 그대로 둔다`() {
        assertThat(cleanEasyText("※ 안내")).isEqualTo("※ 안내")
    }

    @Test
    @DisplayName("※가 둘 이상인 줄은 단독 ※ 로 보지 않고 그대로 둔다")
    fun `※※ 는 그대로 둔다`() {
        assertThat(cleanEasyText("※※")).isEqualTo("※※")
    }

    @Test
    @DisplayName("실제 LLM 응답 꼬리의 단독 ※ 줄도 다음 문장에 붙는다")
    fun `실제 응답 꼬리에서도 ※가 다음 문장에 붙는다`() {
        val tail =
            "신청 기간은 3월 1일부터 3월 31일까지이며 이 기간 안에 신청할 수 있습니다.\n" +
                "\n" +
                "※\n" +
                "신청할 때는 다음 서류를 반드시 함께 내야 합니다.\n" +
                "임대차계약서 사본: 주민센터에서 확인할 수 있습니다."
        val expected =
            "신청 기간은 3월 1일부터 3월 31일까지이며 이 기간 안에 신청할 수 있습니다.\n" +
                "\n" +
                "※ 신청할 때는 다음 서류를 반드시 함께 내야 합니다.\n" +
                "임대차계약서 사본: 주민센터에서 확인할 수 있습니다."
        assertThat(cleanEasyText(tail)).isEqualTo(expected)
    }

    @Test
    @DisplayName("빈 문자열은 빈 문자열이다")
    fun `빈 문자열은 빈 문자열이다`() {
        assertThat(cleanEasyText("")).isEqualTo("")
    }

    @Test
    @DisplayName("한 줄짜리 본문은 그대로 둔다")
    fun `한 줄짜리 본문은 그대로 둔다`() {
        assertThat(cleanEasyText("본문")).isEqualTo("본문")
    }

    @ParameterizedTest(name = "{0} — 지우지 않는다")
    @MethodSource("preservedLines")
    @DisplayName("본문으로 볼 수 있는 줄은 표식으로 오인해 지우지 않는다")
    fun `본문으로 볼 수 있는 줄은 지우지 않는다`(line: String) {
        assertThat(cleanEasyText(line)).isEqualTo(line)
    }

    companion object {
        @JvmStatic
        fun preservedLines(): List<Arguments> =
            listOf(
                "02-123-4567", // 전화번호 — 숫자 사이 하이픈
                "2026", // 4자리 이상 숫자 — 표식 나열자는 1~3자리만
                "10,000", // 쉼표 있는 금액
                "10", // 구두점 없는 숫자만 있는 줄
                "- 항목", // 표식 뒤 본문이 있는 줄
                "※ 안내", // 안내 표식 뒤 본문이 있는 줄
                "1. 신청", // 숫자 나열자 뒤 본문이 있는 줄
                "가. 대상", // 한글 나열자 뒤 본문이 있는 줄
            ).map { Arguments.of(it) }
    }
}

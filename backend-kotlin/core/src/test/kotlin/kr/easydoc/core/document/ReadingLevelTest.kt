package kr.easydoc.core.document

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReadingLevelTest {
    @Test
    fun `중등 수준을 wire 값으로 받는다`() {
        assertThat(ReadingLevel.ofWireName("middle_school").wireName).isEqualTo("middle_school")
    }

    @Test
    fun `wire 값은 세 수준과 왕복한다`() {
        ReadingLevel.entries.forEach { level ->
            assertThat(ReadingLevel.ofWireName(level.wireName)).isEqualTo(level)
        }
    }

    @Test
    fun `알 수 없는 수준은 사용자 값을 되풀이하지 않고 거절한다`() {
        assertThatThrownBy { ReadingLevel.ofWireName("secret-canary") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("쉬운 글 수준은 middle_school, grade_5_6 또는 grade_3_4여야 합니다")
            .message()
            .doesNotContain("secret-canary")
    }
}

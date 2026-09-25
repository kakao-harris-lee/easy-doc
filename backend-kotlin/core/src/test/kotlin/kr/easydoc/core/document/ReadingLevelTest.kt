package kr.easydoc.core.document

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReadingLevelTest {
    @Test
    fun `wire 값은 두 수준과 왕복한다`() {
        ReadingLevel.entries.forEach { level ->
            assertThat(ReadingLevel.ofWireName(level.wireName)).isEqualTo(level)
        }
    }

    @Test
    fun `알 수 없는 수준은 사용자 값을 되풀이하지 않고 거절한다`() {
        assertThatThrownBy { ReadingLevel.ofWireName("secret-canary") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("쉬운 글 수준은 grade_5_6 또는 grade_3_4여야 합니다")
            .message()
            .doesNotContain("secret-canary")
    }
}

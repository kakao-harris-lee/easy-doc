package kr.easydoc.infrastructure.format

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FormatRegistryTest {
    @Test
    fun `등록 순서와 무관하게 형식으로 구현을 찾는다`() {
        val registry = FormatRegistry(listOf("pdf", "txt"), setOf('t', 'p')) { it.first() }

        assertThat(registry['t']).isEqualTo("txt")
        assertThat(registry['p']).isEqualTo("pdf")
    }

    @Test
    fun `중복 형식을 조립 시 거절한다`() {
        assertThatThrownBy { FormatRegistry(listOf("txt", "text"), setOf('t')) { it.first() } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate")
    }

    @Test
    fun `누락되거나 예상하지 못한 형식을 조립 시 거절한다`() {
        assertThatThrownBy { FormatRegistry(listOf("txt"), setOf('t', 'p')) { it.first() } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FormatRegistry(listOf("txt", "pdf"), setOf('t')) { it.first() } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

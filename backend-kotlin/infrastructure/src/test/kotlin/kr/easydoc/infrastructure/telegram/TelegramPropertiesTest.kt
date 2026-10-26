package kr.easydoc.infrastructure.telegram

import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class TelegramPropertiesTest {
    @Test
    fun `기본은 비활성화고 자격 증명 없이 허용한다`() {
        val p = TelegramProperties()
        assertThat(p.enabled).isFalse()
        assertThat(p.connectTimeout).isEqualTo(Duration.ofSeconds(2))
        assertThat(p.readTimeout).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `활성화 필수값과 양의 timeout을 검증하고 toString은 자격 증명을 숨긴다`() {
        assertThatThrownBy { TelegramProperties(enabled = true) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TelegramProperties(enabled = true, botToken = Secret("token"), connectTimeout = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TelegramProperties(
                enabled = true,
                botToken = Secret("token"),
                chatId = "chat",
                readTimeout = Duration.ofSeconds(-1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        val rendered = TelegramProperties(true, Secret("real-looking-token"), "private-chat").toString()
        assertThat(rendered).doesNotContain("real-looking-token", "private-chat")
    }
}

package kr.easydoc.infrastructure.telegram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramConfigurationTest {
    @Test
    fun `기본 URL로 출력 포트를 조립한다`() {
        val port = TelegramConfiguration().telegramOperatorNotificationAdapter(TelegramProperties())

        assertThat(TelegramConfiguration.DEFAULT_BASE_URL).isEqualTo("https://api.telegram.org")
        assertThat(port).isInstanceOf(TelegramOperatorNotificationAdapter::class.java)
    }
}

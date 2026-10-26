package kr.easydoc.infrastructure.telegram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramConfigurationTest {
    @Test
    fun `기본 URL로 RestClient와 출력 포트를 조립한다`() {
        val configuration = TelegramConfiguration()
        val properties = TelegramProperties()
        val client = configuration.telegramRestClient(properties)
        val port = configuration.telegramOperatorNotificationAdapter(client, properties)

        assertThat(TelegramConfiguration.DEFAULT_BASE_URL).isEqualTo("https://api.telegram.org")
        assertThat(port).isInstanceOf(TelegramOperatorNotificationAdapter::class.java)
    }
}

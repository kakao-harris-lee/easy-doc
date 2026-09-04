package kr.easydoc.infrastructure.mail

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** provider 선택 조립 — `fake`·`smtp` 둘이고, 다른 값은 기동 시점에 거절한다. */
class MailConfigurationTest {
    private val configuration = MailConfiguration()

    @Test
    @DisplayName("provider=fake 는 FakeMailSender 를 조립한다")
    fun `fake 를 조립한다`() {
        val sender = configuration.mailSender(MailProperties(provider = "fake"))

        assertThat(sender).isInstanceOf(FakeMailSender::class.java)
    }

    @Test
    @DisplayName("provider 대소문자는 가리지 않는다")
    fun `대소문자를 가리지 않는다`() {
        val sender = configuration.mailSender(MailProperties(provider = "FAKE"))

        assertThat(sender).isInstanceOf(FakeMailSender::class.java)
    }

    @Test
    @DisplayName("아직 없는 provider(ses 등)는 ConfigurationException 으로 기동을 막는다")
    fun `모르는 provider 는 거절한다`() {
        assertThatThrownBy { configuration.mailSender(MailProperties(provider = "ses")) }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("provider=smtp 는 필수값이 모두 있으면 SmtpMailSender 를 조립한다")
    fun `smtp 를 조립한다`() {
        val sender = configuration.mailSender(fullySpecifiedSmtpProperties())

        assertThat(sender).isInstanceOf(SmtpMailSender::class.java)
    }

    @Test
    @DisplayName("provider=smtp 대소문자는 가리지 않는다")
    fun `smtp 대소문자를 가리지 않는다`() {
        val sender = configuration.mailSender(fullySpecifiedSmtpProperties(provider = "SMTP"))

        assertThat(sender).isInstanceOf(SmtpMailSender::class.java)
    }

    @Test
    @DisplayName("provider=smtp 인데 host 가 비면 ConfigurationException")
    fun `smtp host 누락은 거절한다`() {
        val properties = fullySpecifiedSmtpProperties(smtp = fullySpecifiedSmtp().copy(host = ""))

        assertThatThrownBy { configuration.mailSender(properties) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.mail.smtp.host")
    }

    @Test
    @DisplayName("provider=smtp 인데 username 이 비면 ConfigurationException")
    fun `smtp username 누락은 거절한다`() {
        val properties = fullySpecifiedSmtpProperties(smtp = fullySpecifiedSmtp().copy(username = ""))

        assertThatThrownBy { configuration.mailSender(properties) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.mail.smtp.username")
    }

    @Test
    @DisplayName("provider=smtp 인데 password 가 비면 ConfigurationException")
    fun `smtp password 누락은 거절한다`() {
        val properties = fullySpecifiedSmtpProperties(smtp = fullySpecifiedSmtp().copy(password = Secret.EMPTY))

        assertThatThrownBy { configuration.mailSender(properties) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.mail.smtp.password")
    }

    @Test
    @DisplayName("provider=smtp 인데 from-address 가 비면 ConfigurationException")
    fun `smtp from-address 누락은 거절한다`() {
        val properties = fullySpecifiedSmtpProperties(fromAddress = "")

        assertThatThrownBy { configuration.mailSender(properties) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.mail.from-address")
    }

    private fun fullySpecifiedSmtp(): SmtpProperties =
        SmtpProperties(
            host = "smtp.example.com",
            port = 465,
            ssl = true,
            username = "pilot",
            password = Secret("app-password"),
        )

    private fun fullySpecifiedSmtpProperties(
        provider: String = "smtp",
        fromAddress: String = "pilot@example.com",
        smtp: SmtpProperties = fullySpecifiedSmtp(),
    ): MailProperties = MailProperties(provider = provider, fromAddress = fromAddress, smtp = smtp)
}

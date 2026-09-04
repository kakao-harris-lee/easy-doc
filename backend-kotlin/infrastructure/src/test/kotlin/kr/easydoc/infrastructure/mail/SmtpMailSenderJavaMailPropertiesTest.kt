package kr.easydoc.infrastructure.mail

import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `SmtpProperties` → `JavaMailSenderImpl` 매핑. GreenMail 을 거치지 않는 순수 단위
 * 테스트다 — `ssl` 이 실제로 `mail.smtp.ssl.enable` 을 세우는지는 이쪽으로 잰다
 * (GreenMail 의 SSL 소켓 설정은 몇 줄로 끝나지 않아 [SmtpMailSenderTest] 는 평문 SMTP
 * 엔드포인트만 쓴다 — `MailConfigurationTest` 팀 지침 참고).
 */
class SmtpMailSenderJavaMailPropertiesTest {
    @Test
    @DisplayName("ssl=true 는 mail.smtp.ssl.enable 을 true 로 세운다 — 접속 시점 암시적 TLS(SMTPS)")
    fun `ssl 참이면 ssl_enable 이 true 다`() {
        val smtp = SmtpProperties(host = "h", port = 465, ssl = true, username = "u", password = Secret("p"))

        val sender = SmtpMailSender.javaMailSender(smtp, timeoutMs = 3000)

        assertThat(sender.javaMailProperties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true")
    }

    @Test
    @DisplayName("ssl=false 는 mail.smtp.ssl.enable 을 false 로 세운다")
    fun `ssl 거짓이면 ssl_enable 이 false 다`() {
        val smtp = SmtpProperties(host = "h", port = 25, ssl = false, username = "u", password = Secret("p"))

        val sender = SmtpMailSender.javaMailSender(smtp, timeoutMs = 3000)

        assertThat(sender.javaMailProperties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false")
    }

    @Test
    @DisplayName("host·port·username·password·timeout 이 그대로 실린다")
    fun `연결 정보와 timeout 이 실린다`() {
        val smtp =
            SmtpProperties(host = "smtp.example.com", port = 465, ssl = true, username = "u", password = Secret("p"))

        val sender = SmtpMailSender.javaMailSender(smtp, timeoutMs = 7000)

        assertThat(sender.host).isEqualTo("smtp.example.com")
        assertThat(sender.port).isEqualTo(465)
        assertThat(sender.username).isEqualTo("u")
        assertThat(sender.password).isEqualTo("p")
        assertThat(sender.javaMailProperties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("7000")
        assertThat(sender.javaMailProperties.getProperty("mail.smtp.timeout")).isEqualTo("7000")
        assertThat(sender.javaMailProperties.getProperty("mail.smtp.writetimeout")).isEqualTo("7000")
        assertThat(sender.javaMailProperties.getProperty("mail.smtp.auth")).isEqualTo("true")
    }
}

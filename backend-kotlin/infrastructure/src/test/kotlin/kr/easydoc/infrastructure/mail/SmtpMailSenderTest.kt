package kr.easydoc.infrastructure.mail

import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * `SmtpMailSender` ↔ 실제 SMTP 프로토콜 왕복. GreenMail(임베디드 fake 서버, `localhost` 안
 * 에서만 돈다)로 검증한다 — **실제 네트워크로 나가지 않는다.**
 *
 * **평문 SMTP 엔드포인트만 쓴다.** GreenMail 의 SMTPS(SSL 소켓) 설정은 자체 서명 인증서
 * 신뢰 설정까지 필요해 몇 줄로 끝나지 않는다 — `ssl` 플래그 자체가
 * `mail.smtp.ssl.enable` 을 세우는지는 GreenMail 없이 [SmtpMailSenderJavaMailPropertiesTest]
 * 가 확인한다(팀 지침이 제시한 대안 경로).
 */
class SmtpMailSenderTest {
    @RegisterExtension
    val greenMail: GreenMailExtension =
        GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(FROM_ADDRESS, USERNAME, PASSWORD))

    @Test
    @DisplayName("제목·본문·발신·수신이 그대로 도착한다")
    fun `발송한 메일이 도착한다`() {
        val result =
            sender().send(
                OutboundMail(
                    to = EmailAddress.of("recipient@example.com"),
                    subject = "제목입니다",
                    textBody = "본문입니다",
                ),
            )

        assertThat(result).isInstanceOf(MailDelivery.Sent::class.java)
        val received = greenMail.receivedMessages
        assertThat(received).hasSize(1)
        assertThat(received[0].subject).isEqualTo("제목입니다")
        // `GreenMailUtil.getBody` 는 Content-Transfer-Encoding 을 디코드하지 않고 원문
        // 그대로 돌려준다(javadoc 명시) — UTF-8 한글 본문은 JavaMail 이 표준으로 base64
        // 인코딩하므로, 표준 API(`getContent()`)로 읽어야 디코드된 본문을 본다.
        assertThat(received[0].content as String).contains("본문입니다")
        assertThat(received[0].allRecipients.map { it.toString() }).containsExactly("recipient@example.com")
        assertThat(received[0].from.map { it.toString() }).containsExactly(FROM_ADDRESS)
    }

    @Test
    @DisplayName("접속할 수 없으면 예외를 던지지 않고 Rejected 를 돌려준다")
    fun `연결 실패는 Rejected 다`() {
        val unreachable =
            SmtpMailSender(
                SmtpMailSender.javaMailSender(
                    SmtpProperties(
                        host = "127.0.0.1",
                        port = UNREACHABLE_PORT,
                        ssl = false,
                        username = USERNAME,
                        password = Secret(PASSWORD),
                    ),
                    timeoutMs = 500,
                ),
                FROM_ADDRESS,
            )

        val result = unreachable.send(OutboundMail(EmailAddress.of("recipient@example.com"), "제목", "본문"))

        assertThat(result).isInstanceOf(MailDelivery.Rejected::class.java)
    }

    private fun sender(): SmtpMailSender =
        SmtpMailSender(
            SmtpMailSender.javaMailSender(
                SmtpProperties(
                    host = "localhost",
                    port = greenMail.smtp.port,
                    ssl = false,
                    username = USERNAME,
                    password = Secret(PASSWORD),
                ),
                timeoutMs = 5000,
            ),
            FROM_ADDRESS,
        )

    private companion object {
        const val FROM_ADDRESS = "sender@example.com"
        const val USERNAME = "sender"
        const val PASSWORD = "test-password-only"

        /** 아무 서버도 듣지 않을 loopback 포트 — 접속 즉시 거절돼야 한다. */
        const val UNREACHABLE_PORT = 1
    }
}

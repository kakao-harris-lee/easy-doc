package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.OutboundMail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FakeMailSenderTest {
    @Test
    @DisplayName("보낸 메일을 메모리에 기록하고 Sent 를 돌려준다")
    fun `메일을 기록한다`() {
        val sender = FakeMailSender()
        val mail = OutboundMail(EmailAddress.of("user@example.com"), "제목", "본문")

        val result = sender.send(mail)

        assertThat(result).isInstanceOf(MailDelivery.Sent::class.java)
        assertThat(sender.sent).containsExactly(mail)
    }

    @Test
    @DisplayName("실제 네트워크를 쓰지 않는다 — 반복 호출도 기록만 늘어난다")
    fun `여러 통을 순서대로 기록한다`() {
        val sender = FakeMailSender()
        val first = OutboundMail(EmailAddress.of("a@example.com"), "제목1", "본문1")
        val second = OutboundMail(EmailAddress.of("b@example.com"), "제목2", "본문2")

        sender.send(first)
        sender.send(second)

        assertThat(sender.sent).containsExactly(first, second)
    }
}

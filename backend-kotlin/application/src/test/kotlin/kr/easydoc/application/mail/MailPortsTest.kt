package kr.easydoc.application.mail

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MailPortsTest {
    @Test
    @DisplayName("OutboundMail.toString 은 수신자·본문 내용을 찍지 않는다")
    fun `발신 메일 toString 이 내용을 가린다`() {
        val mail =
            OutboundMail(
                to = EmailAddress.of("user@example.com"),
                subject = "제목",
                textBody = "비밀 문서 본문 조각",
            )

        val printed = mail.toString()

        assertThat(printed).doesNotContain("user@example.com", "비밀 문서 본문 조각")
        assertThat(printed).contains("제목")
    }

    @Test
    @DisplayName("MailDelivery 는 Sent 와 Rejected 두 갈래다")
    fun `발송 결과 sealed 타입`() {
        val sent: MailDelivery = MailDelivery.Sent(providerMessageId = "id-1")
        val rejected: MailDelivery = MailDelivery.Rejected(MailRejectReason.PROVIDER_ERROR)

        assertThat(sent).isInstanceOf(MailDelivery.Sent::class.java)
        assertThat((sent as MailDelivery.Sent).providerMessageId).isEqualTo("id-1")
        assertThat(rejected).isInstanceOf(MailDelivery.Rejected::class.java)
        assertThat((rejected as MailDelivery.Rejected).reason).isEqualTo(MailRejectReason.PROVIDER_ERROR)
    }
}

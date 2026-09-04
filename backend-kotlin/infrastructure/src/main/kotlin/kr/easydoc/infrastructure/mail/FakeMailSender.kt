package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실제 네트워크 호출 없이 메일을 메모리에 기록한다. `local`/`ci`/테스트 전용이며 지금은
 * **유일하게 구현된 어댑터**다(`MailConfiguration` KDoc).
 */
class FakeMailSender : MailSender {
    private val record = CopyOnWriteArrayList<OutboundMail>()

    /** 보낸 순서대로. 테스트·로컬 확인용 — 운영 조회 API 가 아니다. */
    val sent: List<OutboundMail> get() = record.toList()

    override fun send(message: OutboundMail): MailDelivery {
        record.add(message)
        return MailDelivery.Sent(providerMessageId = null)
    }
}

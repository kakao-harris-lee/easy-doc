package kr.easydoc.application.conversion

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailRejectReason
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 변환 완료 메일 알림 — 재실행 안전(멱등), 실패해도 변환을 막지 않는다. */
class ConversionCompletedNotifierTest {
    @Test
    @DisplayName("완료 변환 하나당 메일을 정확히 한 번 보낸다")
    fun `메일을 한 번 보낸다`() {
        val conversionId = UUID.randomUUID()
        val store =
            FakeStore(
                ConversionNotificationTarget(
                    documentTitle = "복지 안내문",
                    ownerEmail = EmailAddress.of("owner@example.com"),
                    alreadyNotified = false,
                ),
            )
        val sender = RecordingSender()
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        notifier.notify(conversionId)

        assertThat(sender.sent).hasSize(1)
        assertThat(store.markNotifiedCalls).containsExactly(conversionId)
    }

    @Test
    @DisplayName("이미 알림을 보낸 변환은 건너뛴다 — 재실행 안전")
    fun `이미 보낸 알림은 건너뛴다`() {
        val conversionId = UUID.randomUUID()
        val store =
            FakeStore(
                ConversionNotificationTarget(
                    documentTitle = "복지 안내문",
                    ownerEmail = EmailAddress.of("owner@example.com"),
                    alreadyNotified = true,
                ),
            )
        val sender = RecordingSender()
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        notifier.notify(conversionId)

        assertThat(sender.sent).isEmpty()
        assertThat(store.markNotifiedCalls).isEmpty()
    }

    @Test
    @DisplayName("발송기가 거절해도 예외를 던지지 않는다")
    fun `거절돼도 던지지 않는다`() {
        val conversionId = UUID.randomUUID()
        val store =
            FakeStore(
                ConversionNotificationTarget(
                    documentTitle = "복지 안내문",
                    ownerEmail = EmailAddress.of("owner@example.com"),
                    alreadyNotified = false,
                ),
            )
        val sender = RecordingSender(result = MailDelivery.Rejected(MailRejectReason.PROVIDER_ERROR))
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        assertThatCode { notifier.notify(conversionId) }.doesNotThrowAnyException()
        assertThat(store.markNotifiedCalls).isEmpty()
    }

    @Test
    @DisplayName("발송기가 예외를 던져도 알림 호출은 던지지 않는다")
    fun `발송 예외도 삼킨다`() {
        val conversionId = UUID.randomUUID()
        val store =
            FakeStore(
                ConversionNotificationTarget(
                    documentTitle = "복지 안내문",
                    ownerEmail = EmailAddress.of("owner@example.com"),
                    alreadyNotified = false,
                ),
            )
        val sender = ThrowingSender()
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        assertThatCode { notifier.notify(conversionId) }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("알림 대상이 없으면(문서 삭제 등) 조용히 넘어간다")
    fun `대상이 없으면 넘어간다`() {
        val conversionId = UUID.randomUUID()
        val store = FakeStore(target = null)
        val sender = RecordingSender()
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        assertThatCode { notifier.notify(conversionId) }.doesNotThrowAnyException()
        assertThat(sender.sent).isEmpty()
    }

    @Test
    @DisplayName("메일 본문은 문서 제목과 링크만 담는다")
    fun `본문은 제목과 링크만이다`() {
        val conversionId = UUID.randomUUID()
        val store =
            FakeStore(
                ConversionNotificationTarget(
                    documentTitle = "복지 안내문",
                    ownerEmail = EmailAddress.of("owner@example.com"),
                    alreadyNotified = false,
                ),
            )
        val sender = RecordingSender()
        val notifier = ConversionCompletedNotifier(store, sender, PUBLIC_BASE_URL)

        notifier.notify(conversionId)

        val mail = sender.sent.single()
        assertThat(mail.textBody).contains("복지 안내문")
        assertThat(mail.textBody).contains("$PUBLIC_BASE_URL/conversions/$conversionId")
        assertThat(mail.subject).isNotBlank()
    }

    private class FakeStore(private val target: ConversionNotificationTarget?) : ConversionNotificationStore {
        val markNotifiedCalls = mutableListOf<UUID>()

        override fun findTarget(conversionId: UUID): ConversionNotificationTarget? = target

        override fun markNotified(conversionId: UUID): Boolean {
            markNotifiedCalls += conversionId
            return true
        }
    }

    private class RecordingSender(private val result: MailDelivery = MailDelivery.Sent()) : MailSender {
        val sent = mutableListOf<OutboundMail>()

        override fun send(message: OutboundMail): MailDelivery {
            sent += message
            return result
        }
    }

    private class ThrowingSender : MailSender {
        override fun send(message: OutboundMail): MailDelivery = error("네트워크 실패")
    }

    private companion object {
        const val PUBLIC_BASE_URL = "http://localhost:5173"
    }
}

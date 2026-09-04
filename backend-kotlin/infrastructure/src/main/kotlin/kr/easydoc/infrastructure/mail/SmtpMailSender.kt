package kr.easydoc.infrastructure.mail

import jakarta.mail.MessagingException
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailRejectReason
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * `spring-boot-starter-mail`(JavaMail) 을 쓰는 SMTP relay 어댑터. 임시 provider다
 * (`MailConfiguration` KDoc — 사용자 결정 2026-09-04, SES 전환 전까지 소비자 메일 계정을
 * relay 로 쓴다).
 *
 * 어댑터 자신은 재시도하지 않는다(CLAUDE.md — 재시도 책임은 한 계층만 갖는다. 여기서는
 * 상위 [kr.easydoc.application.conversion.ConversionCompletedNotifier] 가 실패를 삼키고
 * 끝낼 뿐 재시도하지 않으므로, 지금 이 경로에 재시도는 어디에도 없다). 예외는 전부
 * [MailDelivery.Rejected] 로 접는다 — 수신 주소·본문·예외 메시지는 로그에 남기지 않고
 * 예외 클래스명만 남긴다.
 */
class SmtpMailSender(
    private val sender: JavaMailSender,
    private val fromAddress: String,
) : MailSender {
    private val log = LoggerFactory.getLogger(SmtpMailSender::class.java)

    override fun send(message: OutboundMail): MailDelivery =
        try {
            val mimeMessage = sender.createMimeMessage()
            // UTF-8 명시 — 안 하면 한글 본문이 JavaMail 기본 인코딩으로 깨져 나간다.
            val helper = MimeMessageHelper(mimeMessage, false, CHARSET)
            helper.setFrom(fromAddress)
            helper.setTo(message.to.value)
            helper.setSubject(message.subject)
            helper.setText(message.textBody, false)
            sender.send(mimeMessage)
            MailDelivery.Sent()
        } catch (exc: MessagingException) {
            log.warn("SMTP 메일 작성 실패: {}", exc::class.simpleName)
            MailDelivery.Rejected(MailRejectReason.PROVIDER_ERROR)
        } catch (exc: MailException) {
            log.warn("SMTP 메일 발송 실패: {}", exc::class.simpleName)
            MailDelivery.Rejected(MailRejectReason.PROVIDER_ERROR)
        }

    companion object {
        /** [MailConfiguration] 이 `provider=smtp` 를 고를 때 부른다. */
        fun from(properties: MailProperties): SmtpMailSender =
            SmtpMailSender(javaMailSender(properties.smtp, properties.timeoutMs), properties.fromAddress)

        /**
         * `SmtpProperties` → [JavaMailSenderImpl]. `internal` 인 이유는 `ssl` 이 실제로
         * `mail.smtp.ssl.enable` 을 세우는지를 GreenMail 없이 단위 테스트로 직접 확인하기
         * 위해서다(`SmtpMailSenderJavaMailPropertiesTest`).
         */
        internal fun javaMailSender(
            smtp: SmtpProperties,
            timeoutMs: Long,
        ): JavaMailSenderImpl =
            JavaMailSenderImpl().apply {
                host = smtp.host
                port = smtp.port
                username = smtp.username
                password = smtp.password.reveal()
                javaMailProperties.apply {
                    setProperty("mail.transport.protocol", "smtp")
                    setProperty("mail.smtp.auth", "true")
                    // 접속 시점 암시적 TLS(SMTPS) — STARTTLS 가 아니다(SmtpProperties KDoc).
                    setProperty("mail.smtp.ssl.enable", smtp.ssl.toString())
                    setProperty("mail.smtp.connectiontimeout", timeoutMs.toString())
                    setProperty("mail.smtp.timeout", timeoutMs.toString())
                    setProperty("mail.smtp.writetimeout", timeoutMs.toString())
                }
            }

        /** 한글 본문이 JavaMail 기본 인코딩으로 깨지지 않게 명시한다. */
        private const val CHARSET: String = "UTF-8"
    }
}

package kr.easydoc.infrastructure.mail

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class NotificationMailConfigurationTest {
    private val factory =
        NotificationMailConfiguration().notificationMailFactory(MailTemplateProperties(), DefaultResourceLoader())
    private val recipient = EmailAddress.of("owner@example.com")

    @Test
    fun `인증과 재설정 메일의 기본 문구를 유지한다`() {
        val values = mapOf("code" to "123456", "minutes" to "10")
        val verification = factory.create(NotificationType.EMAIL_VERIFICATION, recipient, values)
        val reset = factory.create(NotificationType.PASSWORD_RESET, recipient, values)

        assertThat(verification.subject).isEqualTo("[쉬운 글] 이메일 인증 코드")
        assertThat(verification.textBody).isEqualTo("인증 코드: 123456\n\n이 코드는 발급 시점으로부터 10분간 유효합니다.")
        assertThat(reset.subject).isEqualTo("[쉬운 글] 비밀번호 재설정 코드")
        assertThat(reset.textBody).isEqualTo("재설정 코드: 123456\n\n이 코드는 발급 시점으로부터 10분간 유효합니다.")
    }

    @Test
    fun `변환 완료 메일은 제목의 플레이스홀더를 재해석하지 않는다`() {
        val message =
            factory.create(
                NotificationType.CONVERSION_COMPLETED,
                recipient,
                mapOf("title" to "문서 {url}", "url" to "https://example.com/conversions/1"),
            )

        assertThat(message.to).isEqualTo(recipient)
        assertThat(message.subject).isEqualTo("[쉬운 글] 변환이 완료됐습니다")
        assertThat(message.textBody)
            .isEqualTo("\"문서 {url}\" 문서 변환이 완료됐습니다.\n\n결과 확인: https://example.com/conversions/1\n")
    }

    @Test
    fun `템플릿에 필요한 값이 없으면 거절한다`() {
        assertThatThrownBy { factory.create(NotificationType.EMAIL_VERIFICATION, recipient) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `비밀번호 생성과 변경 알림의 기본 문구를 유지한다`() {
        val created = factory.create(NotificationType.PASSWORD_CREATED, recipient)
        val changed = factory.create(NotificationType.PASSWORD_CHANGED, recipient)

        assertThat(created.subject).isEqualTo("[쉬운 글] 비밀번호가 만들어졌습니다")
        assertThat(created.textBody)
            .isEqualTo("방금 이 계정에 비밀번호가 만들어졌습니다. 본인이 한 일이 아니라면 즉시 비밀번호를 재설정해 주세요.")
        assertThat(changed.subject).isEqualTo("[쉬운 글] 비밀번호가 바뀌었습니다")
        assertThat(changed.textBody)
            .isEqualTo("방금 이 계정의 비밀번호가 바뀌었습니다. 본인이 한 일이 아니라면 즉시 비밀번호를 다시 재설정해 주세요.")
    }

    @Test
    fun `외부 파일 설정을 바인딩해 메일에 반영한다`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("notifications.properties")
        val templates = defaultTemplates()
        templates.setProperty("email-verification.subject", "맞춤 인증 메일")
        templates.setProperty("email-verification.body", "코드 {code}, 유효 시간 {minutes}분")
        Files.newBufferedWriter(path, Charsets.UTF_8).use { templates.store(it, null) }

        ApplicationContextRunner()
            .withUserConfiguration(NotificationMailConfiguration::class.java)
            .withPropertyValues("easydoc.mail.templates-location=${path.toUri()}")
            .run { context ->
                val configured = context.getBean(kr.easydoc.application.mail.NotificationMailFactory::class.java)
                val message =
                    configured.create(
                        NotificationType.EMAIL_VERIFICATION,
                        recipient,
                        mapOf(
                            "code" to "654321",
                            "minutes" to "7",
                        ),
                    )
                assertThat(message.subject).isEqualTo("맞춤 인증 메일")
                assertThat(message.textBody).isEqualTo("코드 654321, 유효 시간 7분")
            }
    }

    @Test
    fun `누락된 템플릿과 잘못된 플레이스홀더는 조립 시 거절한다`() {
        val missing = defaultTemplates().apply { remove("password-created.subject") }
        assertThatThrownBy { TemplateNotificationMailFactory(missing) }.isInstanceOf(IllegalStateException::class.java)

        for (body in listOf("인증 코드 {code}", "인증 코드 {code}, {minutes}분, {unknown}")) {
            val invalid = defaultTemplates().apply { setProperty("email-verification.body", body) }
            assertThatThrownBy {
                TemplateNotificationMailFactory(
                    invalid,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        val sensitiveSubject = defaultTemplates().apply { setProperty("email-verification.subject", "코드 {code}") }
        assertThatThrownBy {
            TemplateNotificationMailFactory(sensitiveSubject)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun defaultTemplates(): Properties =
        Properties().apply {
            DefaultResourceLoader()
                .getResource("classpath:mail/notifications.properties")
                .inputStream
                .bufferedReader(Charsets.UTF_8)
                .use(::load)
        }
}

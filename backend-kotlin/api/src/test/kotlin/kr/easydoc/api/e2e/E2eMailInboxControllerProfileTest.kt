package kr.easydoc.api.e2e

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailInbox
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.infrastructure.mail.FakeMailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ContextConsumer
import org.springframework.http.HttpStatus
import java.util.function.Supplier

/**
 * `e2e` profile 게이트 — 이 컨트롤러(`/__e2e/mail/latest`)는 **제품 API 가 아니다.**
 * `api`/`local`/prod 컨텍스트에는 아예 없어야 하고, `e2e` profile 이 켜진 컨텍스트에서만
 * 조립돼 `FakeMailSender` 가 기록한 메일을 읽어야 한다. `CryptoProfileExemptionTest` 와
 * 같은 가벼운 `ApplicationContextRunner` 형태를 쓴다 — DB·전체 기동 없이 profile 조건만 잰다.
 */
class E2eMailInboxControllerProfileTest {
    @Test
    @DisplayName("e2e profile 이 없으면 컨트롤러 빈이 아예 없다 — api/local/prod 에 존재하면 안 된다")
    fun `프로필 없이는 조립되지 않는다`() {
        listOf(null, "api", "api,local", "worker").forEach { profile ->
            runner(profile)
                .run(
                    ContextConsumer { context: AssertableApplicationContext ->
                        assertThat(context)
                            .describedAs("profile=%s 인데 E2eMailInboxController 가 조립됐다", profile ?: "(미지정)")
                            .doesNotHaveBean(E2eMailInboxController::class.java)
                    },
                )
        }
    }

    @Test
    @DisplayName("e2e profile 이 켜지면 컨트롤러가 조립되고 FakeMailSender 가 기록한 메일을 읽는다")
    fun `e2e profile 에서는 조립되고 실제로 읽는다`() {
        val fakeMailSender = FakeMailSender()
        fakeMailSender.send(
            OutboundMail(EmailAddress.of("e2e-profile-test@example.test"), SUBJECT, BODY),
        )

        runner("api,local,e2e", fakeMailSender)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasNotFailed()
                    val controller = context.getBean(E2eMailInboxController::class.java)

                    val found = controller.latest("e2e-profile-test@example.test")
                    assertThat(found.statusCode).isEqualTo(HttpStatus.OK)
                    assertThat(found.body?.subject).isEqualTo(SUBJECT)
                    assertThat(found.body?.textBody).isEqualTo(BODY)

                    val missing = controller.latest("nobody@example.test")
                    assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
                },
            )
    }

    private fun runner(
        activeProfile: String?,
        mailInbox: MailInbox = FakeMailSender(),
    ): ApplicationContextRunner {
        val base =
            ApplicationContextRunner()
                .withUserConfiguration(E2eMailInboxController::class.java)
                .withBean(MailInbox::class.java, Supplier { mailInbox })
        return if (activeProfile == null) base else base.withPropertyValues("spring.profiles.active=$activeProfile")
    }

    private companion object {
        const val SUBJECT = "[쉬운 글] 이메일 인증 코드"
        const val BODY = "인증 코드: 123456\n\n이 코드는 발급 시점으로부터 10분간 유효합니다."
    }
}

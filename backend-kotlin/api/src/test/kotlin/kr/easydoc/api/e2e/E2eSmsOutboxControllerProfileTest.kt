package kr.easydoc.api.e2e

import kr.easydoc.application.auth.PhoneVerificationSmsOutbox
import kr.easydoc.infrastructure.sms.FakeSmsSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ContextConsumer
import org.springframework.http.HttpStatus
import java.util.function.Supplier

/**
 * `e2e` profile 게이트 — 이 컨트롤러(`/__e2e/sms/latest`)는 **제품 API 가 아니다.**
 * `api`/`local`/prod 컨텍스트에는 아예 없어야 하고, `e2e` profile 이 켜진 컨텍스트에서만
 * 조립돼 `FakeSmsSender` 가 기록한 인증 코드를 읽어야 한다(`E2eMailInboxControllerProfileTest`
 * 와 같은 자리 — `ApplicationContextRunner` 로 DB·전체 기동 없이 profile 조건만 잰다).
 */
class E2eSmsOutboxControllerProfileTest {
    @Test
    @DisplayName("e2e profile 이 없으면 컨트롤러 빈이 아예 없다 — api/local/prod 에 존재하면 안 된다")
    fun `프로필 없이는 조립되지 않는다`() {
        listOf(null, "api", "api,local", "worker").forEach { profile ->
            runner(profile)
                .run(
                    ContextConsumer { context: AssertableApplicationContext ->
                        assertThat(context)
                            .describedAs("profile=%s 인데 E2eSmsOutboxController 가 조립됐다", profile ?: "(미지정)")
                            .doesNotHaveBean(E2eSmsOutboxController::class.java)
                    },
                )
        }
    }

    @Test
    @DisplayName("e2e profile 이 켜지면 컨트롤러가 조립되고 FakeSmsSender 가 기록한 코드를 읽는다")
    fun `e2e profile 에서는 조립되고 실제로 읽는다`() {
        val fakeSmsSender = FakeSmsSender()
        fakeSmsSender.send("01012345678", CODE, VALID_MINUTES)

        runner("api,local,e2e", fakeSmsSender)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasNotFailed()
                    val controller = context.getBean(E2eSmsOutboxController::class.java)

                    val found = controller.latest("01012345678")
                    assertThat(found.statusCode).isEqualTo(HttpStatus.OK)
                    assertThat(found.body?.code).isEqualTo(CODE)
                    assertThat(found.body?.validMinutes).isEqualTo(VALID_MINUTES)

                    val missing = controller.latest("01099999999")
                    assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
                },
            )
    }

    private fun runner(
        activeProfile: String?,
        outbox: PhoneVerificationSmsOutbox = FakeSmsSender(),
    ): ApplicationContextRunner {
        val base =
            ApplicationContextRunner()
                .withUserConfiguration(E2eSmsOutboxController::class.java)
                .withBean(PhoneVerificationSmsOutbox::class.java, Supplier { outbox })
        return if (activeProfile == null) base else base.withPropertyValues("spring.profiles.active=$activeProfile")
    }

    private companion object {
        const val CODE = "123456"
        const val VALID_MINUTES = 5L
    }
}

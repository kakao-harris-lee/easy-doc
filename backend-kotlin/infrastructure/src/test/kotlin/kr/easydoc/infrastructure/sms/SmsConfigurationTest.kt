package kr.easydoc.infrastructure.sms

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * `easydoc.sms.provider` 선택 — `PaymentConfigurationTest` 와 같은 자리: Spring 컨텍스트
 * 없이 `@Bean` 메서드를 직접 부른다. 프로젝트 `CLAUDE.md` 「새 provider는 adapter contract
 * test와 설정 선택 테스트를 먼저」를 SENS 어댑터에 채운다(리뷰 MEDIUM 지적).
 */
class SmsConfigurationTest {
    private val configuration = SmsConfiguration()

    @Test
    fun `provider가 fake면 실제 SMS를 보내지 않는 대역이 등록된다`() {
        val sender = configuration.phoneVerificationSmsSender(SmsProperties(provider = "fake"))

        assertThat(sender).isInstanceOf(FakeSmsSender::class.java)
        // 대역은 예외를 던지지 않고 메모리에 기록한다 — 발송 자체는 부작용이 없다.
        sender.send("01012345678", "123456", 5)
    }

    @Test
    fun `e2e profile 전용 outbox는 fake 발송기를 그대로 되읽는 통로로 조립된다`() {
        val sender = configuration.phoneVerificationSmsSender(SmsProperties(provider = "fake"))

        val outbox = configuration.phoneVerificationSmsOutbox(sender)
        sender.send("01012345678", "123456", 5)

        assertThat(outbox.latestTo("01012345678")?.code).isEqualTo("123456")
    }

    @Test
    fun `e2e profile 전용 outbox는 outbox를 구현하지 않는 발송기면 ConfigurationException`() {
        val sender =
            configuration.phoneVerificationSmsSender(
                SmsProperties(
                    provider = "sens",
                    serviceId = "svc",
                    accessKey = "access",
                    secretKey = Secret("secret"),
                    from = "01000000000",
                ),
            )

        assertThatThrownBy { configuration.phoneVerificationSmsOutbox(sender) }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    fun `provider가 sens인데 필수 설정이 비어 있으면 ConfigurationException이다`() {
        assertThatThrownBy {
            configuration.phoneVerificationSmsSender(SmsProperties(provider = "sens"))
        }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    fun `provider가 sens이고 설정이 갖춰지면 SensSmsSender가 등록된다`() {
        val sender =
            configuration.phoneVerificationSmsSender(
                SmsProperties(
                    provider = "sens",
                    serviceId = "svc",
                    accessKey = "access",
                    secretKey = Secret("secret"),
                    from = "01000000000",
                ),
            )

        assertThat(sender).isInstanceOf(SensSmsSender::class.java)
    }

    @Test
    fun `알 수 없는 provider는 ConfigurationException이다`() {
        assertThatThrownBy {
            configuration.phoneVerificationSmsSender(SmsProperties(provider = "twilio"))
        }.isInstanceOf(ConfigurationException::class.java)
    }
}

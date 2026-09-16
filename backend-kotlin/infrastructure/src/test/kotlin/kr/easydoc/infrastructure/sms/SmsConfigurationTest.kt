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

        assertThat(sender).isNotInstanceOf(SensSmsSender::class.java)
        // 대역은 예외 없이 조용히 아무 것도 하지 않는다.
        sender.send("01012345678", "123456", 5)
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

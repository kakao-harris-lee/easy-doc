package kr.easydoc.infrastructure.auth

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.sms.SmsProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [AuthConfiguration.resolvePhoneVerificationPepper] 의 기동 자기점검 —
 * `SignupGrantRecordPurgeConfigurationTest`·`PaymentConfigurationTest` 와 같은 자리: Spring
 * 컨텍스트 없이 `@Bean` 이 쓰는 함수를 평범한 함수처럼 직접 부른다.
 *
 * 리뷰 MEDIUM 지적: pepper 필수 검사가 `trialCredits > 0` 일 때만 돌았다 — `sens` 로 실제
 * 번호를 발송하면 체험 크레딧을 안 쓰는 배포에서도 그 번호가 `users.pending_phone_fingerprint`
 * 에 지문으로 남으므로, `trialCredits` 값과 무관하게 `sens` 는 pepper 를 요구해야 한다.
 */
class AuthConfigurationTest {
    private val configuration = AuthConfiguration()

    @Test
    @DisplayName("sens + pepper 없음은 trialCredits=0 이어도 ConfigurationException 이다")
    fun `sens 는 체험 크레딧이 0이어도 pepper 를 요구한다`() {
        assertThatThrownBy {
            configuration.resolvePhoneVerificationPepper(
                SmsProperties(provider = "sens"),
                PhoneVerificationProperties(trialCredits = 0),
            )
        }.isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("fake + pepper 없음은 리터럴 대체값으로 기동된다")
    fun `fake 는 pepper 가 없어도 대체값으로 기동된다`() {
        val pepper =
            configuration.resolvePhoneVerificationPepper(
                SmsProperties(provider = "fake"),
                PhoneVerificationProperties(),
            )

        assertThat(pepper.isBlank()).isFalse()
    }

    @Test
    @DisplayName("sens + pepper 설정은 그 pepper 그대로 기동된다")
    fun `sens 는 pepper 가 있으면 통과한다`() {
        val configured = Secret("real-pepper")

        val pepper =
            configuration.resolvePhoneVerificationPepper(
                SmsProperties(provider = "sens"),
                PhoneVerificationProperties(fingerprintPepper = configured),
            )

        assertThat(pepper).isEqualTo(configured)
    }
}

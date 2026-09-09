package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.application.credit.NoopSignupGrantLedger
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [CreditAccountConfiguration] 의 가입 부여 pepper 기동 자기점검(가입 크레딧 후속 §7 결정 3) —
 * `CryptoStartupVerificationTest` 와 같은 자리: Spring 컨텍스트 없이 `@Bean` 메서드를 평범한
 * 함수처럼 직접 부른다.
 */
class CreditAccountConfigurationTest {
    private val configuration = CreditAccountConfiguration()

    @Test
    @DisplayName("AC7 전반 — signupGrant > 0 인데 pepper 가 없으면 기동이 실패한다")
    fun `pepper 없이 가입 부여를 켜면 기동이 실패한다`() {
        val properties = CreditsProperties(enforced = false, signupGrant = 50, signupGrantPepper = Secret.EMPTY)

        assertThatThrownBy {
            configuration.creditAccountService(NoopCreditAccountRepository, NoopSignupGrantLedger, properties)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("signup-grant-pepper")
    }

    @Test
    @DisplayName("공백만 있는 pepper 도 없는 것과 같다 — 기동이 실패한다 (회귀 방지)")
    fun `공백만 있는 pepper 는 기동을 막는다`() {
        val properties = CreditsProperties(enforced = false, signupGrant = 50, signupGrantPepper = Secret("   "))

        // 지금은 Secret.isBlank() 가 String.isBlank() 를 그대로 써서 이미 막힌다 — 이
        // 테스트는 그 사실 자체가 아니라, 나중에 누군가 isBlank() 를 isEmpty() 로 바꿔도
        // 이 갈래가 계속 실패로 남는지(회귀 방지)를 고정한다. 통과하면 고정된 약한 키로
        // HMAC 을 돌리면서 기동만 조용히 성공하는 fail-open 이 된다.
        assertThatThrownBy {
            configuration.creditAccountService(NoopCreditAccountRepository, NoopSignupGrantLedger, properties)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("signup-grant-pepper")
    }

    @Test
    @DisplayName("AC7 후반 — signupGrant = 0 이면 pepper 없이도 기동된다")
    fun `가입 부여가 꺼져 있으면 pepper 없이도 기동된다`() {
        val properties = CreditsProperties(enforced = false, signupGrant = 0, signupGrantPepper = Secret.EMPTY)

        assertThatCode {
            configuration.creditAccountService(NoopCreditAccountRepository, NoopSignupGrantLedger, properties)
        }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("signupGrant > 0 이고 pepper 도 있으면 기동된다")
    fun `pepper 가 있으면 가입 부여를 켜도 기동된다`() {
        val properties =
            CreditsProperties(enforced = false, signupGrant = 50, signupGrantPepper = Secret("real-pepper"))

        assertThatCode {
            configuration.creditAccountService(NoopCreditAccountRepository, NoopSignupGrantLedger, properties)
        }.doesNotThrowAnyException()
    }
}

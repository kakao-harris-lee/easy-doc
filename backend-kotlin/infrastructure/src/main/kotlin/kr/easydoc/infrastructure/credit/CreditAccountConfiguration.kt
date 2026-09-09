package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.SignupGrantEmailHasher
import kr.easydoc.application.credit.SignupGrantLedger
import kr.easydoc.core.exceptions.ConfigurationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 크레딧 계정 유스케이스 조립(C1) — 가입 부여 중복 방지 원장(V20)과 기동 자기점검을
 * 포함한다(가입 크레딧 후속 §7 결정 3, `CryptoConfiguration` 과 같은 자리·이유:
 * 설정값과 구현 클래스를 함께 볼 수 있는 모듈이 `infrastructure` 하나뿐이다).
 */
@Configuration(proxyBeanMethods = false)
class CreditAccountConfiguration {
    @Bean
    fun creditAccountRepository(jdbcClient: JdbcClient): CreditAccountRepository =
        JdbcCreditAccountRepository(jdbcClient)

    @Bean
    fun signupGrantLedger(jdbcClient: JdbcClient): SignupGrantLedger = JdbcSignupGrantLedger(jdbcClient)

    @Bean
    fun creditAccountService(
        repository: CreditAccountRepository,
        signupGrantLedger: SignupGrantLedger,
        properties: CreditsProperties,
    ): CreditAccountService {
        verifyPepperConfigured(properties)
        return CreditAccountService(
            repository = repository,
            enforced = properties.enforced,
            signupGrant = properties.signupGrant,
            signupGrantLedger = signupGrantLedger,
            emailHasher = SignupGrantEmailHasher(properties.signupGrantPepper),
        )
    }

    /**
     * 기동 자기점검 — `signupGrant > 0` 인데 pepper 가 없으면 앱을 띄우지 않는다
     * (가입 크레딧 후속 §7 결정 3, `CryptoConfiguration.verify` 와 같은 자리). `signupGrant`
     * 를 쓰지 않는 배포(`0`)는 이 값 없이도 그대로 뜬다 — **조용히 부여해 버리는
     * fail-open 을 만들지 않는다**는 것이 이 검사의 유일한 목적이므로, 부여 자체를 쓰지
     * 않는 배포까지 막을 이유가 없다.
     */
    private fun verifyPepperConfigured(properties: CreditsProperties) {
        if (properties.signupGrant > 0 && properties.signupGrantPepper.isBlank()) {
            throw ConfigurationException(
                "easydoc.credits.signup-grant 가 ${properties.signupGrant} 인데 " +
                    "easydoc.credits.signup-grant-pepper(EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER)가 " +
                    "비어 있다. 이 값 없이 가입 부여를 켜면 가입 크레딧 중복 방지 원장의 이메일 " +
                    "해시를 만들 수 없다 — 앱을 띄우지 않는다. 근거: 가입 크레딧 후속 " +
                    "(docs/plans/2026-09-09-account-deletion.md §7 결정 3).",
            )
        }
    }
}

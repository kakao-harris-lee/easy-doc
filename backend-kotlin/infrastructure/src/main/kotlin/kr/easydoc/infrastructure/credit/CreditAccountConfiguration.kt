package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/** 크레딧 계정 유스케이스 조립(C1). */
@Configuration(proxyBeanMethods = false)
class CreditAccountConfiguration {
    @Bean
    fun creditAccountRepository(jdbcClient: JdbcClient): CreditAccountRepository =
        JdbcCreditAccountRepository(jdbcClient)

    @Bean
    fun creditAccountService(
        repository: CreditAccountRepository,
        properties: CreditsProperties,
    ): CreditAccountService =
        CreditAccountService(
            repository = repository,
            enforced = properties.enforced,
            signupGrant = properties.signupGrant,
        )
}

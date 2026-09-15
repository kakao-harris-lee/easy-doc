package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** `credit-grant` 프로필에서 크레딧 조정 명령을 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(CREDIT_GRANT_PROFILE)
class CreditGrantConfiguration {
    @Bean
    fun creditGrantRunner(
        service: CreditAccountService,
        repository: CreditAccountRepository,
        transactionRunner: TransactionRunner,
        users: UserRepository,
        accessLog: RecordPersonalDataAccess,
    ): CreditGrantRunner = CreditGrantRunner(service, repository, transactionRunner, users, accessLog)
}

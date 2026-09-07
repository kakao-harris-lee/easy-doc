package kr.easydoc.api.credit

import kr.easydoc.api.CREDIT_GRANT_PROFILE
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * `credit-grant` profile 전용 조립(C2). [CreditAccountService]·[CreditAccountRepository]·
 * [TransactionRunner] 는 `infrastructure`의 `CreditAccountConfiguration`·`AuthConfiguration`이
 * 프로필과 무관하게 이미 조립한 빈이다(`UsageReportConfiguration` KDoc과 같은 이유 — `api`는
 * `infrastructure`를 `runtimeOnly`로만 의존한다). 이 클래스는 그 빈들을 받아 CLI 실행부
 * ([CreditGrantRunner])만 배선한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(CREDIT_GRANT_PROFILE)
class CreditGrantConfiguration {
    @Bean
    fun creditGrantRunner(
        service: CreditAccountService,
        repository: CreditAccountRepository,
        transactionRunner: TransactionRunner,
    ): CreditGrantRunner = CreditGrantRunner(service, repository, transactionRunner)
}

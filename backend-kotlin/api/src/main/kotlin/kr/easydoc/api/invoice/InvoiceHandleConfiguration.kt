package kr.easydoc.api.invoice

import kr.easydoc.api.INVOICE_HANDLE_PROFILE
import kr.easydoc.application.invoice.InvoiceRequestService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * `invoice-handle` profile 전용 조립. [InvoiceRequestService] 는 `infrastructure`의
 * `InvoiceRequestConfiguration`이 프로필과 무관하게 이미 조립한 빈이다
 * (`CreditGrantConfiguration` KDoc과 같은 이유 — `api`는 `infrastructure`를 `runtimeOnly`로만
 * 의존한다). 이 클래스는 그 빈을 받아 CLI 실행부([InvoiceHandleRunner])만 배선한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(INVOICE_HANDLE_PROFILE)
class InvoiceHandleConfiguration {
    @Bean
    fun invoiceHandleRunner(service: InvoiceRequestService): InvoiceHandleRunner = InvoiceHandleRunner(service)
}

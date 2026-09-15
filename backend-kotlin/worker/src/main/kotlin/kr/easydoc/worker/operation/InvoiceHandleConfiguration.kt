package kr.easydoc.worker.operation

import kr.easydoc.application.invoice.InvoiceRequestService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** `invoice-handle` 프로필에서 세금계산서 처리 명령을 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(INVOICE_HANDLE_PROFILE)
class InvoiceHandleConfiguration {
    @Bean
    fun invoiceHandleRunner(service: InvoiceRequestService): InvoiceHandleRunner = InvoiceHandleRunner(service)
}

package kr.easydoc.infrastructure.invoice

import kr.easydoc.application.invoice.InvoiceRequestRepository
import kr.easydoc.application.invoice.InvoiceRequestService
import kr.easydoc.application.mail.MailSender
import kr.easydoc.infrastructure.billing.BillingProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/** 세금계산서 요청 유스케이스 조립. */
@Configuration(proxyBeanMethods = false)
class InvoiceRequestConfiguration {
    @Bean
    fun invoiceRequestRepository(jdbcClient: JdbcClient): InvoiceRequestRepository =
        JdbcInvoiceRequestRepository(jdbcClient)

    @Bean
    fun invoiceRequestService(
        repository: InvoiceRequestRepository,
        mailSender: MailSender,
        billingProperties: BillingProperties,
    ): InvoiceRequestService =
        InvoiceRequestService(
            repository = repository,
            mail = mailSender,
            operatorEmail = billingProperties.operatorEmail,
            clock = Clock.systemUTC(),
        )
}

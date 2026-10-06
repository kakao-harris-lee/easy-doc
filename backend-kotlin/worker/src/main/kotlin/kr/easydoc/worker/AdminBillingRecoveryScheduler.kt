package kr.easydoc.worker

import kr.easydoc.application.admin.AdminBillingService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile(WORKER_PROFILE)
class AdminBillingRecoveryScheduler(private val billing: AdminBillingService) {
    @Scheduled(fixedDelayString = "\${easydoc.payment.recovery-delay-ms:60000}")
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        try {
            billing.recover()
        } catch (_: RuntimeException) {
            LoggerFactory
                .getLogger(
                    javaClass,
                ).error("Admin billing recovery deferred; durable operations remain pending")
        }
    }
}

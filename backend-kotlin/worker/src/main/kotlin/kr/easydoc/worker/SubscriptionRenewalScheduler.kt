package kr.easydoc.worker

import kr.easydoc.application.subscription.SubscriptionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SubscriptionRenewalScheduler(
    private val subscriptions: SubscriptionService,
    private val toss: kr.easydoc.application.subscription.TossBillingService,
) {
    @Scheduled(fixedDelayString = "\${easydoc.payment.renewal-delay-ms:60000}")
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        try {
            subscriptions.renewDue()
            toss.runDue()
        } catch (_: RuntimeException) {
            LoggerFactory.getLogger(javaClass).error("Subscription renewal failed; pending cycles will retry")
        }
    }
}

package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.TossBillingCallbackStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Frequent cleanup for staged credentials and completed resume metadata. */
@Component
@Profile("worker")
class TossBillingCallbackCleanupScheduler(
    private val store: TossBillingCallbackStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${easydoc.payment.callback-cleanup-delay-ms:60000}")
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        try {
            store.purge(clock.instant())
        } catch (_: RuntimeException) {
            LoggerFactory.getLogger(javaClass).error("Billing callback cleanup failed; expired rows will retry")
        }
    }
}

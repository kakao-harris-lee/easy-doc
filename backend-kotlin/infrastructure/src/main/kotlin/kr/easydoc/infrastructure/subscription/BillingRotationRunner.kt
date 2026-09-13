package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.TossBillingStore
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("rotate-keys")
class BillingRotationRunner(private val store: TossBillingStore) :
    ApplicationRunner,
    ExitCodeGenerator {
    private var code = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        try {
            (store as JdbcTossBillingStore).rotateSecrets()
        } catch (_: RuntimeException) {
            code = 1
            LoggerFactory.getLogger(javaClass).error("Billing envelope rotation failed; retain old keys and retry")
        }
    }

    override fun getExitCode(): Int = code
}

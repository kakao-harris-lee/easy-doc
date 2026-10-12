package kr.easydoc.infrastructure.subscription

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Rotates callback ciphertext separately from the existing session/order envelope runner. */
@Component
@Profile("rotate-keys")
class TossBillingCallbackRotationRunner(
    private val store: kr.easydoc.application.subscription.TossBillingCallbackStore,
) : ApplicationRunner,
    ExitCodeGenerator {
    private var code = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        try {
            (store as JdbcTossBillingCallbackStore).rotateSecrets()
        } catch (_: RuntimeException) {
            code = 1
            LoggerFactory
                .getLogger(
                    javaClass,
                ).error("Billing callback envelope rotation failed; retain old keys and retry")
        }
    }

    override fun getExitCode(): Int = code
}

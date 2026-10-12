package kr.easydoc.infrastructure.retention

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** rotate-keys one-shot에서 거래 원장과 심사 사유의 암호화 envelope를 함께 회전한다. */
@Component
@Profile("rotate-keys")
class RetainedTransactionRotationRunner(private val repository: JdbcRetainedTransactionRepository) :
    ApplicationRunner,
    ExitCodeGenerator {
    private var code = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        try {
            repository.rotateSecrets()
        } catch (_: RuntimeException) {
            code = 1
            LoggerFactory
                .getLogger(javaClass)
                .error("Retained transaction envelope rotation failed; retain old keys and retry")
        }
    }

    override fun getExitCode(): Int = code
}

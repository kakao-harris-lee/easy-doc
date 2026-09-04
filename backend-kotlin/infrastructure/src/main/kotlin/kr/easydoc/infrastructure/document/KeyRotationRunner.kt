package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.KeyRotationBatch
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * `rotate-keys` profile 의 실행부 — 컨텍스트가 뜬 뒤 [KeyRotationBatch] 를 한 번 돌리고
 * 끝낸다. `ApiApplication.main` 이 [ExitCodeGenerator] 를 읽어 `SpringApplication.exit` 로
 * 종료 코드를 낸다 — `migrate` profile 이 Flyway 뒤 컨텍스트를 닫는 것과 같은 자리다.
 *
 * **예외를 밖으로 던지지 않는다.** 던지면 `SpringApplication.run` 이 실패 분석을 거쳐
 * 스택트레이스를 그대로 표준 오류로 남긴다 — 그 메시지에 무엇이 실릴지 이 클래스가 보장할
 * 수 없다. 여기서 잡아 **개수만** 남기는 것이 「행 id 를 로그에 남기지 않는다」는 운영
 * 진입점 요구사항을 실패 경로에서도 지킨다.
 */
@Component
@Profile(ROTATE_KEYS_PROFILE)
class KeyRotationRunner(private val batch: KeyRotationBatch) :
    ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(KeyRotationRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                batch.run()
                SUCCESS
            } catch (failure: RuntimeException) {
                // 메시지만 남긴다 — 도메인 예외는 개수만 말하고 행 식별자·본문을 담지 않는다.
                log.error("키 회전 배치가 실패했다: {}", failure.message)
                FAILURE
            }
    }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
    }
}

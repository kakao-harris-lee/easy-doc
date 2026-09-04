package kr.easydoc.api

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/** API 실행 진입점. */
@SpringBootApplication(scanBasePackages = ["kr.easydoc"])
@ConfigurationPropertiesScan("kr.easydoc")
class ApiApplication

/** `migrate` profile 이름. 스키마만 적용하고 종료하는 실행 모드다. */
internal const val MIGRATE_PROFILE = "migrate"

/**
 * `rotate-keys` profile 이름. 낡은 세대로 봉인된 행을 현재 쓰기 세대로 재봉인하고 종료하는
 * 실행 모드다(backlog §1.1 「키 회전에 운영 진입점이 없음」).
 *
 * `MIGRATE_PROFILE` 과 같은 이유로 `infrastructure` 쪽에도 같은 이름·같은 값을 따로 든다
 * (`kr.easydoc.infrastructure.document.ROTATE_KEYS_PROFILE`) — `api` 는 `infrastructure` 를
 * `runtimeOnly` 로만 의존해 그 모듈의 상수를 컴파일 시점에 보지 못한다.
 */
internal const val ROTATE_KEYS_PROFILE = "rotate-keys"

/**
 * `e2e` profile 이름. Playwright e2e 스택(`compose.e2e.yml`)이 `api,local` 곁에 얹어
 * 켠다 — 이 profile 이 있을 때만 [kr.easydoc.api.e2e.E2eMailInboxController] 가 조립된다.
 * 운영 profile(`api`·`local`·prod)에는 이 값이 없으므로 그 컨트롤러도 없다.
 */
internal const val E2E_PROFILE = "e2e"

fun main(args: Array<String>) {
    val context = runApplication<ApiApplication>(*args)
    val profiles = context.environment.activeProfiles.toSet()

    // Flyway 는 컨텍스트 초기화 중에 이미 돌았으므로 여기서 닫고 종료한다.
    if (profiles.contains(MIGRATE_PROFILE)) {
        context.close()
    }

    // 회전 배치(KeyRotationRunner)는 ApplicationRunner 로 컨텍스트 초기화 중에 이미 돌았다.
    // SpringApplication.exit 가 컨텍스트의 ExitCodeGenerator 빈을 읽어 종료 코드를 내고
    // 컨텍스트를 닫는다 — 실패(배치 예외·미완주)면 0이 아닌 코드로 프로세스를 끝낸다.
    if (profiles.contains(ROTATE_KEYS_PROFILE)) {
        val exitCode = SpringApplication.exit(context)
        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }
}

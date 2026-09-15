package kr.easydoc.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** API 실행 진입점. */
@SpringBootApplication(scanBasePackages = ["kr.easydoc.api", "kr.easydoc.infrastructure"])
@ConfigurationPropertiesScan(basePackages = ["kr.easydoc.api", "kr.easydoc.infrastructure"])
class ApiApplication

/** `migrate` profile 이름. 스키마만 적용하고 종료하는 실행 모드다. */
internal const val MIGRATE_PROFILE = "migrate"

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
}

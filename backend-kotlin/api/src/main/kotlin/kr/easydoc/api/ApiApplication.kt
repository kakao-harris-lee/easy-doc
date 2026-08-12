package kr.easydoc.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * API 실행 진입점.
 *
 * `worker` 를 의존하지 않는다 — 두 진입점은 profile(`api`, `worker`, `migrate`)로만
 * 갈리고 서로를 모른다(계획 §3.2).
 *
 * `scanBasePackages` 를 `kr.easydoc` 로 넓히는 이유: `infrastructure` 의 `@Configuration`
 * 은 `kr.easydoc.infrastructure` 에 있어 기본 스캔 범위(`kr.easydoc.api`)에 들어오지
 * 않는다. 스캔이 닿지 않으면 Flyway baseline 가드가 조용히 등록되지 않고, 그 상태로도
 * 앱은 뜬다 — 조용한 실패라 더 위험하다.
 */
@SpringBootApplication(scanBasePackages = ["kr.easydoc"])
@ConfigurationPropertiesScan("kr.easydoc")
class ApiApplication

/** `migrate` profile 이름. 스키마만 적용하고 종료하는 실행 모드다. */
private const val MIGRATE_PROFILE = "migrate"

fun main(args: Array<String>) {
    val context = runApplication<ApiApplication>(*args)

    // migrate profile 은 "스키마를 적용하고 끝나는" 일회성 실행이다. Flyway 는 컨텍스트
    // 초기화 중에 이미 돌았으므로 여기서 컨텍스트를 닫고 종료 코드를 확정한다.
    // 명시적으로 닫지 않으면 컨테이너가 살아 있는 채로 남을 수 있고, compose 의
    // `service_completed_successfully` 조건이 영원히 충족되지 않는다.
    if (context.environment.activeProfiles.contains(MIGRATE_PROFILE)) {
        context.close()
    }
}

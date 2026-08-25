package kr.easydoc.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** API 실행 진입점. */
@SpringBootApplication(scanBasePackages = ["kr.easydoc"])
@ConfigurationPropertiesScan("kr.easydoc")
class ApiApplication

/** `migrate` profile 이름. 스키마만 적용하고 종료하는 실행 모드다. */
internal const val MIGRATE_PROFILE = "migrate"

fun main(args: Array<String>) {
    val context = runApplication<ApiApplication>(*args)

    // Flyway 는 컨텍스트 초기화 중에 이미 돌았으므로 여기서 닫고 종료한다.
    if (context.environment.activeProfiles.contains(MIGRATE_PROFILE)) {
        context.close()
    }
}

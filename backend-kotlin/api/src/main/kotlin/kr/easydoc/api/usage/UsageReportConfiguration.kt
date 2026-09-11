package kr.easydoc.api.usage

import kr.easydoc.api.USAGE_REPORT_PROFILE
import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.usage.UsageReportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * `usage-report` profile 전용 조립. [UsageReportService] 는 `infrastructure`의
 * `UsageConfiguration`이 프로필과 무관하게 이미 조립한 빈이다(`UsageConfiguration` KDoc —
 * `api`가 `infrastructure`를 `runtimeOnly`로만 의존해 `JdbcUsageReportRepository`를
 * 컴파일 시점에 볼 수 없다). [RecordPersonalDataAccess]도 같은 이유로 `infrastructure`의
 * `AccessLogConfiguration`이, [UserRepository]도 `AuthConfiguration`이 이미 조립한
 * 빈이다(`--actor-email` 해석, `CliActor.resolveActorId`). 이 클래스는 그 빈들을 받아 CLI
 * 실행부([UsageReportRunner])만 배선한다 — `KeyRotationConfiguration`이 무거운 배치
 * 객체를 조립하는 것과 달리, 여기서 새로 조립할 것은 실행부 하나뿐이다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(USAGE_REPORT_PROFILE)
class UsageReportConfiguration {
    @Bean
    fun usageReportRunner(
        service: UsageReportService,
        users: UserRepository,
        accessLog: RecordPersonalDataAccess,
    ): UsageReportRunner = UsageReportRunner(service, users, accessLog)
}

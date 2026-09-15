package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.admin.AdminGrantService
import kr.easydoc.application.auth.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** `admin-grant` 프로필에서 관리자 권한 명령을 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile(ADMIN_GRANT_PROFILE)
class AdminGrantConfiguration {
    @Bean
    fun adminGrantRunner(
        service: AdminGrantService,
        users: UserRepository,
        accessLog: RecordPersonalDataAccess,
    ): AdminGrantRunner = AdminGrantRunner(service, users, accessLog)
}

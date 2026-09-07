package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminAccessRepository
import kr.easydoc.application.admin.AdminConversionQueryRepository
import kr.easydoc.application.admin.AdminCreditAdjustmentService
import kr.easydoc.application.admin.AdminGrantService
import kr.easydoc.application.admin.AdminGuard
import kr.easydoc.application.admin.AdminQueryService
import kr.easydoc.application.admin.AdminWorkspaceQueryRepository
import kr.easydoc.application.admin.AnnouncementRepository
import kr.easydoc.application.admin.AnnouncementService
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.invoice.InvoiceRequestRepository
import kr.easydoc.application.usage.UsageQueryService
import kr.easydoc.application.usage.UsageReportService
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 어드민 최소(A1) 조립 — 계획 `docs/plans/2026-09-07-admin-minimum.md` §3 A1.
 *
 * **프로필과 무관하게 상시 조립한다** — [AdminGuard]는 `api`/`local`/prod 컨텍스트가 매
 * 요청 거치는 인터셉터([kr.easydoc.api.admin.AdminAccessInterceptor])가 쓴다(값싼
 * JdbcClient 기반 객체라 `usageQueryService`와 같은 이유로 gate 하지 않는다,
 * `UsageConfiguration` KDoc). `admin-grant` 프로필 전용 실행부(`api`의
 * `AdminGrantConfiguration`)는 여기서 조립한 [AdminGrantService] 빈만 받는다 — `api`가
 * `infrastructure`를 `runtimeOnly`로만 의존해 `JdbcAdminAccessRepository`를 컴파일
 * 시점에 보지 못하기 때문이다(`CreditGrantConfiguration`과 같은 이유).
 */
@Configuration(proxyBeanMethods = false)
class AdminConfiguration {
    @Bean
    fun adminAccessRepository(jdbcClient: JdbcClient): AdminAccessRepository = JdbcAdminAccessRepository(jdbcClient)

    @Bean
    fun adminGuard(repository: AdminAccessRepository): AdminGuard = AdminGuard(repository)

    @Bean
    fun adminGrantService(
        users: UserRepository,
        access: AdminAccessRepository,
    ): AdminGrantService = AdminGrantService(users, access)

    @Bean
    fun adminWorkspaceQueryRepository(jdbcClient: JdbcClient): AdminWorkspaceQueryRepository =
        JdbcAdminWorkspaceQueryRepository(jdbcClient)

    @Bean
    fun adminConversionQueryRepository(jdbcClient: JdbcClient): AdminConversionQueryRepository =
        JdbcAdminConversionQueryRepository(jdbcClient)

    /** 매개변수 수는 협력자의 수다 — `AdminQueryService` 생성자와 같은 근거로 억제한다. */
    @Suppress("LongParameterList")
    @Bean
    fun adminQueryService(
        workspaces: AdminWorkspaceQueryRepository,
        creditAccounts: CreditAccountService,
        usage: UsageQueryService,
        invoiceRequests: InvoiceRequestRepository,
        conversions: AdminConversionQueryRepository,
        usageReport: UsageReportService,
        properties: UsageProperties,
    ): AdminQueryService =
        AdminQueryService(
            workspaces = workspaces,
            creditAccounts = creditAccounts,
            usage = usage,
            invoiceRequests = invoiceRequests,
            conversions = conversions,
            usageReport = usageReport,
            zone = properties.zoneId(),
            clock = Clock.systemUTC(),
        )

    @Bean
    fun adminCreditAdjustmentService(
        repository: CreditAccountRepository,
        service: CreditAccountService,
    ): AdminCreditAdjustmentService = AdminCreditAdjustmentService(repository, service)

    @Bean
    fun announcementRepository(jdbcClient: JdbcClient): AnnouncementRepository = JdbcAnnouncementRepository(jdbcClient)

    @Bean
    fun announcementService(repository: AnnouncementRepository): AnnouncementService =
        AnnouncementService(repository, Clock.systemUTC())
}

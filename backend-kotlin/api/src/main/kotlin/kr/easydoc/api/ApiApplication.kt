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

/**
 * `usage-report` profile 이름. 지난달(기본) 사용자·워크스페이스별 사용량을 CSV 로 쓰고
 * 종료하는 실행 모드다(계획 `docs/plans/2026-09-07-usage-ledger-and-report.md` §3 U3).
 *
 * `ROTATE_KEYS_PROFILE`과 같은 이유로 `infrastructure`가 아니라 `application`이 조립하는
 * [kr.easydoc.application.usage.UsageReportService] 빈만 받는다 — `api`가 `infrastructure`를
 * `runtimeOnly`로만 의존해 `JdbcUsageReportRepository`를 컴파일 시점에 보지 못하기 때문에,
 * 그 어댑터 조립은 `infrastructure`의 `UsageConfiguration`이 상시(프로필 무관) 맡고 이
 * profile 은 `UsageReportConfiguration`(`api`)에서 실행부([kr.easydoc.api.usage.UsageReportRunner])만
 * 배선한다.
 */
internal const val USAGE_REPORT_PROFILE = "usage-report"

/**
 * `credit-grant` profile 이름. 운영자 수동 크레딧 부여·조정 한 건을 반영하고 종료하는 실행
 * 모드다(계획 `docs/plans/2026-09-07-credit-accounts.md` §2 결정 6, C2).
 *
 * `ROTATE_KEYS_PROFILE`·`USAGE_REPORT_PROFILE`과 같은 이유로 `infrastructure`가 아니라
 * `application`이 조립하는 [kr.easydoc.application.credit.CreditAccountService]·
 * [kr.easydoc.application.credit.CreditAccountRepository] 빈만 받는다.
 */
internal const val CREDIT_GRANT_PROFILE = "credit-grant"

/**
 * `invoice-handle` profile 이름. 운영자가 세금계산서 요청 한 건을 처리(발급·거절)하고
 * 종료하는 실행 모드다(계획 `docs/plans/2026-09-07-invoice-requests.md` §2 결정 4).
 *
 * `CREDIT_GRANT_PROFILE`과 같은 이유로 `infrastructure`가 아니라 `application`이 조립하는
 * [kr.easydoc.application.invoice.InvoiceRequestService] 빈만 받는다.
 */
internal const val INVOICE_HANDLE_PROFILE = "invoice-handle"

/**
 * `admin-grant` profile 이름. 검증된 이메일 계정에 관리자 권한을 부여·회수하고 종료하는
 * 실행 모드다(어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md` §2 결정 1).
 *
 * `CREDIT_GRANT_PROFILE`·`INVOICE_HANDLE_PROFILE`과 같은 이유로 `infrastructure`가 아니라
 * `application`이 조립하는 [kr.easydoc.application.admin.AdminGrantService] 빈만 받는다.
 */
internal const val ADMIN_GRANT_PROFILE = "admin-grant"

/**
 * `access-log-report` profile 이름. 접속기록(`personal_data_access_logs`, V22) 월 1회
 * 점검용 보고서를 CSV 로 쓰고 종료하는 실행 모드다(계획
 * `docs/plans/2026-09-11-access-log-retention.md` §3.5).
 *
 * `USAGE_REPORT_PROFILE`과 같은 이유로 `infrastructure`가 아니라 `application`이 조립하는
 * [kr.easydoc.application.accesslog.PersonalDataAccessReportService] 빈만 받는다.
 */
internal const val ACCESS_LOG_REPORT_PROFILE = "access-log-report"

/**
 * 컨텍스트 초기화 중에 [ApplicationRunner][org.springframework.boot.ApplicationRunner] 로
 * 이미 도는 one-shot profile 전부 — 회전 배치·운영 리포트·크레딧 부여·세금계산서 처리·
 * 관리자 부여·접속기록 점검 보고서. `main` 이 이 집합 하나로 판정해 조건의 순환 복잡도를
 * 갈래 수와 무관하게 1로 유지한다.
 */
private val ONE_SHOT_PROFILES: Set<String> =
    setOf(
        ROTATE_KEYS_PROFILE,
        USAGE_REPORT_PROFILE,
        CREDIT_GRANT_PROFILE,
        INVOICE_HANDLE_PROFILE,
        ADMIN_GRANT_PROFILE,
        ACCESS_LOG_REPORT_PROFILE,
    )

fun main(args: Array<String>) {
    val context = runApplication<ApiApplication>(*args)
    val profiles = context.environment.activeProfiles.toSet()

    // Flyway 는 컨텍스트 초기화 중에 이미 돌았으므로 여기서 닫고 종료한다.
    if (profiles.contains(MIGRATE_PROFILE)) {
        context.close()
    }

    // SpringApplication.exit 가 컨텍스트의 ExitCodeGenerator 빈을 읽어 종료 코드를 내고
    // 컨텍스트를 닫는다 — 실패(배치 예외·미완주)면 0이 아닌 코드로 프로세스를 끝낸다.
    if (profiles.any { it in ONE_SHOT_PROFILES }) {
        val exitCode = SpringApplication.exit(context)
        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }
}

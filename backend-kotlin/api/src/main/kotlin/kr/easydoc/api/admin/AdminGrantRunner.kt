package kr.easydoc.api.admin

import kr.easydoc.application.admin.AdminGrantResult
import kr.easydoc.application.admin.AdminGrantService
import kr.easydoc.core.privacy.CONTENT_MASK
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator

/**
 * `admin-grant` profile 의 실행부 — 컨텍스트가 뜬 뒤 [AdminGrantService.grant] 를 한 번
 * 불러 관리자 권한 부여·회수를 반영하고 종료한다. `CreditGrantRunner`·`InvoiceHandleRunner`와
 * 같은 one-shot 자리다 — `ApiApplication.main` 이 [ExitCodeGenerator] 를 읽어
 * `SpringApplication.exit` 로 종료 코드를 낸다.
 *
 * 인자는 `--email=<이메일> [--revoke]`(어드민 최소 계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 1) — `--revoke`는 값을 받지 않는
 * 플래그다(있으면 회수, 없으면 부여).
 *
 * **부여(`--revoke` 아님)는 대상 계정의 이메일이 검증된 상태여야 한다** — 아니면 exit 1.
 * **표준출력에는 user_id·반영 뒤 플래그만 낸다** — 이메일은 담지 않는다(`CreditGrantRunner`의
 * "워크스페이스 이름·이메일은 내지 않는다"와 같은 규약).
 *
 * **예외를 밖으로 던지지 않는다** — `CreditGrantRunner`와 같은 이유. 메시지 한 줄만 남기고
 * 종료 코드 1이다. 알 수 없는 이메일·미검증 이메일·인자 검증 실패 모두 이 한 자리로 모인다.
 */
class AdminGrantRunner(private val service: AdminGrantService) :
    ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(AdminGrantRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                val grantArgs = AdminGrantArgs.parse(args)
                when (val result = service.grant(grantArgs.email, grantArgs.revoke)) {
                    is AdminGrantResult.Applied -> {
                        println("관리자 권한 반영 — user_id=${result.userId} is_admin=${result.isAdmin}")
                        SUCCESS
                    }

                    AdminGrantResult.UserNotFound -> {
                        // 부여·회수는 사유가 다르다 — 회수 대상이 없는 것은 부여 대상이
                        // 없는 것과 다른 운영 상황이라 메시지를 가른다(독립 리뷰 지적).
                        val message = if (grantArgs.revoke) UNKNOWN_REVOKE_TARGET_MESSAGE else UNKNOWN_EMAIL_MESSAGE
                        log.error("관리자 권한 부여가 실패했다: {}", message)
                        FAILURE
                    }

                    AdminGrantResult.EmailNotVerified -> {
                        log.error("관리자 권한 부여가 실패했다: 이메일이 검증되지 않았습니다")
                        FAILURE
                    }
                }
            } catch (failure: Exception) {
                // 메시지만 남긴다 — 이메일은 이 갈래의 예외 메시지에 담기지 않는다
                // (형식·인자 검증 오류뿐이다).
                log.error("관리자 권한 부여가 실패했다: {}", failure.message)
                FAILURE
            }
    }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
        const val UNKNOWN_EMAIL_MESSAGE = "알 수 없는 이메일입니다"
        const val UNKNOWN_REVOKE_TARGET_MESSAGE = "회수 대상 이메일을 찾을 수 없습니다"
    }
}

/**
 * `admin-grant` 인자 검증 — Spring 컨텍스트 없이 [ApplicationArguments] 만으로 단위 테스트할
 * 수 있게 [AdminGrantRunner] 에서 분리했다(`CreditGrantArgs`와 같은 이유).
 */
internal data class AdminGrantArgs(
    val email: String,
    val revoke: Boolean,
) {
    /** 이메일을 찍지 않는다(인구조사 규약). */
    override fun toString(): String = "AdminGrantArgs(email=$CONTENT_MASK, revoke=$revoke)"

    companion object {
        private const val EMAIL_OPTION = "email"
        private const val REVOKE_OPTION = "revoke"

        fun parse(args: ApplicationArguments): AdminGrantArgs {
            val email = requireNotNull(singleOptionValue(args, EMAIL_OPTION)) { "--$EMAIL_OPTION 은 필수입니다" }
            val revoke = args.containsOption(REVOKE_OPTION)
            return AdminGrantArgs(email, revoke)
        }

        private fun singleOptionValue(
            args: ApplicationArguments,
            name: String,
        ): String? = if (args.containsOption(name)) args.getOptionValues(name)?.firstOrNull() else null
    }
}

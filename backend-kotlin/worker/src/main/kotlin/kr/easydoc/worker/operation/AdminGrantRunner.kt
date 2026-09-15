package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.admin.AdminGrantResult
import kr.easydoc.application.admin.AdminGrantService
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.core.privacy.CONTENT_MASK
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.util.UUID

/**
 * 관리자 권한을 부여하거나 회수하는 일회성 명령.
 * `--email`, 선택적 `--revoke`, 필수 `--actor-email`을 받는다. 부여 대상은 이메일 검증이
 * 완료되어야 하며, 출력에는 이메일을 포함하지 않는다. 실제 변경이 적용된 경우에만
 * 접속기록을 남기고 실패는 종료 코드 1로 보고한다.
 */
@Suppress("TooGenericExceptionCaught")
class AdminGrantRunner(
    private val service: AdminGrantService,
    private val users: UserRepository,
    private val accessLog: RecordPersonalDataAccess,
) : ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(AdminGrantRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    override fun run(args: ApplicationArguments) {
        val actorId = resolveActorOrFail(args) ?: return
        exitCode = applyGrant(args, actorId)
    }

    /** 읽기 전 실패(액터 이메일 해석) — 기록 없이 종료 코드 1. */
    private fun resolveActorOrFail(args: ApplicationArguments): UUID? =
        try {
            CliActor.resolveActorId(users, CliActor.parseEmail(args))
        } catch (failure: Exception) {
            log.error("관리자 권한 부여가 실패했다: {}", failure.message)
            exitCode = FAILURE
            null
        }

    private fun applyGrant(
        args: ApplicationArguments,
        actorId: UUID,
    ): Int =
        try {
            val grantArgs = AdminGrantArgs.parse(args)
            when (val result = service.grant(grantArgs.email, grantArgs.revoke)) {
                is AdminGrantResult.Applied -> {
                    accessLog.recordSuccess(
                        actorId,
                        CliActor.clientIp(),
                        OPERATION,
                        "user_id=${result.userId}",
                    )
                    println("관리자 권한 반영 — user_id=${result.userId} is_admin=${result.isAdmin}")
                    SUCCESS
                }

                AdminGrantResult.UserNotFound -> {
                    // 부여와 회수의 운영 조치가 다르므로 메시지를 구분한다.
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

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
        const val UNKNOWN_EMAIL_MESSAGE = "알 수 없는 이메일입니다"
        const val UNKNOWN_REVOKE_TARGET_MESSAGE = "회수 대상 이메일을 찾을 수 없습니다"

        /** 접속기록 `operation` 열에 쓰는 명령 이름. */
        const val OPERATION = "admin-grant"
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

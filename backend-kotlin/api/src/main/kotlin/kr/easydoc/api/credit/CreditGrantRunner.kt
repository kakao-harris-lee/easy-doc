package kr.easydoc.api.credit

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.util.UUID

/**
 * `credit-grant` profile 의 실행부(C2) — 컨텍스트가 뜬 뒤 [CreditAccountService.grant] 를 한 번
 * 불러 운영자 수동 부여·조정을 반영하고 종료한다. `UsageReportRunner`·`KeyRotationRunner`와
 * 같은 one-shot 자리다 — `ApiApplication.main` 이 [ExitCodeGenerator] 를 읽어
 * `SpringApplication.exit` 로 종료 코드를 낸다.
 *
 * 인자는 `--workspace=<uuid> --credits=<정수, 0이 아니면 음수 허용> --reason=plan_monthly|manual|refund
 * --note="…"`(마지막은 선택, 200자 이내) — 계획 `docs/plans/2026-09-07-credit-accounts.md` §2
 * 결정 6. 양수는 [CreditReason] · [CreditAccountRepository.grant] 가 GRANT로, 음수는 ADJUST로
 * 가른다(저장소가 부호로 판단한다, `CreditAccountService` KDoc).
 *
 * **거래의 `owner_user_id`는 이 프로필이 스스로 찾는다.** 운영자는 워크스페이스 식별자만
 * 주고 인증된 사용자 컨텍스트가 없으므로 [CreditAccountRepository.ownerOf] 로 워크스페이스의
 * 실제 소유자를 먼저 읽는다 — 없으면(잘못된 식별자·삭제된 워크스페이스) [NotFoundException].
 *
 * **표준출력에는 workspace_id·적용 델타·반영 뒤 balance·reserved·available만 낸다** —
 * 워크스페이스 이름·이메일은 담지 않는다(계획 §2 결정 6 "워크스페이스 이름·이메일은 내지
 * 않는다").
 *
 * **예외를 밖으로 던지지 않는다** — `UsageReportRunner`와 같은 이유. 메시지 한 줄만 남기고
 * 종료 코드 1이다. 인자 검증 실패([IllegalArgumentException])·알 수 없는 워크스페이스
 * ([NotFoundException]) 모두 이 한 자리로 모인다.
 *
 * **소유자 조회([CreditAccountRepository.ownerOf])와 부여([CreditAccountService.grant])는
 * 한 트랜잭션 안에서 돈다** — 그 사이에 워크스페이스가 지워지면(운영자가 동시에 삭제
 * 처리 중인 경우) 존재하지 않는 소유자로 거래를 남기지 않는다(리뷰 HIGH-1). 반영 뒤
 * 읽기([CreditAccountService.read])는 그 트랜잭션이 커밋된 뒤 별도로 돈다 — 조회는
 * 쓰기와 같은 원자성 경계를 공유할 이유가 없다.
 */
class CreditGrantRunner(
    private val service: CreditAccountService,
    private val repository: CreditAccountRepository,
    private val transaction: TransactionRunner,
) : ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(CreditGrantRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                val grantArgs = CreditGrantArgs.parse(args)
                val ownerId =
                    transaction.inTransaction {
                        val resolvedOwnerId =
                            repository.ownerOf(grantArgs.workspaceId)
                                ?: throw NotFoundException("워크스페이스를 찾을 수 없습니다: ${grantArgs.workspaceId}")
                        service.grant(
                            grantArgs.workspaceId,
                            resolvedOwnerId,
                            grantArgs.credits,
                            grantArgs.reason,
                            grantArgs.note,
                        )
                        resolvedOwnerId
                    }
                val view = service.read(ownerId, grantArgs.workspaceId)

                println(
                    "크레딧 반영 — workspace_id=${grantArgs.workspaceId} 적용=${grantArgs.credits} " +
                        "balance=${view.balance} reserved=${view.reserved} available=${view.available}",
                )
                SUCCESS
            } catch (failure: Exception) {
                // 메시지만 남긴다 — 워크스페이스 이름·이메일은 이 갈래의 예외 메시지에
                // 담기지 않는다(도메인 예외·인자 검증 메시지 모두 형식·범위 오류뿐이다).
                log.error("크레딧 부여가 실패했다: {}", failure.message)
                FAILURE
            }
    }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
    }
}

/**
 * `credit-grant` 인자 검증 — Spring 컨텍스트 없이 [ApplicationArguments] 만으로 단위 테스트할
 * 수 있게 [CreditGrantRunner] 에서 분리했다(`DefaultApplicationArguments` 로 직접 생성 가능).
 */
internal data class CreditGrantArgs(
    val workspaceId: UUID,
    val credits: Int,
    val reason: CreditReason,
    val note: String?,
) {
    companion object {
        private const val WORKSPACE_OPTION = "workspace"
        private const val CREDITS_OPTION = "credits"
        private const val REASON_OPTION = "reason"
        private const val NOTE_OPTION = "note"

        /** `credit_transactions.note` — `varchar(200)`(V15). */
        private const val NOTE_MAX_LENGTH = 200

        /**
         * 운영자 CLI 가 받는 사유 셋 — [CreditReason] 전체가 아니다. `signup`·`conversion`은
         * 각각 가입 자동 부여·문서 변환 경로 전용이라 이 CLI 로 흉내 낼 수 없다(계획 §2 결정 6).
         */
        private val ALLOWED_REASONS =
            mapOf(
                "plan_monthly" to CreditReason.PLAN_MONTHLY,
                "manual" to CreditReason.MANUAL,
                "refund" to CreditReason.REFUND,
            )

        fun parse(args: ApplicationArguments): CreditGrantArgs {
            val workspaceId = requireUuid(singleOptionValue(args, WORKSPACE_OPTION))
            val credits = requireNonZeroInt(singleOptionValue(args, CREDITS_OPTION))
            val reason = requireReason(singleOptionValue(args, REASON_OPTION))
            val note = requireValidNote(singleOptionValue(args, NOTE_OPTION))
            return CreditGrantArgs(workspaceId, credits, reason, note)
        }

        private fun singleOptionValue(
            args: ApplicationArguments,
            name: String,
        ): String? = if (args.containsOption(name)) args.getOptionValues(name)?.firstOrNull() else null

        private fun requireUuid(value: String?): UUID {
            val raw = requireNotNull(value) { "--$WORKSPACE_OPTION 은 필수입니다" }
            return try {
                UUID.fromString(raw)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("--$WORKSPACE_OPTION 형식이 올바르지 않습니다: $raw", failure)
            }
        }

        private fun requireNonZeroInt(value: String?): Int {
            val raw = requireNotNull(value) { "--$CREDITS_OPTION 은 필수입니다" }
            val parsed = requireNotNull(raw.toIntOrNull()) { "--$CREDITS_OPTION 형식이 올바르지 않습니다: $raw" }
            require(parsed != 0) { "--$CREDITS_OPTION 은 0이 될 수 없습니다" }
            return parsed
        }

        private fun requireReason(value: String?): CreditReason {
            val raw = requireNotNull(value) { "--$REASON_OPTION 은 필수입니다" }
            return requireNotNull(ALLOWED_REASONS[raw]) {
                "--$REASON_OPTION 값이 올바르지 않습니다: $raw (허용값: ${ALLOWED_REASONS.keys.joinToString(", ")})"
            }
        }

        private fun requireValidNote(value: String?): String? {
            require(value == null || value.length <= NOTE_MAX_LENGTH) {
                "--$NOTE_OPTION 은 ${NOTE_MAX_LENGTH}자를 넘을 수 없습니다"
            }
            return value
        }
    }
}

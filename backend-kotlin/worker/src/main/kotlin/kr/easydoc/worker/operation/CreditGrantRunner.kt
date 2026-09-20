package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 워크스페이스 크레딧을 조정하는 일회성 명령.
 * `--workspace`, `--credits`, `--reason`, 선택적 `--note`, 필수 `--actor-email`을 받는다.
 * `--cycle-ends-at`이 있으면 잔액을 더하지 않고 주기 allowance로 설정하며,
 * `--cycle-renews`는 이 옵션과 함께만 사용할 수 있다.
 *
 * 소유자 조회와 조정은 한 트랜잭션에서 실행한다. 출력과 오류에는 이름이나 이메일을
 * 포함하지 않으며, 변경 커밋 후 접속기록을 남긴다. 실패는 종료 코드 1로 보고한다.
 */
@Suppress("TooGenericExceptionCaught")
class CreditGrantRunner(
    private val service: CreditAccountService,
    private val repository: CreditAccountRepository,
    private val transaction: TransactionRunner,
    private val users: UserRepository,
    private val accessLog: RecordPersonalDataAccess,
) : ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(CreditGrantRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    override fun run(args: ApplicationArguments) {
        val actorId = resolveActorOrFail(args) ?: return
        exitCode = applyGrant(args, actorId)
    }

    /** 읽기 전 실패(이메일 해석) — 기록 없이 종료 코드 1. */
    private fun resolveActorOrFail(args: ApplicationArguments): UUID? =
        try {
            CliActor.resolveActorId(users, CliActor.parseEmail(args))
        } catch (failure: Exception) {
            log.error("크레딧 부여가 실패했다: {}", failure.message)
            exitCode = FAILURE
            null
        }

    private fun applyGrant(
        args: ApplicationArguments,
        actorId: UUID,
    ): Int =
        try {
            val grantArgs = CreditGrantArgs.parse(args)
            val cycleEndsAt = grantArgs.cycleEndsAt
            val ownerId =
                transaction.inTransaction {
                    val resolvedOwnerId =
                        repository.ownerOf(grantArgs.workspaceId)
                            ?: throw NotFoundException("워크스페이스를 찾을 수 없습니다: ${grantArgs.workspaceId}")
                    if (cycleEndsAt != null) {
                        service.setAllowance(
                            grantArgs.workspaceId,
                            resolvedOwnerId,
                            grantArgs.credits,
                            cycleEndsAt,
                            grantArgs.renews,
                            grantArgs.reason,
                            grantArgs.note,
                        )
                    } else {
                        service.grant(
                            grantArgs.workspaceId,
                            resolvedOwnerId,
                            grantArgs.credits,
                            grantArgs.reason,
                            grantArgs.note,
                        )
                    }
                    resolvedOwnerId
                }
            // 여기가 실제로 소유자를 찾고 반영한 시점이다 — 클래스 KDoc. 아래 조회가
            // 실패해도(표준출력용일 뿐이다) 이 기록은 되돌리지 않는다.
            accessLog.recordSuccess(
                actorId,
                CliActor.clientIp(),
                OPERATION,
                "workspace_id=${grantArgs.workspaceId}",
            )
            val view = service.read(ownerId, grantArgs.workspaceId)

            println(
                "크레딧 반영 — workspace_id=${grantArgs.workspaceId} 적용=${grantArgs.credits} " +
                    "balance=${view.balance} reserved=${view.reserved} available=${view.available}" +
                    (cycleEndsAt?.let { " cycle_ends_at=$it" } ?: ""),
            )
            SUCCESS
        } catch (failure: Exception) {
            // 메시지만 남긴다 — 워크스페이스 이름·이메일은 이 갈래의 예외 메시지에
            // 담기지 않는다(도메인 예외·인자 검증 메시지 모두 형식·범위 오류뿐이다).
            log.error("크레딧 부여가 실패했다: {}", failure.message)
            FAILURE
        }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1

        /** 접속기록 `operation` 열에 쓰는 명령 이름. */
        const val OPERATION = "credit-grant"
    }
}

/**
 * `credit-grant` 인자 검증 — Spring 컨텍스트 없이 [ApplicationArguments] 만으로 단위 테스트할
 * 수 있게 [CreditGrantRunner] 에서 분리했다(`DefaultApplicationArguments` 로 직접 생성 가능).
 */
internal data class CreditGrantArgs(
    val workspaceId: UUID,
    val credits: BigDecimal,
    val reason: CreditReason,
    val note: String?,
    /**
     * `--cycle-ends-at`(선택) — 있으면 [CreditGrantRunner] 가 [credits] 를 더하지 않고
     * 「이번 주기 이용량」으로 설정한다([CreditAccountService.setAllowance]). 없으면 기존
     * `grant`(더하기) 경로 그대로다.
     */
    val cycleEndsAt: Instant?,
    /**
     * `--cycle-renews`(선택, 기본 `false`) — [cycleEndsAt] 이 있을 때만 [CreditAccountService.setAllowance]
     * 로 전달된다. 주기 종료 배치가 이 계정을 갱신(플랜)할지 종료(무료 체험)할지 가른다.
     */
    val renews: Boolean,
) {
    companion object {
        private const val WORKSPACE_OPTION = "workspace"
        private const val CREDITS_OPTION = "credits"
        private const val REASON_OPTION = "reason"
        private const val NOTE_OPTION = "note"
        private const val CYCLE_ENDS_AT_OPTION = "cycle-ends-at"
        private const val CYCLE_RENEWS_OPTION = "cycle-renews"

        /** `credit_transactions.note` — `varchar(200)`(V15). */
        private const val NOTE_MAX_LENGTH = 200

        /**
         * 운영자 CLI 가 받는 사유 셋 — [CreditReason] 전체가 아니다. `signup`·`conversion`은
         * 각각 가입 자동 부여와 문서 변환 경로 전용이라 이 CLI에서 사용할 수 없다.
         */
        private val ALLOWED_REASONS =
            mapOf(
                "plan_monthly" to CreditReason.PLAN_MONTHLY,
                "manual" to CreditReason.MANUAL,
                "refund" to CreditReason.REFUND,
            )

        fun parse(args: ApplicationArguments): CreditGrantArgs {
            val workspaceId = requireUuid(singleOptionValue(args, WORKSPACE_OPTION))
            val credits = requireNonZeroAmount(singleOptionValue(args, CREDITS_OPTION))
            val reason = requireReason(singleOptionValue(args, REASON_OPTION))
            val note = requireValidNote(singleOptionValue(args, NOTE_OPTION))
            val cycleEndsAt = requireOptionalCycleEndsAt(singleOptionValue(args, CYCLE_ENDS_AT_OPTION))
            if (cycleEndsAt != null) {
                // 주기 이용량은 음수일 수 없다(`ck_workspace_credit_accounts_allowance_non_negative`,
                // V21) — 이 갈래에서만 credits 의 부호를 좁힌다. 이 인자가 없는 기존 경로
                // (grant/adjust)는 음수를 그대로 허용한다.
                require(credits.signum() >= 0) {
                    "--$CYCLE_ENDS_AT_OPTION 와 함께라면 --$CREDITS_OPTION 은 음수가 될 수 없습니다: $credits"
                }
            }
            val renews = parseCycleRenews(args)
            return CreditGrantArgs(workspaceId, credits, reason, note, cycleEndsAt, renews)
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

        private fun requireNonZeroAmount(value: String?): BigDecimal {
            val raw = requireNotNull(value) { "--$CREDITS_OPTION 은 필수입니다" }
            val parsed = requireNotNull(raw.toBigDecimalOrNull()) { "--$CREDITS_OPTION 형식이 올바르지 않습니다: $raw" }
            require(parsed.signum() != 0) { "--$CREDITS_OPTION 은 0이 될 수 없습니다" }
            return try {
                parsed.setScale(1, RoundingMode.UNNECESSARY)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("--$CREDITS_OPTION 은 0.1 단위여야 합니다: $raw")
            }
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

        /**
         * `--cycle-renews` 는 값 없이 쓰는 플래그도, `=true`/`=false` 로 명시하는 것도
         * 허용한다 — 없으면 `false`(기본, 무료 체험처럼 「닫는」 주기), 값 없이 붙이면
         * `true`, `=false` 를 명시하면 `false`.
         */
        private fun parseCycleRenews(args: ApplicationArguments): Boolean {
            if (!args.containsOption(CYCLE_RENEWS_OPTION)) return false
            val raw = args.getOptionValues(CYCLE_RENEWS_OPTION)?.firstOrNull()
            return raw.isNullOrBlank() || raw.equals("true", ignoreCase = true)
        }

        /** 없으면 `null`(기존 grant 경로). 있으면 ISO-8601 순간(`Instant.parse`)이어야 한다. */
        private fun requireOptionalCycleEndsAt(value: String?): Instant? {
            if (value == null) return null
            return try {
                Instant.parse(value)
            } catch (failure: DateTimeParseException) {
                throw IllegalArgumentException(
                    "--$CYCLE_ENDS_AT_OPTION 형식이 올바르지 않습니다(ISO-8601 순간이어야 합니다, 예: " +
                        "2026-10-10T00:00:00Z): $value",
                    failure,
                )
            } catch (failure: DateTimeException) {
                throw IllegalArgumentException(
                    "--$CYCLE_ENDS_AT_OPTION 형식이 올바르지 않습니다(ISO-8601 순간이어야 합니다, 예: " +
                        "2026-10-10T00:00:00Z): $value",
                    failure,
                )
            }
        }
    }
}

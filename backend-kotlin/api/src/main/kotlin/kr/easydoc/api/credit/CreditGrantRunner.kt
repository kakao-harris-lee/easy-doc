package kr.easydoc.api.credit

import kr.easydoc.api.accesslog.CliActor
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
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeParseException
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
 * **`--cycle-ends-at=<ISO-8601 순간>`(선택)이 있으면 [CreditAccountService.setAllowance] 를
 * 대신 부른다** — 잔액을 `--credits` 값으로 **더하지 않고 설정**하고, 그 값을 이번 주기의
 * 이용량(`allowance`)으로, `--cycle-ends-at` 값을 주기 종료일로 남긴다(크레딧을 「구독
 * 주기에 포함된 이용량」으로 바꾼 사용자 결정 2026-09-10 — "주기를 여는" 운영 경로). 이
 * 경로에서는 `--credits`가 음수면 거절된다(주기 이용량은 음수일 수 없다) — 기존
 * `--credits`(더하기, 음수 허용) 경로와는 별개의 의미다.
 *
 * **`--cycle-renews`(선택, 기본 `false`)는 `--cycle-ends-at`과 함께일 때만 의미가 있다** —
 * 주기 종료 배치가 이 계정을 갱신(플랜, `true`)할지 종료(무료 체험, `false`)할지 가른다.
 * `--cycle-ends-at` 없이 주기 이용량을 여는 CLI 경로는 없으므로, 이 인자 혼자는 쓰이지
 * 않는다(`CreditGrantArgs.renews` KDoc).
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
 *
 * **`--actor-email=<이메일>`(필수)** — 접속기록(`personal_data_access_logs`, V22)의
 * `actor_user_id`를 채운다(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.2).
 * `admin-grant --email`과 같은 형태다 — 운영자는 자기 user_id를 모른다.
 *
 * **접속기록은 위 트랜잭션이 커밋된 직후 남긴다** — 워크스페이스 소유자를 실제로 찾고
 * 크레딧을 반영한 시점이다(`UsageReportRunner`와 같은 원칙 — 명령의 성패가 아니라
 * 개인정보에 접근했는지를 남긴다). `--actor-email` 해석 실패·워크스페이스를 찾지 못해
 * 트랜잭션이 실패하는 경우처럼 **읽기 전에** 끝나는 실패는 기록을 남기지 않는다.
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

        /** `ApiApplication.CREDIT_GRANT_PROFILE` 과 같은 값 — 접속기록 `operation` 열에 그대로 쓴다. */
        const val OPERATION = "credit-grant"
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
            val cycleEndsAt = requireOptionalCycleEndsAt(singleOptionValue(args, CYCLE_ENDS_AT_OPTION))
            if (cycleEndsAt != null) {
                // 주기 이용량은 음수일 수 없다(`ck_workspace_credit_accounts_allowance_non_negative`,
                // V21) — 이 갈래에서만 credits 의 부호를 좁힌다. 이 인자가 없는 기존 경로
                // (grant/adjust)는 음수를 그대로 허용한다.
                require(credits >= 0) {
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

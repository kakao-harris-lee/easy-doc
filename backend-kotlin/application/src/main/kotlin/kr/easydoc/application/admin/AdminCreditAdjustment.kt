package kr.easydoc.application.admin

import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.CreditAccountView
import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/**
 * 관리자 화면이 조정할 수 있는 사유 — `credit-grant` 운영 프로필과 같은 집합(월 구독 부여·
 * 수동 부여·환급). 자동 경로 전용 사유(가입 보너스·문서 변환)는 화면에서 만들 수 없다
 * (`CreditGrantArgs.ALLOWED_REASONS`와 같은 판단).
 */
private val ADMIN_GRANTABLE_REASONS: Map<String, CreditReason> =
    mapOf(
        "plan_monthly" to CreditReason.PLAN_MONTHLY,
        "manual" to CreditReason.MANUAL,
        "refund" to CreditReason.REFUND,
    )

/** 관리자 크레딧 조정 사유 문자열 → [CreditReason]. 허용 밖 값은 422. */
fun requireAdminCreditReason(raw: String): CreditReason =
    ADMIN_GRANTABLE_REASONS[raw]
        ?: throw InvalidInputException(
            "알 수 없는 크레딧 사유입니다 (허용값: ${ADMIN_GRANTABLE_REASONS.keys.joinToString(", ")})",
        )

/** `credits`는 0이 될 수 없다(계획 §2 결정 4 `POST /admin/workspaces/{workspace_id}/credits`). */
fun requireNonZeroCredits(value: Int): Int {
    if (value == 0) throw InvalidInputException(ZERO_CREDITS_MESSAGE)
    return value
}

internal const val ZERO_CREDITS_MESSAGE = "credits 는 0이 될 수 없습니다"

/**
 * `note`(선택) — `credit_transactions.note`(V15) 는 `varchar(200)` 이다.
 * `CreditGrantArgs.requireValidNote`(CLI)와 같은 상한이다. 값을 자르지 않고 거절한다.
 */
fun requireValidAdminCreditNote(raw: String?): String? {
    if (raw != null && raw.length > MAX_ADMIN_CREDIT_NOTE_LENGTH) {
        throw InvalidInputException(ADMIN_CREDIT_NOTE_TOO_LONG_MESSAGE)
    }
    return raw
}

private const val MAX_ADMIN_CREDIT_NOTE_LENGTH = 200
internal const val ADMIN_CREDIT_NOTE_TOO_LONG_MESSAGE = "note 는 200자를 넘을 수 없습니다"

/**
 * `POST /admin/workspaces/{workspace_id}/credits` 유스케이스 — [CreditAccountService.grant]를
 * 그대로 재사용한다(계획 §3 A1 「기존 서비스 조합」). 워크스페이스의 실제 소유자를
 * [CreditAccountRepository.ownerOf]로 찾아 넘긴다 — `credit-grant` CLI(C2)와 같은 이유로,
 * 관리자 화면 요청에는 소유자 컨텍스트가 없다(관리자 자신은 그 워크스페이스의 소유자가
 * 아니다).
 */
class AdminCreditAdjustmentService(
    private val repository: CreditAccountRepository,
    private val service: CreditAccountService,
) {
    fun adjust(
        workspaceId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID,
    ): CreditAccountView {
        val ownerId = repository.ownerOf(workspaceId) ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
        service.grant(workspaceId, ownerId, credits, reason, note, actorUserId)
        return service.read(ownerId, workspaceId)
    }
}

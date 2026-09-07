package kr.easydoc.application.credit

import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/** 예약 성공 뒤 계정 상태 — [ReservationResult.Reserved] 를 그대로 노출한다. */
typealias Reservation = ReservationResult.Reserved

/** `GET /workspaces/{workspace_id}/credits` 응답 — 계약 `WorkspaceCreditsResponse`(2.22.0). */
data class CreditAccountView(
    val workspaceId: UUID,
    val balance: Int,
    val reserved: Int,
    val available: Int,
    val enforced: Boolean,
    val transactions: List<CreditTransactionView>,
)

/**
 * 크레딧 계정 유스케이스 — 예약·소비·해제·부여·조회(계획
 * `docs/plans/2026-09-07-credit-accounts.md` §2).
 *
 * [enforced] 는 `easydoc.credits.enforced`(기본 `false`) 그대로다 — 꺼져 있으면
 * [reserve] 가 가용 잔액과 무관하게 항상 성공한다(잔액이 음수로 기록될 수 있다). 이
 * 서비스가 스위치를 쥐는 이유는 정책이 한 곳에서만 판단돼야 하기 때문이다 — 호출자
 * ([kr.easydoc.application.document.DocumentService])는 이 서비스가 402 를 던지는지
 * 여부만 본다.
 *
 * [signupGrant] 도 같은 이유로 이 서비스가 쥔다 — `easydoc.credits.signup-grant`(기본
 * `0`) 값 하나를 [kr.easydoc.application.auth.AuthService]·
 * [kr.easydoc.application.auth.SocialLoginService] 둘 다 이 서비스의 [grantSignupBonus]
 * 를 통해서만 쓰게 해, 두 가입 경로가 서로 다른 값을 배선받아 갈리는 사고(리뷰
 * 2026-09-07 지적)를 구조로 막는다.
 */
class CreditAccountService(
    private val repository: CreditAccountRepository,
    private val enforced: Boolean,
    private val signupGrant: Int = 0,
) {
    /** 계정 행이 없으면 0 잔액으로 만든다. */
    fun ensureAccount(workspaceId: UUID) = repository.ensureAccount(workspaceId)

    /**
     * [amount] 를 예약한다. 가용 크레딧이 모자라면(집행 중일 때만) [InsufficientCreditsException]
     * (→ 402, `X-Credit-Balance`·`X-Credits-Required`).
     */
    fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        amount: Credits,
    ): Reservation =
        when (val result = repository.reserve(ownerId, workspaceId, documentId, amount, enforced)) {
            is ReservationResult.Reserved -> {
                result
            }

            is ReservationResult.Insufficient -> {
                throw InsufficientCreditsException(INSUFFICIENT_CREDITS_MESSAGE, result.available, amount.amount)
            }
        }

    /** 예약을 소비로 확정한다. [amount] 가 0 이면(V15 이전 문서) 아무것도 하지 않는다. */
    fun consume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        if (amount.amount == 0) return
        repository.consume(workspaceId, ownerId, documentId, conversionId, amount)
    }

    /** 예약을 되돌린다. [amount] 가 0 이면 아무것도 하지 않는다 — [consume] 과 같은 규약. */
    fun release(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        if (amount.amount == 0) return
        repository.release(workspaceId, ownerId, documentId, conversionId, amount)
    }

    /** 운영자 수동 부여·조정. `credits` 가 0 이상이면 GRANT, 음수면 ADJUST(저장소가 가른다). */
    fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
    ): Int = repository.grant(workspaceId, ownerUserId, credits, reason, note)

    /**
     * 가입 시 기본 워크스페이스에 [signupGrant] 만큼 부여한다 — `AuthService.signup`·
     * `SocialLoginService.callback` 의 새 사용자 갈래가 **둘 다** 이 메서드 하나만
     * 부른다(리뷰 2026-09-07 — 두 경로가 각자 `signupGrant` 를 인자로 받으면 배선이
     * 갈릴 수 있었다). `signupGrant` 가 0 이하면 아무것도 하지 않는다.
     */
    fun grantSignupBonus(
        workspaceId: UUID,
        ownerUserId: UUID,
    ) {
        if (signupGrant <= 0) return
        repository.grant(workspaceId, ownerUserId, signupGrant, CreditReason.SIGNUP, note = null)
    }

    /** **내** 계정을 읽는다. 없거나 내 것이 아니면 [NotFoundException]. */
    fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountView =
        repository.read(ownerId, workspaceId)?.let { row ->
            CreditAccountView(
                workspaceId = row.workspaceId,
                balance = row.balance,
                reserved = row.reserved,
                available = row.balance - row.reserved,
                enforced = enforced,
                transactions = row.transactions,
            )
        } ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)

    /** 정합 검사(계획 §4) — [CreditAccountRepository.consistencyViolations] 그대로. */
    fun consistencyViolations(): List<CreditConsistencyViolation> = repository.consistencyViolations()

    private companion object {
        const val INSUFFICIENT_CREDITS_MESSAGE = "크레딧이 부족합니다. 충전 후 다시 시도하세요."
    }
}

/**
 * 아무것도 저장하지 않는 [CreditAccountRepository] — 항상 예약에 성공한다.
 *
 * 실제 조립(`CreditAccountConfiguration`)은 이 대역을 쓰지 않는다. 존재 이유는 크레딧과
 * 무관한 저장 경로만 재는 다른 모듈의 실 DB 테스트(원본 보존·검수 저장·키 회전·worker
 * 흐름 등)가 `DocumentService`·`ProcessConversionJob` 을 세우면서 크레딧 계정까지 새로
 * 배선하지 않아도 되게 하는 것이다 — [noCredits] 가 그 조립을 한 줄로 묶는다. 그래서
 * `internal` 이 아니라 공개한다(`FakeLlmProvider` 와 같은 공유 테스트 대역 규약).
 * 집행 여부와 무관하게 항상 성공하므로 프로덕션 스위치(`easydoc.credits.enforced`)의
 * 안전한 기본값(꺼짐)과 같은 방향이다.
 *
 * **`application` 모듈에는 `core`·`infrastructure` 와 달리 `testFixtures` 소스셋이 없다**
 * (`application/build.gradle.kts` 확인) — 그래서 이 대역과 [noCredits] 를 test 소스가
 * 아니라 여기(main)에 둔다. test 소스로 옮기면 `infrastructure`·`api` 모듈의 테스트가
 * (자기 모듈이 아닌) `application` 의 test 산출물을 당겨 올 방법이 없어 재사용이 끊긴다.
 */
object NoopCreditAccountRepository : CreditAccountRepository {
    override fun ensureAccount(workspaceId: UUID) = Unit

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        amount: Credits,
        enforced: Boolean,
    ): ReservationResult = ReservationResult.Reserved(balance = amount.amount, reserved = amount.amount)

    override fun consume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) = Unit

    override fun release(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) = Unit

    override fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
    ): Int = credits

    override fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountRow? = null

    override fun consistencyViolations(): List<CreditConsistencyViolation> = emptyList()
}

/**
 * 크레딧과 무관한 테스트가 `DocumentService`·`ProcessConversionJob` 을 세울 때 쓰는
 * 한 줄 조립 — [NoopCreditAccountRepository] KDoc의 「`testFixtures` 가 없다」 사유로
 * main 소스에 둔다. 여덟 곳의 실 DB 테스트(`infrastructure`)와 순수 단위 테스트
 * (`application`)가 이 함수 하나를 공유해 대역을 손으로 다시 짜지 않는다.
 */
fun noCredits(): CreditAccountService = CreditAccountService(NoopCreditAccountRepository, enforced = false)

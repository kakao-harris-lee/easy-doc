package kr.easydoc.application.credit

import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.CreditTransactionKind
import kr.easydoc.core.credit.Credits
import java.time.Instant
import java.util.UUID

// 크레딧 계정 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**.
//
// `application` 은 `infrastructure` 를 의존하지 않는다(계획 §3.2, `DocumentPorts.kt` 와 같은
// 규약). 이 표들은 [kr.easydoc.core.crypto.EncryptedField] 가 아는 봉인 대상이 아니다 —
// 원장 `llm_calls`(V14)와 같은 이유로 본문·개인정보를 담지 않는다(숫자·사유·메모뿐).

/** [CreditAccountRepository.reserve] 의 결과. */
sealed interface ReservationResult {
    /** 예약에 성공했다 — 예약 **직후** 계정 상태. */
    data class Reserved(
        val balance: Int,
        val reserved: Int,
    ) : ReservationResult {
        /** 가용 = balance − reserved. */
        val available: Int get() = balance - reserved
    }

    /**
     * 집행 중인데 가용 크레딧이 모자라 예약하지 못했다. [available] 은 **음수일 수 있다**
     * (집행이 꺼진 이전 요청들이 잔액을 이미 음수로 만들어 둔 상태에서 집행이 켜지면,
     * 그 워크스페이스의 다음 예약은 실제 음수 가용량을 그대로 봐야 한다 — `0`으로
     * 바닥을 씌우면 헤더 `X-Credit-Balance`가 거짓 낙관값을 낸다).
     */
    data class Insufficient(val available: Int) : ReservationResult
}

/** 저장소가 돌려주는 계정 스냅샷 — [kr.easydoc.infrastructure.credit.CreditsProperties.enforced] 를 모른다. */
data class CreditAccountRow(
    val workspaceId: UUID,
    val balance: Int,
    val reserved: Int,
    val transactions: List<CreditTransactionView>,
)

/**
 * 거래 한 건 — 계약 `WorkspaceCreditsResponse.transactions` 항목.
 *
 * [balanceDelta]·[reservedDelta] 는 이 거래가 계정 행의 두 숫자에 각각 얼마를 움직였는지
 * 그대로 담는다(V15 머리주석 2026-09-07 리뷰) — `reserve`: (0, +n), `consume`: (-n, -n),
 * `release`: (0, -n), `grant`/`adjust`: (±c, 0).
 *
 * API 가 노출하는 `credits` 필드(계약 `CreditTransaction.credits`)는 계획 §2 결정 2가
 * 원래 정한 부호 규약("grant/release는 +, reserve/consume/adjust는 방향대로") 그대로다 —
 * [kr.easydoc.api.workspace.CreditTransactionResponse.of] 가 종류별로 계산한다:
 * `reserve`·`release`는 `-reservedDelta`(reserve: -n, release: +n),
 * `consume`·`grant`·`adjust`는 [balanceDelta] 그대로(consume: -n, grant: +c, adjust: 부호
 * 그대로의 c). 저장소 델타 자체는 종류를 가리지 않고 균일한 산술식(계정 행의 실제 증감)
 * 이지만, API 값은 "이 거래가 사용자 관점에서 어느 방향으로 크레딧을 움직였는가"를 그대로
 * 드러낸다 — 이전 설계(단일 `credits` 열)가 `consume` 을 강제로 `0` 으로 둬야 했던
 * 결함(V15 머리주석)을 이 계산이 두 열로부터 다시 복원해 없앤다.
 */
data class CreditTransactionView(
    val id: UUID,
    val kind: CreditTransactionKind,
    val balanceDelta: Int,
    val reservedDelta: Int,
    val reason: CreditReason,
    val note: String?,
    val documentId: UUID?,
    val createdAt: Instant,
)

/**
 * 정합 검사(계획 §4) 위반 한 건 — 두 불변식(`sum(balance_delta) = balance`,
 * `sum(reserved_delta) = reserved`) 중 하나라도 어긋난 워크스페이스.
 */
data class CreditConsistencyViolation(
    val workspaceId: UUID,
    val balance: Int,
    val reserved: Int,
    val balanceSum: Int,
    val reservedSum: Int,
)

/**
 * `workspace_credit_accounts`·`credit_transactions` 저장소(V15).
 *
 * **모든 변형 메서드는 계정 행과 거래 행을 한 문장 묶음으로, 호출자의 트랜잭션 안에서
 * 쓴다** — 커밋하지 않는다(`ConversionRepository` 와 같은 규약).
 *
 * **소유 술어는 [reserve]·[read] 둘에만 있다.** 이 둘은 사용자 요청 경로(`createDocument`·
 * `readWorkspaceCredits`)가 호출자가 제출한 `workspace_id`를 그대로 받아 넘기므로, 그
 * 값이 진짜 호출자 소유인지 문장 자신이 확인해야 한다 —
 * [JdbcConversionRepository.reserveReconversionCalls] 와 같은 자리(쓰기 문장 자신)에
 * 소유 술어(`EXISTS … user_id = :ownerId`)를 건다. [consume]·[release]·[grant] 는 소유
 * 술어가 없다 — 셋 다 이미 검증된 문맥에서만 불린다: `consume`/`release`는 worker 가
 * 자신이 방금 `FOR NO KEY UPDATE`로 잠그고 읽은 [kr.easydoc.application.conversion.ConversionWorkItem]
 * 의 `workspaceId`/`ownerId`를 그대로 넘기고(사용자가 준 값이 아니다 — 리스를 쥔
 * worker 자신이 DB에서 방금 읽은 값), `grant`는 운영자 전용 진입점(`credit-grant`
 * 프로필, C2)이나 가입 직후([kr.easydoc.application.auth.AuthService.signup] 이
 * 방금 만든 워크스페이스)에서만 불려 호출자가 그 워크스페이스를 지어낼 수 없다.
 */
interface CreditAccountRepository {
    /** 계정 행이 없으면 0 잔액으로 만든다. 이미 있으면 아무것도 하지 않는다(멱등). */
    fun ensureAccount(workspaceId: UUID)

    /**
     * [amount] 를 예약한다 — `reserved += amount`. [enforced] 가 거짓이면 가용 잔액과
     * 무관하게 항상 성공한다(잔액이 음수로 기록될 수 있다, 계획 §2 결정 4).
     *
     * **계정 행이 아직 없으면(롤링 배포 창) 먼저 만든다** — [ensureAccount] 를 호출자
     * 대신 이 메서드가 스스로 부른다. 그렇지 않으면 집행이 꺼진 상태에서도 계정 행
     * 부재가 그대로 402(`Insufficient`)로 새어 나간다 — 집행 스위치가 꺼져 있다는
     * 것은 "잔액 검사를 하지 않는다"는 뜻이지 "행이 없으면 거절한다"는 뜻이 아니다.
     */
    fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        amount: Credits,
        enforced: Boolean,
    ): ReservationResult

    /** 예약을 소비로 확정한다 — `balance -= amount`, `reserved -= amount`. */
    fun consume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    )

    /** 예약을 되돌린다 — `reserved -= amount` (`balance` 는 그대로). */
    fun release(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    )

    /**
     * 수동 부여·조정 — `balance += credits`([credits] 는 음수 허용). 종류는 부호가 정한다
     * (0 이상이면 [CreditTransactionKind.GRANT], 음수면 [CreditTransactionKind.ADJUST]).
     *
     * 계정 행이 없으면(워크스페이스가 지워졌거나 잘못된 식별자) [kr.easydoc.core.exceptions.NotFoundException] —
     * 스프링 데이터 접근 예외가 API 경계로 새지 않는다.
     *
     * [actorUserId] 는 감사 흔적이다(어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md`
     * §2 결정 3, `credit_transactions.actor_user_id` V17) — 관리자 화면 경유는 관리자 id,
     * `credit-grant` 프로필·가입 보너스 같은 자동 경로는 `null`.
     *
     * @return 반영 뒤 잔액.
     */
    @Suppress("LongParameterList")
    fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ): Int

    /**
     * **내** 계정을 읽는다. 없거나 내 것이 아니면 `null` — 두 경우를 구분하지 않는다.
     * 최근 거래 50건을 최신순으로 함께 담는다.
     */
    fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountRow?

    /**
     * 정합 검사(계획 §4 수용 기준) — `credit_transactions` 의 `balance_delta`·
     * `reserved_delta` 합이 각각 `balance`·`reserved` 와 다른 워크스페이스를 전부
     * 찾는다. 운영 리포트가 재사용할 수 있게 저장소에 둔다.
     */
    fun consistencyViolations(): List<CreditConsistencyViolation>

    /**
     * 워크스페이스 소유자(`workspaces.user_id`) 조회 — `credit-grant` 프로필(C2)이 거래의
     * `owner_user_id`(NOT NULL FK `users`)를 채우려고 쓴다. 그 프로필은 인증된 요청자가
     * 없어(운영자가 워크스페이스 식별자만 CLI 인자로 준다) [grant] 에 넘길 실제 사용자
     * id 를 스스로 찾아야 한다. 워크스페이스가 없으면 `null`.
     */
    fun ownerOf(workspaceId: UUID): UUID?
}

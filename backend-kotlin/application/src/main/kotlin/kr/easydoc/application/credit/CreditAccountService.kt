package kr.easydoc.application.credit

import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** 예약 성공 뒤 계정 상태 — [ReservationResult.Reserved] 를 그대로 노출한다. */
typealias Reservation = ReservationResult.Reserved

/**
 * `GET /workspaces/{workspace_id}/credits` 응답 — 계약 `WorkspaceCreditsResponse`(2.22.0,
 * [allowance]·[cycleEndsAt] 는 2.30.0).
 */
data class CreditAccountView(
    val workspaceId: UUID,
    val balance: Int,
    val reserved: Int,
    val available: Int,
    val enforced: Boolean,
    val transactions: List<CreditTransactionView>,
    /**
     * `signup_grant_skipped`(계약 2.29.0) — 이 계정의 가입 부여가 「이미 가입 부여를
     * 받은 이메일이라 건너뛰었다」로 판정됐는지. **이메일이 인증되기 전에는 항상
     * `false`다**([CreditAccountService.read] 가 [CreditAccountRow.emailVerified] 로
     * 가린다) — 인증 전 노출은 이메일 존재 여부를 캐내는 창구가 된다(가입 크레딧 후속
     * §7 결정 5).
     */
    val signupGrantSkipped: Boolean,
    /** 이번 주기에 제공된 이용량(계약 2.30.0) — `workspace_credit_accounts.allowance`(V21). */
    val allowance: Int,
    /**
     * 이번 주기가 끝나는 시각(계약 2.30.0) — `null`이면 이 계정은 주기가 없다(기존 계정,
     * 또는 아직 플랜을 배정받지 않은 계정). 화면은 이때 기존 문구를 유지한다.
     */
    val cycleEndsAt: Instant?,
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
@Suppress("LongParameterList")
class CreditAccountService(
    private val repository: CreditAccountRepository,
    private val enforced: Boolean,
    private val signupGrant: Int = 0,
    private val signupGrantLedger: SignupGrantLedger = NoopSignupGrantLedger,
    /**
     * `signupGrant <= 0` 인 배포에서는 [grantSignupBonus] 가 조회 전에 반환하므로
     * 이 해시기가 실제로 불릴 일이 없다 — 실제 조립(`CreditAccountConfiguration`)은
     * 이 기본값을 쓰지 않고 `signupGrantPepper` 를 넘긴다(`signupGrant > 0` 인데 pepper 가
     * 비어 있으면 그 조립 층의 기동 자기점검이 앱을 띄우지 않는다 — 가입 크레딧 후속
     * §7 결정 3). 기본값을 빈 [Secret] 으로 두지 않는 이유: `SecretKeySpec` 은 길이 0
     * 키를 거부한다(`IllegalArgumentException`) — `signupGrant` 만 켜고 원장·해시기는
     * 대역을 쓰지 않는 기존 테스트(`AuthServiceTest`·`SocialLoginServiceTest`)가 이
     * 기본값을 그대로 쓰다 무관한 예외로 깨지지 않게 한다.
     */
    private val emailHasher: SignupGrantEmailHasher = SignupGrantEmailHasher(Secret("unused-default-pepper")),
    /**
     * 가입 크레딧(무료 체험)의 유효기간 — `easydoc.credits.signup-grant-validity`(기본
     * `P1M`, ISO-8601 Period). [grantSignupBonus] 가 이 기간 뒤 **저절로 끝나는**
     * 비갱신 주기를 연다(사용자 확정 2026-09-10 — 무료 이용량은 1개월 이내 종료). 코드에
     * 한 달을 박지 않고 구성값으로 받는다(프로젝트 `CLAUDE.md` 「상수와 구성 관리」).
     */
    private val signupGrantValidity: Period = Period.ofMonths(1),
    /** [grantSignupBonus] 가 주기 종료 시각을 계산하는 데 쓴다. 기본은 시스템 UTC 시계. */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * [grantSignupBonus] 가 [signupGrantValidity] 를 더할 달력 시간대 — 사용량 집계
     * (`UsageQueryService`·`UsageReportService`)가 이미 쓰는 `easydoc.usage.zone`(기본
     * `Asia/Seoul`, [kr.easydoc.infrastructure.usage.UsageProperties])과 **같은 값**을
     * 재사용한다(리뷰 지적 — UTC로 달력 연산을 하면 한국 사용자 기준의 "한 달"과
     * UTC/KST 가 갈리는 매일 9시간 창에서 어긋난다). `application` 은 `infrastructure` 를
     * 의존할 수 없어 이 클래스가 그 상수를 직접 참조하지 못한다 — 그래서 문자열이 아니라
     * 인자로 받는다. 실제 조립(`CreditAccountConfiguration`)은 이 기본값을 쓰지 않고
     * `UsageProperties.zoneId()` 를 그대로 넘긴다(같은 값을 두 곳에서 각자 정하지
     * 않는다는 뜻에서 `emailHasher` 기본값과 같은 자리) — 이 기본값은 시간대를 지정하지
     * 않는 기존 테스트가 무관한 예외로 깨지지 않게 하는 것이 유일한 목적이다.
     */
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
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

    /**
     * 운영자 수동 부여·조정. `credits` 가 0 이상이면 GRANT, 음수면 ADJUST(저장소가 가른다).
     *
     * [actorUserId] 는 감사 흔적이다(어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md`
     * §2 결정 3) — 관리자 화면 경유(`POST /admin/workspaces/{workspace_id}/credits`)는
     * 관리자 id를 채우고, `credit-grant` 운영 프로필(자동 경로)은 `null`로 둔다.
     */
    @Suppress("LongParameterList")
    fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID? = null,
    ): Int = repository.grant(workspaceId, ownerUserId, credits, reason, note, actorUserId)

    /**
     * 새 주기를 연다 — `balance`를 [allowance] 로 설정한다(더하지 않는다). 운영자 CLI의
     * `--cycle-ends-at` 경로([kr.easydoc.api.credit.CreditGrantRunner])가 부른다.
     * [CreditAccountRepository.setAllowance] KDoc 참고 — [renews] 가 주기 종료 배치의
     * 갱신/종료 갈래를 가른다.
     */
    @Suppress("LongParameterList")
    fun setAllowance(
        workspaceId: UUID,
        ownerUserId: UUID,
        allowance: Int,
        cycleEndsAt: Instant,
        renews: Boolean,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID? = null,
    ): Int =
        repository.setAllowance(workspaceId, ownerUserId, allowance, cycleEndsAt, renews, reason, note, actorUserId)

    /**
     * 가입 시 기본 워크스페이스에 [signupGrant] 만큼의 **무료 체험 주기**를 연다 —
     * `AuthService.signup`·`SocialLoginService.callback` 의 새 사용자 갈래가 **둘 다** 이
     * 메서드 하나만 부른다(리뷰 2026-09-07 — 두 경로가 각자 `signupGrant` 를 인자로
     * 받으면 배선이 갈릴 수 있었다). `signupGrant` 가 0 이하면 아무것도 하지 않는다.
     *
     * **2026-09-10 사용자 확정으로 「더하기」(`grant`)가 아니라 [setAllowance] 를 쓴다** —
     * 무료 이용량은 결제주기가 아니라 [signupGrantValidity](기본 1개월) 뒤 **저절로
     * 끝나야 하고 다시 채워지면 안 된다.** `renews = false` 로 열어, 주기 종료 배치
     * ([kr.easydoc.application.credit.CreditCycleReset])가 그 시각에 잔액·이용량을 0으로
     * 만들고 주기를 닫는다(플랜처럼 갱신되지 않는다). 가입 직후 새 워크스페이스는 잔액이
     * 항상 0이므로 「설정」과 「더하기」가 이 경로에서는 같은 결과다.
     *
     * [signupGrantValidity] 를 더하는 달력은 [zoneId] 기준이다(리뷰 지적 — UTC로 계산하면
     * KST 자정과 어긋나는 매일 9시간 창이 생긴다). `JdbcCreditCycleReset` 도 같은 값
     * (`easydoc.usage.zone`)을 쓴다 — 두 계산이 서로 다른 "한 달"을 쓰면 안 된다.
     *
     * **가입 크레딧은 계정당(정확히는 이메일당) 한 번이다**(가입 크레딧 후속 §7 결정 1) —
     * 부여하기 **전에** [signupGrantLedger] 로 [normalizedEmail] 이 전에 가입 부여를 받은
     * 적이 있는지 본다. 있으면 [CreditAccountRepository.markSignupGrantSkipped] 로 그
     * 사실만 계정 행에 남기고 부여하지 않는다(거래도 만들지 않는다 — §7 결정 7). 없으면
     * 평소대로 부여한 뒤 **그 직후** 기록한다 — 탈퇴 시점이 아니라 부여 시점에 기록해야
     * "탈퇴하지 않고 워크스페이스만 다시 만드는" 경로도 놓치지 않는다(§7 결정 1 후반).
     *
     * [normalizedEmail] 은 **호출자가 이미 정규화한** 값이어야 한다 — [SignupGrantEmailHasher]
     * KDoc.
     */
    fun grantSignupBonus(
        workspaceId: UUID,
        ownerUserId: UUID,
        normalizedEmail: String,
    ) {
        if (signupGrant <= 0) return
        val emailHash = emailHasher.hash(normalizedEmail)
        if (signupGrantLedger.hasGranted(emailHash)) {
            repository.markSignupGrantSkipped(workspaceId)
            return
        }
        val cycleEndsAt = ZonedDateTime.ofInstant(Instant.now(clock), zoneId).plus(signupGrantValidity).toInstant()
        repository.setAllowance(
            workspaceId,
            ownerUserId,
            signupGrant,
            cycleEndsAt,
            renews = false,
            reason = CreditReason.SIGNUP,
            note = null,
            actorUserId = null,
        )
        signupGrantLedger.record(emailHash)
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
                // 이메일 미인증이면 항상 false — CreditAccountRow KDoc·CreditAccountView.
                // signupGrantSkipped KDoc(가입 크레딧 후속 §7 결정 5).
                signupGrantSkipped = row.emailVerified && row.signupGrantSkipped,
                allowance = row.allowance,
                cycleEndsAt = row.cycleEndsAt,
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
        actorUserId: UUID?,
    ): Int = credits

    override fun setAllowance(
        workspaceId: UUID,
        ownerUserId: UUID,
        allowance: Int,
        cycleEndsAt: Instant,
        renews: Boolean,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ): Int = allowance

    override fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountRow? = null

    override fun consistencyViolations(): List<CreditConsistencyViolation> = emptyList()

    override fun ownerOf(workspaceId: UUID): UUID? = null

    override fun markSignupGrantSkipped(workspaceId: UUID) = Unit
}

/**
 * 크레딧과 무관한 테스트가 `DocumentService`·`ProcessConversionJob` 을 세울 때 쓰는
 * 한 줄 조립 — [NoopCreditAccountRepository] KDoc의 「`testFixtures` 가 없다」 사유로
 * main 소스에 둔다. 여덟 곳의 실 DB 테스트(`infrastructure`)와 순수 단위 테스트
 * (`application`)가 이 함수 하나를 공유해 대역을 손으로 다시 짜지 않는다.
 */
fun noCredits(): CreditAccountService = CreditAccountService(NoopCreditAccountRepository, enforced = false)

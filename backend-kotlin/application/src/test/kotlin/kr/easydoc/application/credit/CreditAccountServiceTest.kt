package kr.easydoc.application.credit

import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [CreditAccountService] 의 분기를 잰다 — Spring 도 DB 도 없이, [FakeCreditAccountRepository] 위에서.
 *
 * 재시도(성공도 실패도 아닌 경로)가 이 서비스를 아예 부르지 않는다는 호출자 계약은 이
 * 서비스 층에 재현할 상태가 없다 — `ProcessConversionJobTest.CreditAccounting` 의
 * 「재시도 예정은 손대지 않는다」 케이스가 실제로 잰다.
 */
class CreditAccountServiceTest {
    private val ownerId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val documentId = UUID.randomUUID()
    private val conversionId = UUID.randomUUID()

    @Test
    @DisplayName("가용 크레딧이 충분하면 예약이 성공한다")
    fun `예약 성공`() {
        val repo = FakeCreditAccountRepository(balance = 10)
        val service = CreditAccountService(repo, enforced = true)

        val reservation = service.reserve(ownerId, workspaceId, documentId, Credits(3))

        assertThat(reservation.balance).isEqualTo(10)
        assertThat(reservation.reserved).isEqualTo(3)
        assertThat(reservation.available).isEqualTo(7)
        assertThat(repo.reserveCalls).containsExactly(Credits(3))
    }

    @Test
    @DisplayName("집행 중 가용 크레딧이 모자라면 402 로 이어지는 예외를 던진다")
    fun `예약 실패는 InsufficientCreditsException`() {
        val repo = FakeCreditAccountRepository(balance = 1)
        val service = CreditAccountService(repo, enforced = true)

        assertThatThrownBy { service.reserve(ownerId, workspaceId, documentId, Credits(3)) }
            .isInstanceOf(InsufficientCreditsException::class.java)
            .extracting("available", "required")
            .containsExactly(1, 3)
    }

    @Test
    @DisplayName("집행이 꺼져 있으면 가용 크레딧이 모자라도 예약이 성공하고 잔액이 음수로 기록된다")
    fun `집행 꺼짐은 음수를 허용한다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val service = CreditAccountService(repo, enforced = false)

        val reservation = service.reserve(ownerId, workspaceId, documentId, Credits(5))

        assertThat(reservation.reserved).isEqualTo(5)
        assertThat(reservation.available).isEqualTo(-5)
    }

    @Test
    @DisplayName("소비는 잔액과 예약을 함께 줄인다")
    fun `소비는 잔액과 예약을 함께 줄인다`() {
        val repo = FakeCreditAccountRepository(balance = 10)
        val service = CreditAccountService(repo, enforced = true)
        service.reserve(ownerId, workspaceId, documentId, Credits(3))

        service.consume(workspaceId, ownerId, documentId, conversionId, Credits(3))

        assertThat(repo.consumeCalls).containsExactly(Credits(3))
        assertThat(repo.balance).isEqualTo(7)
        assertThat(repo.reserved).isEqualTo(0)
    }

    @Test
    @DisplayName("해제는 예약만 되돌리고 잔액은 그대로다")
    fun `해제는 예약만 되돌린다`() {
        val repo = FakeCreditAccountRepository(balance = 10)
        val service = CreditAccountService(repo, enforced = true)
        service.reserve(ownerId, workspaceId, documentId, Credits(3))

        service.release(workspaceId, ownerId, documentId, conversionId, Credits(3))

        assertThat(repo.releaseCalls).containsExactly(Credits(3))
        assertThat(repo.balance).isEqualTo(10)
        assertThat(repo.reserved).isEqualTo(0)
    }

    @Test
    @DisplayName("0 크레딧(V15 이전 문서)의 소비·해제는 저장소를 부르지 않는다")
    fun `0 크레딧은 저장소를 부르지 않는다`() {
        val repo = FakeCreditAccountRepository(balance = 10)
        val service = CreditAccountService(repo, enforced = true)

        service.consume(workspaceId, ownerId, documentId, conversionId, Credits(0))
        service.release(workspaceId, ownerId, documentId, conversionId, Credits(0))

        assertThat(repo.consumeCalls).isEmpty()
        assertThat(repo.releaseCalls).isEmpty()
    }

    @Test
    @DisplayName("부여는 저장소에 그대로 위임한다")
    fun `부여는 위임한다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val service = CreditAccountService(repo, enforced = true)

        val resultBalance = service.grant(workspaceId, ownerId, 50, CreditReason.SIGNUP, note = null)

        assertThat(resultBalance).isEqualTo(50)
        assertThat(repo.balance).isEqualTo(50)
        assertThat(repo.grantCalls).containsExactly(Triple(50, CreditReason.SIGNUP, null as String?))
    }

    @Test
    @DisplayName("가입 부여(signupGrant)가 설정되면 grantSignupBonus 가 grant 를 부른다")
    fun `가입 부여가 설정되면 부른다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val service = CreditAccountService(repo, enforced = true, signupGrant = 50)

        service.grantSignupBonus(workspaceId, ownerId, "user@example.com")

        assertThat(repo.grantCalls).containsExactly(Triple(50, CreditReason.SIGNUP, null as String?))
    }

    @Test
    @DisplayName("가입 부여가 0 이하면 grantSignupBonus 는 저장소를 부르지 않는다")
    fun `가입 부여 0은 부르지 않는다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val service = CreditAccountService(repo, enforced = true, signupGrant = 0)

        service.grantSignupBonus(workspaceId, ownerId, "user@example.com")

        assertThat(repo.grantCalls).isEmpty()
    }

    @Test
    @DisplayName("가입 부여 전에 원장을 조회한다 — 없으면 부여하고 부여 직후 기록한다")
    fun `원장에 없으면 부여하고 기록한다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val ledger = FakeSignupGrantLedger()
        val hasher = SignupGrantEmailHasher(Secret("pepper"))
        val service =
            CreditAccountService(
                repo,
                enforced = true,
                signupGrant = 50,
                signupGrantLedger = ledger,
                emailHasher = hasher,
            )

        service.grantSignupBonus(workspaceId, ownerId, "user@example.com")

        assertThat(repo.grantCalls).containsExactly(Triple(50, CreditReason.SIGNUP, null as String?))
        assertThat(repo.markSkippedCalls).isEmpty()
        assertThat(ledger.recorded).containsExactly(hasher.hash("user@example.com"))
    }

    @Test
    @DisplayName("원장에 이미 있으면 부여하지 않고 건너뛴 사실만 남긴다 — 거래를 만들지 않는다")
    fun `원장에 있으면 건너뛴 사실만 남긴다`() {
        val repo = FakeCreditAccountRepository(balance = 0)
        val hasher = SignupGrantEmailHasher(Secret("pepper"))
        val ledger = FakeSignupGrantLedger(alreadyGranted = setOf(hasher.hash("user@example.com")))
        val service =
            CreditAccountService(
                repo,
                enforced = true,
                signupGrant = 50,
                signupGrantLedger = ledger,
                emailHasher = hasher,
            )

        service.grantSignupBonus(workspaceId, ownerId, "user@example.com")

        assertThat(repo.grantCalls).isEmpty()
        assertThat(repo.markSkippedCalls).containsExactly(workspaceId)
        assertThat(ledger.recorded).isEmpty()
    }

    @Test
    @DisplayName("읽기 결과의 signup_grant_skipped 는 이메일 인증 전에는 항상 거짓이다")
    fun `이메일 미인증이면 항상 거짓`() {
        val repo = FakeCreditAccountRepository(balance = 0, signupGrantSkipped = true, emailVerified = false)
        val service = CreditAccountService(repo, enforced = true)

        val view = service.read(ownerId, workspaceId)

        assertThat(view.signupGrantSkipped).isFalse()
    }

    @Test
    @DisplayName("읽기 결과의 signup_grant_skipped 는 이메일 인증 뒤에는 원값 그대로다")
    fun `이메일 인증 뒤에는 원값 그대로`() {
        val repo = FakeCreditAccountRepository(balance = 0, signupGrantSkipped = true, emailVerified = true)
        val service = CreditAccountService(repo, enforced = true)

        val view = service.read(ownerId, workspaceId)

        assertThat(view.signupGrantSkipped).isTrue()
    }

    @Test
    @DisplayName("읽기는 없거나 내 것이 아니면 404 다")
    fun `없는 계정은 404`() {
        val repo = FakeCreditAccountRepository(balance = 0, exists = false)
        val service = CreditAccountService(repo, enforced = true)

        assertThatThrownBy { service.read(ownerId, workspaceId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    @DisplayName("읽기 결과는 enforced 값을 서비스 설정에서 채운다")
    fun `읽기는 enforced 를 채운다`() {
        val repo = FakeCreditAccountRepository(balance = 10)
        val service = CreditAccountService(repo, enforced = true)
        service.reserve(ownerId, workspaceId, documentId, Credits(4))

        val view = service.read(ownerId, workspaceId)

        assertThat(view.balance).isEqualTo(10)
        assertThat(view.reserved).isEqualTo(4)
        assertThat(view.available).isEqualTo(6)
        assertThat(view.enforced).isTrue()
    }
}

/**
 * 최소 상태만 흉내 내는 대역 — 실제 SQL 불변식은 `JdbcCreditAccountRepositoryTest` 가 잰다.
 * [NoopCreditAccountRepository] 에 위임하고 이 서비스가 실제로 판정하는 갈래(잔액·예약
 * 상태 전이, 부여 호출 인자)만 재정의한다(리뷰 2026-09-07 — 다섯 대역의 복붙을 줄인다).
 */
private class FakeCreditAccountRepository(
    var balance: Int,
    private val exists: Boolean = true,
    private val signupGrantSkipped: Boolean = false,
    private val emailVerified: Boolean = false,
) : CreditAccountRepository by NoopCreditAccountRepository {
    var reserved: Int = 0
    val reserveCalls = mutableListOf<Credits>()
    val consumeCalls = mutableListOf<Credits>()
    val releaseCalls = mutableListOf<Credits>()
    val grantCalls = mutableListOf<Triple<Int, CreditReason, String?>>()
    val markSkippedCalls = mutableListOf<UUID>()

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        amount: Credits,
        enforced: Boolean,
    ): ReservationResult {
        val available = balance - reserved
        if (enforced && available < amount.amount) {
            return ReservationResult.Insufficient(available)
        }
        reserveCalls += amount
        reserved += amount.amount
        return ReservationResult.Reserved(balance, reserved)
    }

    override fun consume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        consumeCalls += amount
        balance -= amount.amount
        reserved -= amount.amount
    }

    override fun release(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        releaseCalls += amount
        reserved -= amount.amount
    }

    override fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ): Int {
        grantCalls += Triple(credits, reason, note)
        balance += credits
        return balance
    }

    override fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountRow? =
        if (!exists) {
            null
        } else {
            CreditAccountRow(workspaceId, balance, reserved, emptyList(), signupGrantSkipped, emailVerified)
        }

    override fun markSignupGrantSkipped(workspaceId: UUID) {
        markSkippedCalls += workspaceId
    }
}

/** [SignupGrantLedger] 대역 — 부여를 이미 받은 이메일 해시 집합을 흉내 낸다. */
private class FakeSignupGrantLedger(alreadyGranted: Set<String> = emptySet()) : SignupGrantLedger {
    private val granted = alreadyGranted.toMutableSet()
    val recorded = mutableListOf<String>()

    override fun hasGranted(emailHash: String): Boolean = emailHash in granted

    override fun record(emailHash: String) {
        recorded += emailHash
        granted += emailHash
    }
}

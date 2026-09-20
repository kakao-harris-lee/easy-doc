package kr.easydoc.application.auth

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PhoneVerificationServiceTest {
    @Test
    fun `010 번호 인증은 문자를 보내고 최초 번호에 5크레딧을 지급한다`() {
        val world = PhoneWorld()

        world.service.request(world.userId, "010-1234-5678")
        val result = world.service.confirm(world.userId, world.codes.code)

        assertThat(world.sms.phone).isEqualTo("01012345678")
        assertThat(result.grantedCredits).isEqualTo(5)
        assertThat(world.granted).isEqualByComparingTo("5")
        assertThat(world.users.current.phoneVerifiedAt).isNotNull()
        assertThat(world.users.current.pendingPhoneFingerprint).isNull()
    }

    @Test
    fun `이미 체험을 받은 번호는 인증되지만 크레딧을 다시 받지 않는다`() {
        val world = PhoneWorld(claimGrant = false)
        world.service.request(world.userId, "01012345678")

        val result = world.service.confirm(world.userId, world.codes.code)

        assertThat(result.grantedCredits).isZero()
        assertThat(world.granted).isZero()
        assertThat(world.users.current.phoneVerifiedAt).isNotNull()
    }

    @Test
    fun `이메일 미인증과 국내 010이 아닌 번호는 발송 전에 거절한다`() {
        val unverified = PhoneWorld(emailVerified = false)
        assertThatThrownBy { unverified.service.request(unverified.userId, "01012345678") }
            .isInstanceOf(EmailNotVerifiedException::class.java)
        assertThatThrownBy { PhoneWorld().service.request(UUID.randomUUID(), "01112345678") }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `SMS 발송 실패는 이번에 발급한 코드만 회수하고 지문을 지운 뒤 다시 던진다`() {
        val world = PhoneWorld()
        world.sms.shouldFail = true

        assertThatThrownBy { world.service.request(world.userId, "01012345678") }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)

        assertThat(world.users.current.pendingPhoneFingerprint).isNull()
    }

    @Test
    fun `오답 코드는 크레딧 지급 없이 거절된다`() {
        val world = PhoneWorld()
        world.service.request(world.userId, "01012345678")

        assertThatThrownBy { world.service.confirm(world.userId, "000000") }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
        assertThat(world.granted).isZero()
        assertThat(world.users.current.phoneVerifiedAt).isNull()
    }

    @Test
    fun `오답을 최대 횟수만큼 내면 이후 정답도 거절된다`() {
        val world = PhoneWorld()
        world.service.request(world.userId, "01012345678")

        repeat(5) {
            assertThatThrownBy { world.service.confirm(world.userId, "000000") }
                .isInstanceOf(InvalidVerificationCodeException::class.java)
        }

        assertThatThrownBy { world.service.confirm(world.userId, world.codes.code) }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
    }

    @Test
    fun `늦게 실패한 이전 발송은 최신 발급의 대기 지문을 지우지 않는다`() {
        val world = PhoneWorld()
        world.sms.onFirstSend = {
            world.service.request(world.userId, "01012345678")
            throw ExternalServiceUnavailableException(RecordingPhoneSms.SEND_FAILURE_MESSAGE)
        }

        assertThatThrownBy { world.service.request(world.userId, "01012345678") }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)

        assertThat(world.users.current.pendingPhoneVerificationId).isEqualTo(world.codes.latestId)
        assertThat(world.service.confirm(world.userId, world.codes.code).grantedCredits).isEqualTo(5)
    }

    @Test
    fun `이미 인증된 계정은 다시 확인할 수 없다`() {
        val world = PhoneWorld()
        world.service.request(world.userId, "01012345678")
        world.service.confirm(world.userId, world.codes.code)

        assertThatThrownBy { world.service.confirm(world.userId, world.codes.code) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `대기 중인 지문이 없으면 확인은 거절된다`() {
        val world = PhoneWorld()

        assertThatThrownBy { world.service.confirm(world.userId, "123456") }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
    }

    @Test
    fun `같은 번호를 확인하는 두 번째 계정은 인증되지만 체험 크레딧을 받지 않는다`() {
        val ledger = StatefulPhoneTrialGrantLedger()
        val first = PhoneWorld(grants = ledger)
        val second = PhoneWorld(grants = ledger)

        first.service.request(first.userId, "01012345678")
        first.service.confirm(first.userId, first.codes.code)

        second.service.request(second.userId, "01012345678")
        val result = second.service.confirm(second.userId, second.codes.code)

        assertThat(result.grantedCredits).isZero()
        assertThat(second.granted).isZero()
        assertThat(second.users.current.phoneVerifiedAt).isNotNull()
    }

    @Test
    fun `기본 작업 공간을 찾지 못하면 StorageException 이고 크레딧을 지급하지 않는다`() {
        val world = PhoneWorld(hasDefaultWorkspace = false)
        world.service.request(world.userId, "01012345678")

        assertThatThrownBy { world.service.confirm(world.userId, world.codes.code) }
            .isInstanceOf(StorageException::class.java)
        assertThat(world.granted).isZero()
    }
}

private class StatefulPhoneTrialGrantLedger : PhoneTrialGrantLedger {
    private val claimed = mutableSetOf<String>()

    override fun claim(fingerprint: String): Boolean = claimed.add(fingerprint)
}

private class PhoneWorld(
    emailVerified: Boolean = true,
    claimGrant: Boolean = true,
    grants: PhoneTrialGrantLedger = PhoneTrialGrantLedger { claimGrant },
    hasDefaultWorkspace: Boolean = true,
) {
    val userId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    val users = PhoneUsers(userId, emailVerified)
    val codes = PhoneCodes()
    val sms = RecordingPhoneSms()
    var granted: BigDecimal = BigDecimal.ZERO
    private val creditService =
        CreditAccountService(
            object : kr.easydoc.application.credit.CreditAccountRepository by NoopCreditAccountRepository {
                override fun grant(
                    workspaceId: UUID,
                    ownerUserId: UUID,
                    credits: BigDecimal,
                    reason: CreditReason,
                    note: String?,
                    actorUserId: UUID?,
                ): BigDecimal {
                    granted += credits
                    return granted
                }

                override fun setAllowance(
                    workspaceId: UUID,
                    ownerUserId: UUID,
                    allowance: BigDecimal,
                    cycleEndsAt: Instant,
                    renews: Boolean,
                    reason: CreditReason,
                    note: String?,
                    actorUserId: UUID?,
                ): BigDecimal {
                    granted = allowance
                    return granted
                }
            },
            enforced = true,
        )
    val service =
        PhoneVerificationService(
            users = users,
            workspaces = PhoneWorkspaceRepository(userId, workspaceId, hasDefaultWorkspace),
            codes = codes,
            sms = sms,
            credits = creditService,
            grants = grants,
            hasher = PhoneFingerprintHasher(Secret("test-pepper")),
            transaction =
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                },
            codeTtl = Duration.ofMinutes(5),
            resendCooldown = Duration.ofSeconds(60),
            maxAttempts = 5,
            trialCredits = 5,
        )
}

private class PhoneUsers(
    private val userId: UUID,
    emailVerified: Boolean,
) : UserRepository {
    var current =
        User(
            id = userId,
            email = "user@example.test",
            createdAt = Instant.EPOCH,
            emailVerifiedAt = Instant.EPOCH.takeIf { emailVerified },
            hasPassword = true,
        )

    override fun findById(id: UUID): User? = current.takeIf { id == userId }

    override fun lockForUpdate(id: UUID): User? = findById(id)

    override fun setPendingPhoneFingerprint(
        userId: UUID,
        fingerprint: String,
        verificationId: UUID,
    ) {
        current = current.copy(pendingPhoneFingerprint = fingerprint, pendingPhoneVerificationId = verificationId)
    }

    override fun clearPendingPhoneFingerprint(
        userId: UUID,
        verificationId: UUID,
    ) {
        if (current.pendingPhoneVerificationId == verificationId) {
            current = current.copy(pendingPhoneFingerprint = null, pendingPhoneVerificationId = null)
        }
    }

    override fun markPhoneVerified(userId: UUID): Boolean {
        current =
            current.copy(
                phoneVerifiedAt = Instant.EPOCH,
                pendingPhoneFingerprint = null,
                pendingPhoneVerificationId = null,
            )
        return true
    }

    override fun findByEmail(email: String): StoredUser? = error("not used")

    override fun exists(id: UUID): Boolean = error("not used")

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("not used")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = error("not used")

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("not used")

    override fun markEmailVerified(userId: UUID): Boolean = error("not used")
}

private class PhoneCodes : PhoneVerificationCodeStore {
    var code = "123456"
        private set
    var latestId: UUID? = null
        private set
    private var active = false
    private var activeId: UUID? = null
    private var attempts = 0

    override fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String = issuePhoneVerification(userId, ttl, cooldown).code

    override fun issuePhoneVerification(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): IssuedPhoneVerification {
        code = (code.toInt() + 1).toString().padStart(6, '0')
        val id = UUID.randomUUID()
        active = true
        activeId = id
        attempts = 0
        latestId = id
        return IssuedPhoneVerification(id, code)
    }

    override fun revokePhoneVerification(
        userId: UUID,
        verificationId: UUID,
    ): Boolean {
        if (active && activeId == verificationId) {
            active = false
            return true
        }
        return false
    }

    override fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean {
        if (!active || attempts >= maxAttempts) return false
        attempts++
        return (code == this.code).also { if (it) active = false }
    }

    /** 실물([kr.easydoc.infrastructure.auth.JdbcOneTimeCodeStore.revoke])과 같은 계약 —
     * [code] 가 일치할 때만 활성 코드를 지운다. */
    override fun revoke(
        userId: UUID,
        code: String,
    ) {
        if (code == this.code) active = false
    }
}

private class RecordingPhoneSms : PhoneVerificationSmsSender {
    var phone: String? = null
    var shouldFail: Boolean = false
    var onFirstSend: (() -> Unit)? = null

    override fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    ) {
        onFirstSend?.also { onFirstSend = null }?.invoke()
        if (shouldFail) throw ExternalServiceUnavailableException(SEND_FAILURE_MESSAGE)
        phone = phoneNumber
    }

    companion object {
        const val SEND_FAILURE_MESSAGE = "인증 문자를 보내지 못했습니다"
    }
}

private class PhoneWorkspaceRepository(
    private val ownerId: UUID,
    private val workspaceId: UUID,
    /** `false` 면 소유자가 맞아도 기본 작업 공간이 없는 것으로 다룬다 — 저장소 결손 방어선을 잰다. */
    private val hasDefaultWorkspace: Boolean = true,
) : WorkspaceRepository {
    override fun listOwned(ownerId: UUID): List<WorkspaceListing> =
        if (ownerId == this.ownerId) {
            listOf(WorkspaceListing(Workspace(workspaceId, "기본 작업 공간", Instant.EPOCH), 0))
        } else {
            emptyList()
        }

    override fun findDefaultId(ownerId: UUID): UUID? =
        workspaceId.takeIf { ownerId == this.ownerId && hasDefaultWorkspace }

    override fun createDefault(userId: UUID): UUID = error("not used")

    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace = error("not used")

    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace? = error("not used")

    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState? = error("not used")

    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean = error("not used")
}

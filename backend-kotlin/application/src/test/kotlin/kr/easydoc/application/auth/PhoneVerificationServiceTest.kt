package kr.easydoc.application.auth

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
        assertThat(world.granted).isEqualTo(5)
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
}

private class PhoneWorld(
    emailVerified: Boolean = true,
    claimGrant: Boolean = true,
) {
    val userId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    val users = PhoneUsers(userId, emailVerified)
    val codes = PhoneCodes()
    val sms = RecordingPhoneSms()
    var granted: Int = 0
    private val creditService =
        CreditAccountService(
            object : kr.easydoc.application.credit.CreditAccountRepository by NoopCreditAccountRepository {
                override fun grant(
                    workspaceId: UUID,
                    ownerUserId: UUID,
                    credits: Int,
                    reason: CreditReason,
                    note: String?,
                    actorUserId: UUID?,
                ): Int {
                    granted += credits
                    return granted
                }
            },
            enforced = true,
        )
    val service =
        PhoneVerificationService(
            users = users,
            workspaces = PhoneWorkspaceRepository(userId, workspaceId),
            codes = codes,
            sms = sms,
            credits = creditService,
            grants = PhoneTrialGrantLedger { claimGrant },
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
    ) {
        current = current.copy(pendingPhoneFingerprint = fingerprint)
    }

    override fun clearPendingPhoneFingerprint(
        userId: UUID,
        fingerprint: String,
    ) {
        if (current.pendingPhoneFingerprint == fingerprint) current = current.copy(pendingPhoneFingerprint = null)
    }

    override fun markPhoneVerified(userId: UUID): Boolean {
        current = current.copy(phoneVerifiedAt = Instant.EPOCH, pendingPhoneFingerprint = null)
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
    val code = "123456"
    private var active = false

    override fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String = code.also { active = true }

    override fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean = (active && code == this.code).also { if (it) active = false }

    override fun revoke(userId: UUID) {
        active = false
    }
}

private class RecordingPhoneSms : PhoneVerificationSmsSender {
    var phone: String? = null

    override fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    ) {
        phone = phoneNumber
    }
}

private class PhoneWorkspaceRepository(
    private val ownerId: UUID,
    private val workspaceId: UUID,
) : WorkspaceRepository {
    override fun listOwned(ownerId: UUID): List<WorkspaceListing> =
        if (ownerId == this.ownerId) {
            listOf(WorkspaceListing(Workspace(workspaceId, "기본 작업 공간", Instant.EPOCH), 0))
        } else {
            emptyList()
        }

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

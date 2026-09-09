package kr.easydoc.application.account

import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 회원 탈퇴 유스케이스 — `POST /auth/me/deletion`(계획
 * `docs/plans/2026-09-09-account-deletion.md` §2). Spring도 DB도 없이 대역으로 돈다.
 * 실 PostgreSQL로만 잴 수 있는 것들(행 수, FK CASCADE·NO ACTION 통과 여부)은
 * `JdbcAccountDeletionRepositoryTest`가 맡는다.
 */
class DeleteAccountServiceTest {
    @Test
    @DisplayName("비밀번호 계정이 올바른 비밀번호와 확인 문구로 탈퇴하면 저장소의 삭제 순서가 지켜진다 — 수용 기준 1")
    fun `비밀번호 계정 탈퇴 성공`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = PasswordHash("existing-hash"))

        assertThatCode {
            world.service.deleteAccount(userId, "correct-password", DeleteAccountService.CONFIRMATION_PHRASE)
        }.doesNotThrowAnyException()

        assertThat(world.accounts.feedbackDeletedFor).containsExactly(userId)
        assertThat(world.accounts.usersDeleted).containsExactly(userId)
        assertThat(world.accounts.feedbackDeletedBeforeUserDeleted).isTrue()
    }

    @Test
    @DisplayName("소셜 전용 계정은 확인 문구만으로 탈퇴된다 — 수용 기준 2")
    fun `소셜 전용 계정은 비밀번호 없이 탈퇴된다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)

        assertThatCode {
            world.service.deleteAccount(userId, rawPassword = null, DeleteAccountService.CONFIRMATION_PHRASE)
        }.doesNotThrowAnyException()

        assertThat(world.accounts.usersDeleted).containsExactly(userId)
    }

    @Test
    @DisplayName("비밀번호가 틀리면 422이고 아무것도 지워지지 않는다 — 수용 기준 3")
    fun `비밀번호가 틀리면 422다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = PasswordHash("existing-hash"))

        assertThatThrownBy {
            world.service.deleteAccount(userId, "wrong-password", DeleteAccountService.CONFIRMATION_PHRASE)
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(DeleteAccountService.PASSWORD_MISMATCH_MESSAGE)
        assertThat(world.accounts.usersDeleted).isEmpty()
        assertThat(world.accounts.feedbackDeletedFor).isEmpty()
    }

    @Test
    @DisplayName("비밀번호 계정인데 비밀번호를 보내지 않으면 422다")
    fun `비밀번호를 보내지 않으면 422다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = PasswordHash("existing-hash"))

        assertThatThrownBy {
            world.service.deleteAccount(userId, rawPassword = null, DeleteAccountService.CONFIRMATION_PHRASE)
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(DeleteAccountService.PASSWORD_MISMATCH_MESSAGE)
        assertThat(world.accounts.usersDeleted).isEmpty()
    }

    @Test
    @DisplayName("확인 문구가 다르면 422이고 아무것도 지워지지 않는다 — 수용 기준 3, 락도 잡지 않는다")
    fun `확인 문구가 다르면 422다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)

        assertThatThrownBy { world.service.deleteAccount(userId, rawPassword = null, "확인 안 함") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(DeleteAccountService.CONFIRMATION_MISMATCH_MESSAGE)
        assertThat(world.accounts.usersDeleted).isEmpty()
        assertThat(world.accounts.lockCount).isZero()
    }

    @Test
    @DisplayName("관리자 계정은 409이고 아무것도 지워지지 않는다 — 수용 기준 4")
    fun `관리자 계정은 409다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = true, passwordHash = null)

        assertThatThrownBy {
            world.service.deleteAccount(userId, rawPassword = null, DeleteAccountService.CONFIRMATION_PHRASE)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(DeleteAccountService.ADMIN_CANNOT_DELETE_MESSAGE)
        assertThat(world.accounts.usersDeleted).isEmpty()
        assertThat(world.accounts.feedbackDeletedFor).isEmpty()
    }

    @Test
    @DisplayName("토큰은 유효한데 계정이 이미 지워졌으면 401로 매핑될 예외다")
    fun `계정이 없으면 401이다`() {
        val world = DeleteAccountWorld()

        assertThatThrownBy {
            world.service.deleteAccount(UUID.randomUUID(), null, DeleteAccountService.CONFIRMATION_PHRASE)
        }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("처리 대기 세금계산서 요청이 있으면 운영자 메일이 한 통, 요청 id만 담는다 — 수용 기준 8")
    fun `처리 대기 요청이 있으면 운영자 메일을 보낸다`() {
        val world = DeleteAccountWorld(operatorEmail = "ops@example.test")
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)
        val pendingId = UUID.randomUUID()
        world.accounts.pending[userId] = listOf(pendingId)

        world.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)

        assertThat(world.mail.sent).hasSize(1)
        val sent = world.mail.sent.single()
        assertThat(sent.textBody).contains(pendingId.toString())
    }

    @Test
    @DisplayName("처리 대기 요청이 없으면 운영자 메일을 보내지 않는다")
    fun `처리 대기 요청이 없으면 메일이 없다`() {
        val world = DeleteAccountWorld(operatorEmail = "ops@example.test")
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)

        world.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)

        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("운영자 메일 발송이 실패해도 탈퇴는 이미 끝나 있다 — 수용 기준 8")
    fun `메일 발송 실패가 탈퇴를 막지 않는다`() {
        val world = DeleteAccountWorld(operatorEmail = "ops@example.test")
        world.mail.failNext = true
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)
        world.accounts.pending[userId] = listOf(UUID.randomUUID())

        assertThatCode {
            world.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)
        }.doesNotThrowAnyException()
        assertThat(world.accounts.usersDeleted).containsExactly(userId)
    }

    @Test
    @DisplayName("운영자 메일 주소가 비어 있으면 처리 대기 요청이 있어도 발송을 시도하지 않는다")
    fun `운영자 메일 미설정이면 발송하지 않는다`() {
        val world = DeleteAccountWorld(operatorEmail = "")
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)
        world.accounts.pending[userId] = listOf(UUID.randomUUID())

        world.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)

        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("검증과 삭제가 한 트랜잭션 안에서 일어난다")
    fun `한 트랜잭션 안에서 처리된다`() {
        val world = DeleteAccountWorld()
        val userId = world.accounts.seed(isAdmin = false, passwordHash = null)

        world.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)

        assertThat(world.transaction.callCount).isEqualTo(1)
    }
}

private class DeleteAccountWorld(operatorEmail: String = "") {
    val accounts = FakeAccountDeletionRepository()
    val transaction = RecordingDeletionTransactionRunner()
    val mail = RecordingDeletionMailSender()
    val service =
        DeleteAccountService(
            accounts = accounts,
            passwords = StubDeletionPasswordHasher(),
            transaction = transaction,
            mail = mail,
            operatorEmail = operatorEmail,
        )
}

private class RecordingDeletionTransactionRunner : TransactionRunner {
    var callCount = 0

    override fun <T> inTransaction(block: () -> T): T {
        callCount++
        return block()
    }
}

private class RecordingDeletionMailSender : MailSender {
    val sent: MutableList<OutboundMail> = mutableListOf()
    var failNext = false

    override fun send(message: OutboundMail): MailDelivery {
        if (failNext) {
            failNext = false
            error("발송 실패 대역")
        }
        sent += message
        return MailDelivery.Sent()
    }
}

private class StubDeletionPasswordHasher : PasswordHasher {
    override fun hash(rawPassword: String): PasswordHash = error("이 유스케이스는 해시 계산을 부르지 않는다")

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = rawPassword == "correct-password" && stored.reveal() == "existing-hash"

    override fun needsRehash(stored: PasswordHash): Boolean = error("이 유스케이스는 재해시 판정을 부르지 않는다")

    override fun dummyHash(): PasswordHash = error("이 유스케이스는 더미 해시를 부르지 않는다")
}

private class FakeAccountDeletionRepository : AccountDeletionRepository {
    private val byId = mutableMapOf<UUID, LockedAccount>()
    val pending: MutableMap<UUID, List<UUID>> = mutableMapOf()
    val usersDeleted: MutableList<UUID> = mutableListOf()
    val feedbackDeletedFor: MutableList<UUID> = mutableListOf()
    var lockCount = 0
        private set

    /** 삭제 순서 단언용 — 피드백 삭제가 사용자 삭제보다 먼저였는지. */
    var feedbackDeletedBeforeUserDeleted = false
        private set

    fun seed(
        isAdmin: Boolean,
        passwordHash: PasswordHash?,
    ): UUID {
        val id = UUID.randomUUID()
        byId[id] = LockedAccount(isAdmin, passwordHash)
        return id
    }

    override fun lockForDeletion(userId: UUID): LockedAccount? {
        lockCount++
        return byId[userId]
    }

    override fun pendingInvoiceRequestIds(userId: UUID): List<UUID> = pending[userId] ?: emptyList()

    override fun deleteConversionFeedback(userId: UUID) {
        feedbackDeletedFor += userId
        feedbackDeletedBeforeUserDeleted = userId !in usersDeleted
    }

    override fun deleteUser(userId: UUID) {
        usersDeleted += userId
    }
}

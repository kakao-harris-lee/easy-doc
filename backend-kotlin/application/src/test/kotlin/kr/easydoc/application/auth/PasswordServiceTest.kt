package kr.easydoc.application.auth

import kr.easydoc.application.document.EMAIL_VERIFICATION_REQUIRED_MESSAGE
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 비밀번호 없는 계정(소셜 전용)에 비밀번호를 설정하는 유스케이스 —
 * `POST /auth/password`(backlog §1.4 후속). Spring 도 DB 도 실제 Argon2 도 없이 대역으로 돈다.
 */
class PasswordServiceTest {
    @Test
    @DisplayName("비밀번호가 없는 계정은 새 비밀번호를 설정할 수 있다")
    fun `비밀번호 없는 계정은 설정된다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = false)

        assertThatCode { world.service.set(user.id, "new-password-1") }.doesNotThrowAnyException()

        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("hashed:new-password-1")
    }

    @Test
    @DisplayName("설정 성공은 알림 메일을 한 통 보낸다")
    fun `설정 성공은 알림 메일을 보낸다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = false)

        world.service.set(user.id, "new-password-1")

        assertThat(world.mail.sent).hasSize(1)
        assertThat(
            world.mail.sent
                .single()
                .subject,
        ).isEqualTo(PasswordService.CREATED_SUBJECT)
    }

    @Test
    @DisplayName("이메일이 인증되지 않은 계정은 403으로 매핑될 예외다 — 문서 변환 게이트와 같은 예외·문구")
    fun `이메일 미인증이면 403이다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = false, emailVerified = false)

        assertThatThrownBy { world.service.set(user.id, "new-password-1") }
            .isInstanceOf(EmailNotVerifiedException::class.java)
            .hasMessage(EMAIL_VERIFICATION_REQUIRED_MESSAGE)
        assertThat(world.users.passwordHashOf(user.id)).isNull()
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("이미 비밀번호가 있는 계정은 409로 거절된다 — 재설정을 안내한다")
    fun `이미 비밀번호가 있으면 409다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = true)

        assertThatThrownBy { world.service.set(user.id, "new-password-1") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(PasswordService.ALREADY_HAS_PASSWORD_MESSAGE)
        // 거절됐으니 해시가 갱신되지 않았어야 한다.
        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("existing-hash")
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("8자 미만 비밀번호는 422로 매핑될 예외다 — 잠그기 전에 끊는다")
    fun `짧은 비밀번호는 거절된다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = false)

        assertThatThrownBy { world.service.set(user.id, "short") }
            .isInstanceOf(InvalidInputException::class.java)
        assertThat(world.users.lockCount).isZero()
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("토큰은 유효한데 계정이 삭제됐으면 401로 매핑될 예외다")
    fun `계정이 없으면 401이다`() {
        val world = PasswordWorld()

        assertThatThrownBy { world.service.set(UUID.randomUUID(), "new-password-1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("행 잠금 하나로 확인·해시·갱신이 같은 트랜잭션에서 일어난다")
    fun `한 트랜잭션 안에서 처리된다`() {
        val world = PasswordWorld()
        val user = world.users.seed(hasPassword = false)

        world.service.set(user.id, "new-password-1")

        assertThat(world.users.lockCount).isEqualTo(1)
        assertThat(world.transaction.callCount).isEqualTo(1)
    }
}

private class PasswordWorld {
    val users = PasswordUserRepository()
    val transaction = RecordingPasswordTransactionRunner()
    val mail = RecordingPasswordMailSender()
    val service = PasswordService(users = users, passwords = StubHasher(), mail = mail, transaction = transaction)
}

private class RecordingPasswordTransactionRunner : TransactionRunner {
    var callCount = 0

    override fun <T> inTransaction(block: () -> T): T {
        callCount++
        return block()
    }
}

private class RecordingPasswordMailSender : MailSender {
    val sent: MutableList<OutboundMail> = mutableListOf()

    override fun send(message: OutboundMail): MailDelivery {
        sent += message
        return MailDelivery.Sent()
    }
}

private class StubHasher : PasswordHasher {
    override fun hash(rawPassword: String): PasswordHash = PasswordHash("hashed:$rawPassword")

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = error("이 유스케이스는 검증을 부르지 않는다")

    override fun needsRehash(stored: PasswordHash): Boolean = error("이 유스케이스는 재해시 판정을 부르지 않는다")

    override fun dummyHash(): PasswordHash = error("이 유스케이스는 더미 해시를 부르지 않는다")
}

private class PasswordUserRepository : UserRepository {
    private val byId: MutableMap<UUID, User> = mutableMapOf()
    private val hashById: MutableMap<UUID, PasswordHash> = mutableMapOf()
    var lockCount = 0
        private set

    fun seed(
        hasPassword: Boolean,
        emailVerified: Boolean = true,
    ): User {
        val user =
            User(
                UUID.randomUUID(),
                "user${byId.size}@example.test",
                Instant.EPOCH,
                if (emailVerified) Instant.EPOCH else null,
                hasPassword,
            )
        byId[user.id] = user
        if (hasPassword) {
            hashById[user.id] = PasswordHash("existing-hash")
        }
        return user
    }

    fun passwordHashOf(userId: UUID): PasswordHash? = hashById[userId]

    override fun findByEmail(email: String): StoredUser? = error(NOT_USED_MESSAGE)

    override fun findById(id: UUID): User? = byId[id]

    override fun lockForUpdate(id: UUID): User? {
        lockCount++
        return byId[id]
    }

    override fun exists(id: UUID): Boolean = error(NOT_USED_MESSAGE)

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error(NOT_USED_MESSAGE)

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = error(NOT_USED_MESSAGE)

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        hashById[userId] = passwordHash
        byId[userId]?.let { byId[userId] = it.copy(hasPassword = true) }
    }

    override fun markEmailVerified(userId: UUID) = error(NOT_USED_MESSAGE)

    private companion object {
        const val NOT_USED_MESSAGE = "이 유스케이스가 부르지 않는 사용자 연산이다"
    }
}

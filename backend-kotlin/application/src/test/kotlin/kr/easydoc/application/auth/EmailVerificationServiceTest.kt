package kr.easydoc.application.auth

import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 이메일 인증 유스케이스 — Spring 도 DB 도 실제 메일 발송도 없이 대역으로 돈다. */
class EmailVerificationServiceTest {
    @Test
    @DisplayName("발급은 6자리 코드로 메일을 한 통 보낸다")
    fun `발급은 코드로 메일을 보낸다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()

        world.service.requestVerification(user.id)

        val mail = world.mail.sent.single()
        assertThat(mail.textBody).contains(world.codes.lastIssuedCode())
        assertThat(world.codes.lastIssuedCode()).hasSize(6)
        assertThat(world.codes.lastIssuedCode().all(Char::isDigit)).isTrue()
    }

    @Test
    @DisplayName("정답 코드로 확인하면 계정이 인증된다")
    fun `정답 확인은 인증을 완료한다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.service.requestVerification(user.id)
        val code = world.codes.lastIssuedCode()

        assertThatCode { world.service.confirm(user.id, code) }.doesNotThrowAnyException()

        assertThat(world.users.verifiedIds).containsExactly(user.id)
    }

    @Test
    @DisplayName("오답을 5회 내면 그 코드는 무효화된다 — 이후 정답도 통하지 않는다")
    fun `오답 5회는 코드를 무효화한다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.service.requestVerification(user.id)
        val code = world.codes.lastIssuedCode()

        repeat(5) {
            assertThatThrownBy { world.service.confirm(user.id, "000000") }
                .isInstanceOf(InvalidVerificationCodeException::class.java)
        }

        assertThatThrownBy { world.service.confirm(user.id, code) }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
        assertThat(world.users.verifiedIds).isEmpty()
    }

    @Test
    @DisplayName("만료된 코드는 정답이어도 거절된다")
    fun `만료된 코드는 거절된다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.service.requestVerification(user.id)
        val code = world.codes.lastIssuedCode()
        world.codes.forceExpire(user.id)

        assertThatThrownBy { world.service.confirm(user.id, code) }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
    }

    @Test
    @DisplayName("재발급은 이전 코드를 무효화한다 — 활성 코드는 항상 최대 하나")
    fun `재발급은 이전 코드를 무효화한다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.service.requestVerification(user.id)
        val firstCode = world.codes.lastIssuedCode()

        world.service.requestVerification(user.id)

        assertThatThrownBy { world.service.confirm(user.id, firstCode) }
            .isInstanceOf(InvalidVerificationCodeException::class.java)
        assertThatCode { world.service.confirm(user.id, world.codes.lastIssuedCode()) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("쿨다운 안의 재요청은 저장소가 던진 RateLimitedException 이 그대로 올라간다")
    fun `쿨다운은 429 로 매핑될 예외를 던진다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.codes.cooldownRemaining = 42

        assertThatThrownBy { world.service.requestVerification(user.id) }
            .isInstanceOf(RateLimitedException::class.java)
            .satisfies({ exception -> assertThat((exception as RateLimitedException).retryAfterSeconds).isEqualTo(42) })
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("이미 인증된 이메일의 재요청·확인은 409 로 매핑될 예외다")
    fun `이미 인증된 이메일은 409다`() {
        val world = VerificationWorld()
        val user = world.users.seedVerified()

        assertThatThrownBy { world.service.requestVerification(user.id) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { world.service.confirm(user.id, "123456") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("가입 직후 발급은 best-effort 다 — 메일 발송이 실패해도 예외를 던지지 않는다")
    fun `가입 직후 발급은 발송 실패를 삼킨다`() {
        val world = VerificationWorld(mailFails = true)
        val user = world.users.seedUnverified()

        assertThatCode { world.service.issueAfterSignup(user.id) }.doesNotThrowAnyException()
        // 코드는 이미 저장소에 저장됐다 — 재요청(`requestVerification`)으로 회복할 수 있다는
        // 위임 지침의 전제와 같다. 발송 실패가 발급 자체를 되돌리지 않는다.
        assertThat(world.codes.lastIssuedCode()).isNotBlank()
    }

    @Test
    @DisplayName("가입 직후 발급 자체가 실패해도(저장소 예외) 예외를 던지지 않는다")
    fun `가입 직후 발급은 저장소 예외도 삼킨다`() {
        val world = VerificationWorld()
        val user = world.users.seedUnverified()
        world.codes.cooldownRemaining = 1

        assertThatCode { world.service.issueAfterSignup(user.id) }.doesNotThrowAnyException()
    }
}

/** 유스케이스 하나를 돌리는 데 필요한 최소 세계. */
private class VerificationWorld(mailFails: Boolean = false) {
    val users = VerificationUserRepository()
    val codes = RecordingVerificationCodeStore()
    val mail = RecordingMailSender(fails = mailFails)
    val service =
        EmailVerificationService(
            users = users,
            codes = codes,
            mail = mail,
            codeTtl = Duration.ofMinutes(10),
            resendCooldown = Duration.ofSeconds(60),
            maxAttempts = 5,
        )
}

private class VerificationUserRepository : UserRepository {
    private val byId: MutableMap<UUID, User> = mutableMapOf()
    val verifiedIds: MutableList<UUID> = mutableListOf()

    fun seedUnverified(): User = seed(emailVerifiedAt = null)

    fun seedVerified(): User = seed(emailVerifiedAt = Instant.EPOCH)

    private fun seed(emailVerifiedAt: Instant?): User {
        val user = User(UUID.randomUUID(), "user${byId.size}@example.test", Instant.EPOCH, emailVerifiedAt)
        byId[user.id] = user
        return user
    }

    override fun findByEmail(email: String): StoredUser? = error(NOT_USED_MESSAGE)

    override fun findById(id: UUID): User? = byId[id]

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
    ) = error(NOT_USED_MESSAGE)

    override fun markEmailVerified(userId: UUID) {
        verifiedIds += userId
        byId[userId]?.let { byId[userId] = it.copy(emailVerifiedAt = Instant.EPOCH) }
    }

    private companion object {
        const val NOT_USED_MESSAGE = "이메일 인증 유스케이스가 부르지 않는 사용자 연산이다"
    }
}

/**
 * [VerificationCodeStore] 대역 — 활성 코드 하나·쿨다운·시도 상한을 인메모리로 지킨다.
 * 실물([kr.easydoc.infrastructure.auth.JdbcVerificationCodeStore])과 계약은 같고 구현은
 * 다르다(해시 대신 평문 비교) — 이 파일이 재는 것은 `EmailVerificationService` 의 호출
 * 계약이지 저장소의 SQL 이 아니다.
 */
private class RecordingVerificationCodeStore : VerificationCodeStore {
    private data class ActiveCode(
        val code: String,
        var attempts: Int = 0,
        var voided: Boolean = false,
        var expired: Boolean = false,
    )

    private val active = mutableMapOf<UUID, ActiveCode>()
    private var counter = 0

    /** 다음 [issue] 를 이 초만큼 남은 쿨다운으로 거절하게 만든다. `null` 이면 거절하지 않는다. */
    var cooldownRemaining: Long? = null

    fun lastIssuedCode(): String = checkNotNull(active.values.lastOrNull()?.code) { "발급된 코드가 없다" }

    fun forceExpire(userId: UUID) {
        active[userId]?.expired = true
    }

    override fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String {
        cooldownRemaining?.let { throw RateLimitedException("잠시 후 다시 시도해주세요", it) }
        val code = (++counter).toString().padStart(6, '0')
        active[userId] = ActiveCode(code)
        return code
    }

    override fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean {
        val current = active[userId]
        val matched = current != null && !current.voided && !current.expired && current.code == code
        when {
            current == null || current.voided || current.expired -> {
                Unit
            }

            matched -> {
                active.remove(userId)
            }

            else -> {
                current.attempts++
                if (current.attempts >= maxAttempts) current.voided = true
            }
        }
        return matched
    }
}

private class RecordingMailSender(private val fails: Boolean) : MailSender {
    val sent: MutableList<OutboundMail> = mutableListOf()

    override fun send(message: OutboundMail): MailDelivery {
        if (fails) error("네트워크 실패")
        sent += message
        return MailDelivery.Sent()
    }
}

package kr.easydoc.application.auth

import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
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

/**
 * 이메일 코드로 비밀번호를 재설정하는 유스케이스 — `POST /auth/password-reset/request`·
 * `POST /auth/password-reset/confirm`(backlog §1.4 후속). Spring 도 DB 도 실제 메일 발송도
 * 없이 대역으로 돈다.
 */
class PasswordResetServiceTest {
    @Test
    @DisplayName("존재하는 이메일의 요청은 코드로 메일을 한 통 보낸다")
    fun `요청은 코드로 메일을 보낸다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)

        assertThatCode { world.service.request("known@example.test") }.doesNotThrowAnyException()

        val mail = world.mail.sent.single()
        assertThat(mail.textBody).contains(world.codes.lastIssuedCode(user.id))
        assertThat(world.codes.lastIssuedCode(user.id)).hasSize(6)
    }

    @Test
    @DisplayName("존재하지 않는 이메일의 요청도 예외 없이 끝난다 — 계정 존재를 흘리지 않는다")
    fun `모르는 이메일도 조용히 끝난다`() {
        val world = ResetWorld()

        assertThatCode { world.service.request("unknown@example.test") }.doesNotThrowAnyException()

        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("쿨다운 안의 재요청도 예외 없이 끝난다 — 429를 노출하지 않는다")
    fun `쿨다운도 조용히 끝난다`() {
        val world = ResetWorld()
        world.users.seed("known@example.test", hasPassword = true)
        world.codes.cooldownRemaining = 42

        assertThatCode { world.service.request("known@example.test") }.doesNotThrowAnyException()
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("정답 코드로 확인하면 비밀번호가 설정되고 이메일이 인증되고 토큰이 발급된다")
    fun `정답 확인은 비밀번호를 설정하고 토큰을 발급한다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = false)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)

        val issued = world.service.confirm("known@example.test", code, "new-password-1")

        assertThat(issued.token).isNotBlank()
        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("hashed:new-password-1")
        assertThat(world.users.verifiedIds).containsExactly(user.id)
    }

    @Test
    @DisplayName("정답 확인 성공은 변경 알림 메일도 보낸다 — 요청의 코드 메일에 이어 두 번째 메일이다")
    fun `정답 확인은 변경 알림 메일을 보낸다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)

        world.service.confirm("known@example.test", code, "new-password-1")

        assertThat(world.mail.sent).hasSize(2)
        assertThat(world.mail.sent[0].subject).isEqualTo(PasswordResetService.REQUEST_SUBJECT)
        assertThat(world.mail.sent[1].subject).isEqualTo(PasswordResetService.CHANGED_SUBJECT)
    }

    @Test
    @DisplayName("소셜 전용(비밀번호 없는) 계정도 재설정으로 비밀번호가 생긴다")
    fun `비밀번호 없는 계정도 재설정으로 비밀번호가 생긴다`() {
        val world = ResetWorld()
        val user = world.users.seed("social@example.test", hasPassword = false)
        world.service.request("social@example.test")
        val code = world.codes.lastIssuedCode(user.id)

        world.service.confirm("social@example.test", code, "new-password-1")

        assertThat(world.users.passwordHashOf(user.id)).isNotNull()
    }

    @Test
    @DisplayName("모르는 이메일의 확인은 401로 매핑될 예외이고, 코드가 맞고 틀리고를 가리지 않는다")
    fun `모르는 이메일의 확인은 401이다`() {
        val world = ResetWorld()

        assertThatThrownBy { world.service.confirm("unknown@example.test", "123456", "new-password-1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
            .hasMessage(PasswordResetService.INVALID_RESET_CODE_MESSAGE)
    }

    @Test
    @DisplayName("오답 코드의 확인은 401로 매핑될 예외다 — 시도 횟수가 오르고 알림 메일은 보내지 않는다")
    fun `오답은 401이다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")

        assertThatThrownBy { world.service.confirm("known@example.test", "000000", "new-password-1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
            .hasMessage(PasswordResetService.INVALID_RESET_CODE_MESSAGE)
        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("existing-hash")
        // 요청이 보낸 코드 메일 하나뿐이다 — 실패한 확인은 변경 알림을 보내지 않는다.
        assertThat(world.mail.sent).hasSize(1)
    }

    @Test
    @DisplayName("오답을 5회 내면 그 코드는 무효화된다 — 이후 정답도 통하지 않는다")
    fun `오답 5회는 코드를 무효화한다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)

        repeat(5) {
            assertThatThrownBy { world.service.confirm("known@example.test", "000000", "new-password-1") }
                .isInstanceOf(InvalidCredentialsException::class.java)
        }

        assertThatThrownBy { world.service.confirm("known@example.test", code, "new-password-1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("만료된 코드의 확인은 401이다")
    fun `만료된 코드는 401이다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)
        world.codes.forceExpire(user.id)

        assertThatThrownBy { world.service.confirm("known@example.test", code, "new-password-1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("새 비밀번호가 8자 미만이면 422로 매핑될 예외다 — 코드를 소비하지 않는다")
    fun `짧은 새 비밀번호는 거절된다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)

        assertThatThrownBy { world.service.confirm("known@example.test", code, "short") }
            .isInstanceOf(InvalidInputException::class.java)

        // 코드가 아직 살아 있어야 한다 — 검증에 실패했으니 소비되지 않았다.
        assertThatCode { world.service.confirm("known@example.test", code, "new-password-1") }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("비밀번호 갱신이 실패하면 트랜잭션이 롤백돼 코드가 소비되지 않고 살아 있다")
    fun `갱신 실패는 코드를 살려 둔다`() {
        val world = ResetWorld()
        val user = world.users.seed("known@example.test", hasPassword = true)
        world.service.request("known@example.test")
        val code = world.codes.lastIssuedCode(user.id)
        world.users.failNextUpdatePasswordHash = true

        // 저장소 실패가 그대로 올라온다 — 삼키지 않는다.
        assertThatThrownBy { world.service.confirm("known@example.test", code, "new-password-1") }
            .isInstanceOf(RuntimeException::class.java)
        // 실패한 트랜잭션 안에서 소비된 코드도 함께 롤백됐어야 한다.
        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("existing-hash")
        assertThat(world.users.verifiedIds).isEmpty()

        // 같은 코드로 재시도하면 이번에는 통과한다 — 코드가 소비되지 않고 남아 있었다는 증거다.
        assertThatCode { world.service.confirm("known@example.test", code, "new-password-1") }
            .doesNotThrowAnyException()
        assertThat(world.users.passwordHashOf(user.id)?.reveal()).isEqualTo("hashed:new-password-1")
    }
}

private class ResetWorld {
    val users = ResetUserRepository()
    val codes = RecordingPasswordResetCodeStore()
    val mail = RecordingResetMailSender()
    val accessTokens = StubResetAccessTokens()

    /**
     * 실물(`SpringTransactionRunner`)이 실패 시 하는 일 — 블록 안에서 일어난 저장소
     * 변이를 되돌린다 — 을 인메모리 대역 둘(`codes`·`users`)에 대해 흉내 낸다.
     * `PasswordResetService.confirm`이 "정답인데 이후 갱신이 실패하면 코드 소비까지
     * 롤백된다"고 요구하는 계약을 이 대역들로도 재려면 필요하다 — 평범한 pass-through
     * 대역(`block()`만 부르는 것)은 실패 시 되돌릴 방법이 없다.
     */
    val transaction = RollbackSimulatingTransactionRunner(codes, users)
    val service =
        PasswordResetService(
            users = users,
            codes = codes,
            mail = mail,
            passwords = StubResetHasher(),
            accessTokens = accessTokens,
            transaction = transaction,
            codeTtl = Duration.ofMinutes(10),
            resendCooldown = Duration.ofSeconds(60),
            maxAttempts = 5,
        )
}

/** [ResetWorld] KDoc 참고 — 블록이 예외를 던지면 두 대역의 상태를 호출 전으로 되돌린다. */
private class RollbackSimulatingTransactionRunner(
    private val codes: RecordingPasswordResetCodeStore,
    private val users: ResetUserRepository,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T {
        val codesSnapshot = codes.snapshot()
        val usersSnapshot = users.snapshot()
        return try {
            block()
        } catch (failure: Throwable) {
            codes.restore(codesSnapshot)
            users.restore(usersSnapshot)
            throw failure
        }
    }
}

private class StubResetHasher : PasswordHasher {
    override fun hash(rawPassword: String): PasswordHash = PasswordHash("hashed:$rawPassword")

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = error("이 유스케이스는 검증을 부르지 않는다")

    override fun needsRehash(stored: PasswordHash): Boolean = error("이 유스케이스는 재해시 판정을 부르지 않는다")

    override fun dummyHash(): PasswordHash = error("이 유스케이스는 더미 해시를 부르지 않는다")
}

private class StubResetAccessTokens : AccessTokens {
    override fun ensureConfigured() = Unit

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("token:$userId", 3600)

    override fun verify(token: String): UUID = error("이 유스케이스는 검증을 부르지 않는다")
}

private class ResetUserRepository : UserRepository {
    private val byId: MutableMap<UUID, User> = mutableMapOf()
    private val byEmail: MutableMap<String, UUID> = mutableMapOf()
    private val hashById: MutableMap<UUID, PasswordHash> = mutableMapOf()
    val verifiedIds: MutableList<UUID> = mutableListOf()

    /** 다음 [updatePasswordHash] 호출 한 번만 실패하게 만든다 — 갱신 실패 시나리오 전용. */
    var failNextUpdatePasswordHash = false

    fun seed(
        email: String,
        hasPassword: Boolean,
    ): User {
        val user = User(UUID.randomUUID(), email, Instant.EPOCH, null, hasPassword)
        byId[user.id] = user
        byEmail[email] = user.id
        if (hasPassword) {
            hashById[user.id] = PasswordHash("existing-hash")
        }
        return user
    }

    fun passwordHashOf(userId: UUID): PasswordHash? = hashById[userId]

    /** [RollbackSimulatingTransactionRunner] 전용 — 되돌릴 수 있는 상태만 담는다(`byEmail`은 불변). */
    fun snapshot(): Any = Pair(byId.toMap(), hashById.toMap()) to verifiedIds.toList()

    @Suppress("UNCHECKED_CAST")
    fun restore(snapshot: Any) {
        val (idsAndHashes, verified) = snapshot as Pair<Pair<Map<UUID, User>, Map<UUID, PasswordHash>>, List<UUID>>
        val (ids, hashes) = idsAndHashes
        byId.clear()
        byId.putAll(ids)
        hashById.clear()
        hashById.putAll(hashes)
        verifiedIds.clear()
        verifiedIds.addAll(verified)
    }

    override fun findByEmail(email: String): StoredUser? {
        val user = byEmail[email]?.let { byId[it] } ?: return null
        return StoredUser(user, hashById[user.id])
    }

    override fun findById(id: UUID): User? = byId[id]

    override fun lockForUpdate(id: UUID): User? = error(NOT_USED_MESSAGE)

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
        if (failNextUpdatePasswordHash) {
            failNextUpdatePasswordHash = false
            error("시뮬레이션된 저장소 실패")
        }
        hashById[userId] = passwordHash
        byId[userId]?.let { byId[userId] = it.copy(hasPassword = true) }
    }

    override fun markEmailVerified(userId: UUID) {
        verifiedIds += userId
        byId[userId]?.let { byId[userId] = it.copy(emailVerifiedAt = Instant.EPOCH) }
    }

    private companion object {
        const val NOT_USED_MESSAGE = "이 유스케이스가 부르지 않는 사용자 연산이다"
    }
}

/**
 * [PasswordResetCodeStore] 대역 — `EmailVerificationServiceTest`의
 * `RecordingVerificationCodeStore`와 같은 계약(활성 코드 하나·쿨다운·시도 상한)을
 * 인메모리로 지킨다.
 */
private class RecordingPasswordResetCodeStore : PasswordResetCodeStore {
    private data class ActiveCode(
        val code: String,
        var attempts: Int = 0,
        var voided: Boolean = false,
        var expired: Boolean = false,
    )

    private val active = mutableMapOf<UUID, ActiveCode>()
    private var counter = 0

    var cooldownRemaining: Long? = null

    fun lastIssuedCode(userId: UUID): String = checkNotNull(active[userId]?.code) { "발급된 코드가 없다" }

    fun forceExpire(userId: UUID) {
        active[userId]?.expired = true
    }

    /** [RollbackSimulatingTransactionRunner] 전용 — 활성 코드 맵의 깊은 복사본. */
    fun snapshot(): Any = active.mapValues { it.value.copy() }

    @Suppress("UNCHECKED_CAST")
    fun restore(snapshot: Any) {
        active.clear()
        active.putAll(snapshot as Map<UUID, ActiveCode>)
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

private class RecordingResetMailSender : MailSender {
    val sent: MutableList<OutboundMail> = mutableListOf()

    override fun send(message: OutboundMail): MailDelivery {
        sent += message
        return MailDelivery.Sent()
    }
}

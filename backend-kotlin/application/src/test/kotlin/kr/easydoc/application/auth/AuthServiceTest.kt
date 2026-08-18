package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 인증 유스케이스의 **분기**를 잰다 — Spring 도 DB 도 없이.
 *
 * HTTP 표현은 `api` 의 계약 테스트가, 해시·토큰의 정확성은 `infrastructure` 의 단위
 * 테스트가 맡는다. 여기서 재는 것은 그 사이 — **언제 재해시하는가, 실패하면 어떻게
 * 되는가, 무엇이 먼저 끊기는가** 다.
 */
class AuthServiceTest {
    // ---------------------------------------------------------------- 가입

    @Test
    @DisplayName("가입은 계정과 기본 작업 공간을 같은 트랜잭션에서 만든다")
    fun `가입이 기본 작업 공간까지 한 트랜잭션이다`() {
        val world = World()

        val user = world.service.signup(" User@Example.Test ", VALID_PASSWORD)

        assertThat(user.email).isEqualTo("user@example.test")
        assertThat(world.workspaces.createdFor).containsExactly(user.id)
        assertThat(world.workspaces.depthAtCreation).isEqualTo(1)
    }

    @Test
    @DisplayName("해시 계산은 트랜잭션 **밖**에서 한다 — 커넥션을 붙잡고 기다리지 않는다")
    fun `해시는 트랜잭션 밖에서 계산한다`() {
        val world = World()

        world.service.signup(uniqueEmail(), VALID_PASSWORD)

        assertThat(world.hasher.depthAtHash).isEqualTo(0)
    }

    @Test
    @DisplayName("인증이 배선되지 않으면 해시 계산 전에 끊긴다")
    fun `설정 미비는 해시 전에 끊는다`() {
        val world = World(tokensConfigured = false)

        assertThatThrownBy { world.service.signup(uniqueEmail(), VALID_PASSWORD) }
            .isInstanceOf(ConfigurationException::class.java)
        // 로그인할 수 없는 계정을 만들지 않고, 값비싼 계산도 하지 않는다.
        assertThat(world.hasher.hashCount).isZero()
        assertThat(world.users.saved).isEmpty()
    }

    @Test
    @DisplayName("이메일은 정규화 후 형식·상한을, 비밀번호는 원시 길이를 잰다")
    fun `입력 규칙이 계약의 측정 축을 따른다`() {
        val world = World()

        assertThatThrownBy { world.service.signup("not-an-email", VALID_PASSWORD) }
            .isInstanceOf(InvalidInputException::class.java)
        // 앞뒤 공백을 털면 통과하는 이메일은 통과한다(정규화 후 측정).
        assertThat(world.service.signup("  spaced@example.test  ", VALID_PASSWORD).email)
            .isEqualTo("spaced@example.test")
        // 비밀번호는 공백도 값이다(원시 측정).
        assertThat(world.service.signup(uniqueEmail(), "        ")).isNotNull()
    }

    // ---------------------------------------------------------------- 로그인

    @Test
    @DisplayName("로그인 성공 시에만 재해시한다")
    fun `성공한 로그인만 재해시한다`() {
        val world = World(needsRehash = true)
        val email = uniqueEmail()
        world.service.signup(email, VALID_PASSWORD)

        // 실패한 로그인에서 재해시하면 오프라인 공격자에게 계산 자원을 태워 준다.
        assertThatThrownBy { world.service.login(email, "${VALID_PASSWORD}x") }
            .isInstanceOf(InvalidCredentialsException::class.java)
        assertThat(world.users.rehashed).isEmpty()

        world.service.login(email, VALID_PASSWORD)
        assertThat(world.users.rehashed).hasSize(1)
    }

    @Test
    @DisplayName("파라미터가 현행과 같으면 재해시하지 않는다")
    fun `현행 해시는 그대로 둔다`() {
        val world = World(needsRehash = false)
        val email = uniqueEmail()
        world.service.signup(email, VALID_PASSWORD)

        world.service.login(email, VALID_PASSWORD)

        assertThat(world.users.rehashed).isEmpty()
    }

    @Test
    @DisplayName("재해시가 실패해도 로그인은 성공한다 — best-effort")
    fun `재해시 실패가 로그인을 막지 않는다`() {
        // 여기서 예외가 새면 파라미터를 올린 날 전 사용자가 로그인하지 못한다.
        val world = World(needsRehash = true, rehashFails = true)
        val email = uniqueEmail()
        world.service.signup(email, VALID_PASSWORD)

        assertThat(world.service.login(email, VALID_PASSWORD).token).isNotBlank()
    }

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호가 같은 예외·같은 문구다")
    fun `자격증명 실패를 구분하지 않는다`() {
        val world = World()
        val email = uniqueEmail()
        world.service.signup(email, VALID_PASSWORD)

        val unknown = runCatching { world.service.login(uniqueEmail(), VALID_PASSWORD) }.exceptionOrNull()
        val wrong = runCatching { world.service.login(email, "${VALID_PASSWORD}x") }.exceptionOrNull()

        assertThat(unknown).isInstanceOf(InvalidCredentialsException::class.java)
        assertThat(unknown?.message).isEqualTo(wrong?.message)
    }

    @Test
    @DisplayName("로그인에는 가입 입력 규칙을 다시 적용하지 않는다")
    fun `로그인은 길이 규칙으로 거절하지 않는다`() {
        val world = World()

        // 규칙을 조인 뒤에 가입한 계정이 로그인하지 못하면 안 되고, 422 와 401 이 갈리면
        // "이 이메일은 규칙을 통과하는 형태다"가 새어 나간다.
        assertThatThrownBy { world.service.login("x", "1") }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    // ---------------------------------------------------------------- 내 정보

    @Test
    @DisplayName("토큰은 유효한데 계정이 지워졌으면 같은 401 이다")
    fun `삭제된 계정도 같은 실패다`() {
        val world = World()
        val user = world.service.signup(uniqueEmail(), VALID_PASSWORD)
        world.users.saved.remove(user.email)

        assertThatThrownBy { world.service.readUser(user.id) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    private fun uniqueEmail(): String = "svc${counter++}@example.test"

    private companion object {
        const val VALID_PASSWORD = "correct horse battery"
        var counter = 0
    }
}

/** 유스케이스 하나를 돌리는 데 필요한 최소 세계. 각 테스트가 자기 것을 만든다. */
private class World(
    tokensConfigured: Boolean = true,
    needsRehash: Boolean = false,
    rehashFails: Boolean = false,
) {
    val transaction = RecordingTransactionRunner()
    val hasher = RecordingHasher(transaction, needsRehash)
    val users = RecordingUserRepository(rehashFails)
    val workspaces = RecordingWorkspaceRepository(transaction)
    val tokens = RecordingAccessTokens(tokensConfigured)
    val service = AuthService(users, workspaces, hasher, tokens, transaction)
}

/** 트랜잭션 **깊이**를 기록한다 — 무엇이 경계 안에서 도는지가 이 테스트의 관심사다. */
private class RecordingTransactionRunner : TransactionRunner {
    var depth = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }
}

private class RecordingHasher(
    private val transaction: RecordingTransactionRunner,
    private val needsRehash: Boolean,
) : PasswordHasher {
    var hashCount = 0
        private set
    var depthAtHash = -1
        private set

    override fun hash(rawPassword: String): PasswordHash {
        hashCount++
        depthAtHash = transaction.depth
        return PasswordHash("hashed:$rawPassword")
    }

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = stored.reveal() == "hashed:$rawPassword"

    override fun needsRehash(stored: PasswordHash): Boolean = needsRehash
}

private class RecordingUserRepository(private val rehashFails: Boolean) : UserRepository {
    val saved: MutableMap<String, StoredUser> = mutableMapOf()
    val rehashed: MutableList<UUID> = mutableListOf()

    override fun findByEmail(email: String): StoredUser? = saved[email]

    override fun findById(id: UUID): User? = saved.values.firstOrNull { it.user.id == id }?.user

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User {
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH), passwordHash)
        saved[email] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        if (rehashFails) {
            error("재해시 저장 실패")
        }
        rehashed += userId
    }
}

private class RecordingWorkspaceRepository(private val transaction: RecordingTransactionRunner) : WorkspaceRepository {
    val createdFor: MutableList<UUID> = mutableListOf()

    /** 작업 공간이 만들어진 시점의 트랜잭션 깊이. 0 이면 계정과 따로 커밋된다는 뜻이다. */
    var depthAtCreation = -1
        private set

    override fun createDefault(userId: UUID): UUID {
        createdFor += userId
        depthAtCreation = transaction.depth
        return UUID.randomUUID()
    }
}

private class RecordingAccessTokens(private val configured: Boolean) : AccessTokens {
    override fun ensureConfigured() {
        if (!configured) {
            throw ConfigurationException("인증이 설정되지 않았습니다")
        }
    }

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("token:$userId", 1)

    override fun verify(token: String): UUID = UUID.fromString(token.removePrefix("token:"))
}

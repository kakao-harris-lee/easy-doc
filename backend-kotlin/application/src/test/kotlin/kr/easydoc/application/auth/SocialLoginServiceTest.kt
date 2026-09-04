package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 소셜 로그인 유스케이스의 분기를 잰다 — Spring 도 DB 도 실제 Google 도 없이. */
class SocialLoginServiceTest {
    @Test
    @DisplayName("새 신원은 계정과 기본 작업 공간을 같은 트랜잭션에서 만든다")
    fun `새 신원이 계정을 만든다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-1", "New@Example.Test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(token.token).isNotBlank()
        assertThat(world.workspaces.createdFor).hasSize(1)
        assertThat(
            world.identities.linked
                .single()
                .providerUserId,
        ).isEqualTo("google-sub-1")
        assertThat(world.users.saved.keys).containsExactly("new@example.test")
        // 제공자가 이미 검증한 이메일이다 — 우리 쪽 이메일 인증 코드가 또 필요하지 않다
        // (backlog §1.4 P0-3, `UserRepository.createWithoutPassword` KDoc).
        assertThat(
            world.users.saved
                .getValue("new@example.test")
                .user.emailVerifiedAt,
        ).withFailMessage("구글 최초 가입 계정이 생성 시점에 인증 완료로 표시되지 않았다")
            .isNotNull()
    }

    @Test
    @DisplayName("이미 연결된 신원은 새 계정을 만들지 않고 로그인한다")
    fun `기존 신원은 로그인이다`() {
        val world = SocialWorld()
        val existingUser = User(UUID.randomUUID(), "linked@example.test", Instant.EPOCH)
        world.identities.seed(existingUser.id, SocialLoginProviderId.GOOGLE, "google-sub-2")
        world.provider.nextIdentity = SocialIdentity("google-sub-2", "linked@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(token.token).isEqualTo("token:${existingUser.id}")
        assertThat(world.workspaces.createdFor).isEmpty()
        assertThat(world.identities.linked).isEmpty()
    }

    @Test
    @DisplayName("같은 검증된 이메일의 계정이 이미 있으면 409 — 자동 연결하지 않는다")
    fun `이메일이 겹치면 409다`() {
        val world = SocialWorld()
        world.users.saved["taken@example.test"] =
            StoredUser(User(UUID.randomUUID(), "taken@example.test", Instant.EPOCH), PasswordHash("hashed:x"))
        world.provider.nextIdentity = SocialIdentity("google-sub-3", "taken@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(EmailAlreadyRegisteredException::class.java)
            .hasMessage(SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE)
        assertThat(world.identities.linked).isEmpty()
    }

    @Test
    @DisplayName("이메일이 없으면 422")
    fun `이메일 없으면 422다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-4", email = null, emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.EMAIL_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("이메일이 검증되지 않았으면 422 — 값이 있어도 마찬가지다")
    fun `이메일 미검증도 422다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-5", "unverified@example.test", emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.EMAIL_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("state 가 없거나 만료·재사용이면 400 — 사유를 구분하지 않는다")
    fun `무효한 state 는 400이다`() {
        val world = SocialWorld()

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                "never-issued",
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
            .hasMessage(SocialLoginService.INVALID_STATE_MESSAGE)
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다")
    fun `state 는 단발이다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-6", "once@example.test", emailVerified = true)
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
    }

    @Test
    @DisplayName("redirect_uri 가 발급 시점과 다르면 400 — state 소비가 그 자리에서 막힌다")
    fun `redirect_uri 불일치는 400이다`() {
        val world = SocialWorld()
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                "https://other.example.test/callback",
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
    }

    @Test
    @DisplayName("제공자가 코드를 거절하면 401 — 로그인 실패와 같은 문구다")
    fun `코드 거절은 401이다`() {
        val world = SocialWorld()
        world.provider.exchangeFailure = InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("제공자에 닿지 못하면 502다")
    fun `제공자 불통은 502다`() {
        val world = SocialWorld()
        world.provider.exchangeFailure = ExternalServiceUnavailableException("요청을 처리하지 못했습니다")
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    // 「지원하지 않는 provider」 케이스는 이 서비스 밖이다 — `SocialLoginProviderId` 가
    // `Converter` 로만 만들어지므로(이 파일이 그 타입을 직접 쓴다), 이 테스트가 부를 수
    // 있는 provider 인자는 애초에 컴파일 시점에 google 하나뿐이다. 그 경계는
    // `kr.easydoc.api.auth.SocialLoginProviderIdConverter` 와 `ValueSlotInvariantReachTest`
    // (스키마 층 422 배열)가 잰다.

    @Test
    @DisplayName("키가 설정되지 않은 제공자는 422 — 구글 전용 문구다")
    fun `설정되지 않은 제공자는 422다`() {
        val world = SocialWorld(googleConfigured = false)

        assertThatThrownBy { world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("구글 로그인이 설정되지 않았습니다")
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 422다")
    fun `허용 목록 밖 redirect_uri 는 422다`() {
        val world = SocialWorld()

        assertThatThrownBy { world.service.start(SocialLoginProviderId.GOOGLE, "https://evil.example.test/callback") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.REDIRECT_URI_NOT_ALLOWED_MESSAGE)
    }

    @Test
    @DisplayName("인증이 배선되지 않으면 콜백이 제공자를 부르기 전에 끊긴다")
    fun `설정 미비는 제공자 호출 전에 끊는다`() {
        val world = SocialWorld(tokensConfigured = false)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                "any-state",
                REDIRECT_URI,
            )
        }.isInstanceOf(ConfigurationException::class.java)

        assertThat(world.provider.exchangeCallCount).isZero()
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
    }
}

/** 유스케이스 하나를 돌리는 데 필요한 최소 세계. */
private class SocialWorld(
    tokensConfigured: Boolean = true,
    googleConfigured: Boolean = true,
) {
    val users = RecordingSocialUserRepository()
    val workspaces = RecordingSocialWorkspaceRepository()
    val identities = RecordingIdentityRepository()
    val states: OAuthStateStore = InMemoryOAuthStateStore()
    val tokens = RecordingSocialAccessTokens(tokensConfigured)
    val provider = FakeSocialLoginProvider()
    val transaction =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    val service =
        SocialLoginService(
            providers = if (googleConfigured) mapOf(SocialLoginProviderId.GOOGLE to provider) else emptyMap(),
            states = states,
            repositories = SocialLoginRepositories(users, identities, workspaces),
            accessTokens = tokens,
            transaction = transaction,
            stateTtl = Duration.ofMinutes(10),
        )
}

private class FakeSocialLoginProvider : SocialLoginProvider {
    var nextIdentity: SocialIdentity? = null
    var exchangeFailure: RuntimeException? = null
    var exchangeCallCount = 0
        private set

    override fun supportsRedirectUri(redirectUri: String): Boolean =
        redirectUri == "http://localhost:5173/auth/google/callback"

    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String = "https://accounts.google.test/o/oauth2/auth?state=$state&nonce=$nonce"

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity {
        exchangeCallCount++
        exchangeFailure?.let { throw it }
        return nextIdentity ?: error("테스트가 nextIdentity 를 설정하지 않았다")
    }
}

/** state·nonce 를 실제로 단발 소비하는 인메모리 대역 — 실물 `JdbcOAuthStateStore` 와 같은 계약. */
private class InMemoryOAuthStateStore : OAuthStateStore {
    private data class Entry(
        val provider: SocialLoginProviderId,
        val redirectUri: String,
        val nonce: String,
    )

    private val entries = mutableMapOf<String, Entry>()
    private var counter = 0

    override fun issue(
        provider: SocialLoginProviderId,
        redirectUri: String,
        ttl: Duration,
    ): OAuthChallenge {
        val state = "state-${++counter}"
        val nonce = "nonce-$counter"
        entries[state] = Entry(provider, redirectUri, nonce)
        return OAuthChallenge(state, nonce)
    }

    override fun consume(
        provider: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): String? =
        // 단발 — 일치하든 안 하든 재사용은 막는다(실물의 단일 UPDATE ... WHERE ... RETURNING 과 같은 성질).
        entries
            .remove(state)
            ?.takeIf { it.provider == provider && it.redirectUri == redirectUri }
            ?.nonce
}

private class RecordingSocialUserRepository : UserRepository {
    val saved: MutableMap<String, StoredUser> = mutableMapOf()

    override fun findByEmail(email: String): StoredUser? = saved[email]

    override fun findById(id: UUID): User? = saved.values.firstOrNull { it.user.id == id }?.user

    override fun exists(id: UUID): Boolean = saved.values.any { it.user.id == id }

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("소셜 로그인 유스케이스는 비밀번호가 있는 create 를 부르지 않는다")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User {
        val verifiedAt = if (emailVerified) Instant.EPOCH else null
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt), passwordHash = null)
        saved[email] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("소셜 로그인 유스케이스는 비밀번호를 재해시하지 않는다")

    override fun markEmailVerified(userId: UUID) = error("소셜 로그인 유스케이스는 이 메서드를 부르지 않는다 — 생성 시점에 이미 채운다")
}

private class RecordingSocialWorkspaceRepository : WorkspaceRepository {
    val createdFor: MutableList<UUID> = mutableListOf()

    override fun createDefault(userId: UUID): UUID {
        createdFor += userId
        return UUID.randomUUID()
    }

    override fun listOwned(ownerId: UUID): List<WorkspaceListing> = error(SOCIAL_NOT_SCOPE)

    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace = error(SOCIAL_NOT_SCOPE)

    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace = error(SOCIAL_NOT_SCOPE)

    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState = error(SOCIAL_NOT_SCOPE)

    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean = error(SOCIAL_NOT_SCOPE)

    private companion object {
        const val SOCIAL_NOT_SCOPE = "소셜 로그인 유스케이스가 부르지 않는 작업 공간 연산이다"
    }
}

private class RecordingIdentityRepository : UserIdentityRepository {
    val linked: MutableList<UserIdentity> = mutableListOf()
    private val byProvider = mutableMapOf<Pair<SocialLoginProviderId, String>, UserIdentity>()

    /** 「이미 연결된 신원」 시나리오를 준비한다. */
    fun seed(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
    ) {
        byProvider[provider to providerUserId] = UserIdentity(UUID.randomUUID(), userId, provider, providerUserId)
    }

    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? = byProvider[provider to providerUserId]

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity {
        val identity = UserIdentity(UUID.randomUUID(), userId, provider, providerUserId)
        byProvider[provider to providerUserId] = identity
        linked += identity
        return identity
    }
}

private class RecordingSocialAccessTokens(private val configured: Boolean) : AccessTokens {
    override fun ensureConfigured() {
        if (!configured) {
            throw ConfigurationException("인증이 설정되지 않았습니다")
        }
    }

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("token:$userId", 1)

    override fun verify(token: String): UUID = UUID.fromString(token.removePrefix("token:"))
}

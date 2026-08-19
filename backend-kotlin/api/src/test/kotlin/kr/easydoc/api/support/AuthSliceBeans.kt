package kr.easydoc.api.support

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.auth.WorkspaceDeletionState
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.workspace.DUPLICATE_WORKSPACE_NAME_MESSAGE
import kr.easydoc.application.workspace.WorkspaceService
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `@WebMvcTest` 슬라이스가 쓰는 인증 배선.
 *
 * ## 왜 슬라이스 테스트 전부가 이것을 들여와야 하는가
 *
 * `WebMvcConfig` 가 인증 인터셉터를 등록하면서 `AuthService` 를 요구하게 됐다. 그리고
 * `@WebMvcTest` 는 `WebMvcConfigurer`·`HandlerInterceptor`·`HandlerMethodArgumentResolver`
 * 를 슬라이스에 **자동 포함**하지만 `@Component` 인 서비스는 포함하지 않는다. 그래서
 * `/auth` 를 건드리지 않는 테스트도 이 빈이 없으면 컨텍스트 조립에서 멈춘다.
 *
 * 빈을 조용히 없어도 되게(예: `ObjectProvider.getIfAvailable()`) 만들지 않은 것이 의도다 —
 * 그렇게 하면 배선이 빠진 채 **인증 없이 도는 컨텍스트**가 통과한다.
 *
 * ## 저장소는 가짜지만 규칙은 진짜다
 *
 * [AuthService] 와 `CredentialRules` 는 실물이다. 가짜인 것은 **DB·해시·토큰**뿐이고,
 * 그중 유일성 판정은 `Map` 키 일치로 둔다 — 유일 인덱스와 같은 축이다. 서비스가 이메일을
 * 정규화하지 않으면 키가 갈려 중복 판정이 깨지므로, 정규화 선행(S-2b)을 여기서도 잰다.
 *
 * **암호와 토큰은 여기서 재지 않는다.** Argon2 파라미터·JWT 만료·서명은
 * `infrastructure` 의 단위 테스트와 `AuthEndpointReachTest`(실물 스택)가 잰다.
 *
 * ## `@Configuration` 이 아니라 `@TestConfiguration` 이다
 *
 * 실행 진입점이 `kr.easydoc` 전체를 스캔하므로(`ApiApplication`), 평범한
 * `@Configuration` 이면 **`@SpringBootTest` 컨텍스트가 이 파일을 함께 주워** 실물
 * `AuthConfiguration.authService` 와 빈 이름이 부딪힌다(실측: `BeanDefinitionOverride`).
 * `@TestConfiguration` 은 스캔에서 제외되고 `@Import` 로 부를 때만 들어온다.
 */
@TestConfiguration(proxyBeanMethods = false)
class AuthSliceBeans {
    @Bean
    fun inMemoryUsers(): InMemoryUserRepository = InMemoryUserRepository()

    @Bean
    fun inMemoryWorkspaces(): InMemoryWorkspaceRepository = InMemoryWorkspaceRepository()

    @Bean
    fun stubPasswordHasher(): StubPasswordHasher = StubPasswordHasher()

    @Bean
    fun stubAccessTokens(): StubAccessTokens = StubAccessTokens()

    @Bean
    fun directTransactionRunner(): TransactionRunner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    @Bean
    fun authService(
        users: InMemoryUserRepository,
        workspaces: InMemoryWorkspaceRepository,
        hasher: StubPasswordHasher,
        tokens: StubAccessTokens,
        transaction: TransactionRunner,
    ): AuthService = AuthService(users, workspaces, hasher, tokens, transaction)

    /**
     * `@WebMvcTest` 는 컨트롤러를 **전부** 슬라이스에 넣는다. `WorkspaceController` 가
     * 생긴 순간부터 이 빈이 없으면 `/auth` 만 겨누는 테스트도 컨텍스트 조립에서 멈춘다.
     *
     * 여기서도 유스케이스는 **실물**이다 — 가짜인 것은 저장소와 트랜잭션뿐이다.
     */
    @Bean
    fun workspaceService(
        workspaces: InMemoryWorkspaceRepository,
        transaction: TransactionRunner,
    ): WorkspaceService = WorkspaceService(workspaces, transaction)
}

/** 유일성을 **키 일치**로 판정한다 — `ix_users_email` 과 같은 축이다. */
class InMemoryUserRepository : UserRepository {
    private val byEmail = ConcurrentHashMap<String, StoredUser>()
    private val byId = ConcurrentHashMap<UUID, StoredUser>()

    override fun findByEmail(email: String): StoredUser? = byEmail[email]

    override fun findById(id: UUID): User? = byId[id]?.user

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User {
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH), passwordHash)
        if (byEmail.putIfAbsent(email, stored) != null) {
            throw EmailAlreadyRegisteredException("이미 가입된 이메일입니다")
        }
        byId[stored.user.id] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        val existing = byId[userId] ?: return
        val replaced = StoredUser(existing.user, passwordHash)
        byId[userId] = replaced
        byEmail[existing.user.email] = replaced
    }

    /** 계정 삭제 시나리오(M-3)를 위한 자리. 토큰은 유효한데 계정이 없는 상태를 만든다. */
    fun remove(userId: UUID) {
        val existing = byId.remove(userId) ?: return
        byEmail.remove(existing.user.email)
    }
}

/**
 * 작업 공간 저장소의 가짜.
 *
 * ## 규칙은 진짜다
 *
 * 가짜인 것은 **저장 매체**뿐이다. 소유 조건(모든 조회가 `ownerId` 로 걸린다)과 유일성
 * (같은 소유자 안에서 이름이 겹치면 409)은 실물과 같은 축으로 판정한다 — 그러지 않으면
 * 슬라이스 테스트가 「구현이 소유자를 안 봐도 초록」이 된다.
 *
 * **잠금은 흉내 내지 않는다.** 「마지막 하나」 판정의 동시성은 실제 트랜잭션과 행 잠금이
 * 있어야 잴 수 있으므로 `WorkspaceRepositoryTest`(Testcontainers)가 맡는다.
 * `createdAt` 은 삽입 순서대로 1초씩 벌려 둔다 — 목록 순서가 정해지지 않으면
 * 「첫 번째가 기본 작업 공간」을 잴 수 없다.
 */
class InMemoryWorkspaceRepository : WorkspaceRepository {
    private data class Row(
        val id: UUID,
        val ownerId: UUID,
        val name: String,
        val createdAt: Instant,
    )

    private val rows = mutableListOf<Row>()

    /** 테스트가 문서 수를 심는 자리. 실물에서는 `documents` 테이블이 답한다. */
    val documentCounts: MutableMap<UUID, Int> = mutableMapOf()

    override fun createDefault(userId: UUID): UUID = insert(userId, DEFAULT_WORKSPACE_NAME).id

    override fun listOwned(ownerId: UUID): List<WorkspaceListing> =
        rows
            .filter { it.ownerId == ownerId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map { WorkspaceListing(it.toWorkspace(), documentCounts[it.id] ?: 0) }

    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace = insert(ownerId, name).toWorkspace()

    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace? {
        val index = rows.indexOfFirst { it.id == workspaceId && it.ownerId == ownerId }
        // 없는 것과 남의 것을 가르지 않는다 — 조건 하나로 끝난다.
        if (index < 0) {
            return null
        }
        requireUniqueName(ownerId, name, exceptId = workspaceId)
        val renamed = rows[index].copy(name = name)
        rows[index] = renamed
        return renamed.toWorkspace()
    }

    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState? {
        val owned = rows.filter { it.ownerId == ownerId }
        if (owned.none { it.id == workspaceId }) {
            return null
        }
        return WorkspaceDeletionState(owned.size, documentCounts[workspaceId] ?: 0)
    }

    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean = rows.removeIf { it.id == workspaceId && it.ownerId == ownerId }

    private fun insert(
        ownerId: UUID,
        name: String,
    ): Row {
        requireUniqueName(ownerId, name, exceptId = null)
        val row = Row(UUID.randomUUID(), ownerId, name, Instant.EPOCH.plusSeconds(rows.size.toLong()))
        rows += row
        return row
    }

    /** `uq_workspaces_user_id_name` 과 같은 축이다. */
    private fun requireUniqueName(
        ownerId: UUID,
        name: String,
        exceptId: UUID?,
    ) {
        if (rows.any { it.ownerId == ownerId && it.name == name && it.id != exceptId }) {
            throw ConflictException(DUPLICATE_WORKSPACE_NAME_MESSAGE)
        }
    }

    private fun Row.toWorkspace(): Workspace = Workspace(id, name, createdAt)
}

/**
 * 해시를 흉내만 낸다 — **Argon2 를 슬라이스 테스트에서 돌리지 않는다.**
 *
 * 파라미터 조합이 1건당 64MiB 를 수십 밀리초 붙들고 있어, 계약 케이스 20여 건마다 그것을
 * 돌리면 슬라이스가 초 단위로 느려진다. 해시의 정확성은 `infrastructure` 단위 테스트가
 * 재고 여기서 재는 것은 **HTTP 계약**이다.
 */
class StubPasswordHasher : PasswordHasher {
    override fun hash(rawPassword: String): PasswordHash = PasswordHash("stub:$rawPassword")

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = stored.reveal() == "stub:$rawPassword"

    override fun needsRehash(stored: PasswordHash): Boolean = false

    /**
     * 어떤 비밀번호와도 일치하지 않는 값. [verify] 가 `"stub:"` 접두사를 요구하므로 이
     * 값으로는 절대 통과하지 않는다 — 계정 부재 경로가 실제 검증을 지나가는지를 슬라이스
     * 에서도 재려면 「일치하지 않음」이 성질로 성립해야 한다.
     *
     * 비용은 흉내 내지 않는다. 시간 축 회귀는 실물 Argon2 가 도는 `AuthEndpointReachTest`
     * 가 잰다.
     */
    override fun dummyHash(): PasswordHash = PasswordHash("stub-dummy")
}

/** 토큰 문자열을 사용자 id 그대로 쓴다. 서명·만료는 여기서 재지 않는다. */
class StubAccessTokens : AccessTokens {
    /** 설정 미비 503 은 실물 설정에서만 잴 수 있다 — `AuthUnavailableContractTest` 가 맡는다. */
    override fun ensureConfigured() = Unit

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("stub-token:$userId", STUB_LIFETIME_SECONDS)

    override fun verify(token: String): UUID {
        val subject = token.removePrefix("stub-token:")
        return runCatching { UUID.fromString(subject) }
            .getOrElse { throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다") }
    }

    private companion object {
        /**
         * 계약값이 아니다. `expires_in` 의 계약 준수는 **실물 설정에서** 재야 하므로
         * `AuthEndpointReachTest` 가 맡는다 — 테스트 배선이 값을 정해 놓고 그 값을
         * 단언하면 아무것도 검증하지 않는 것이다.
         */
        const val STUB_LIFETIME_SECONDS = 1L
    }
}

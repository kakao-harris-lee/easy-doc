package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 동시 최초 콜백 자기 경쟁 — backlog §1.4 「남은 결함(2026-09-04, 의도적으로 미해결) — 동시
 * 최초 콜백 경쟁이 오도하는 409를 낼 수 있다」의 옵션 ⑵ 고침(2026-09-06)을 실제 PostgreSQL로
 * 확인한다.
 *
 * 같은 사람이 같은 제공자 신원으로 콜백을 동시에 두 번 보내면(브라우저 탭 두 개 등), 신원
 * 유일 제약(`(provider, provider_user_id)`)을 통과한 두 요청이 모두 갈래 3(완전히 새로운
 * 사용자)을 타 `users.email` 유일 인덱스(`ix_users_email`)에서 하나만 커밋한다.
 * `SocialLoginService.callback` 이 나머지의 `EmailAlreadyRegisteredException` 을 곧장
 * 409로 올리는 대신 롤백 뒤 `findByProviderIdentity` 를 한 번 더 봐서 이긴 쪽 사용자로
 * 로그인 처리하는지 — `SocialUnlinkConcurrencyTest` 와 같은 방식(실제 PostgreSQL,
 * `CyclicBarrier` 로 동시 실행)으로 잰다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialLoginCallbackConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var identities: JdbcUserIdentityRepository
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("social_login_callback_concurrency")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        identities = JdbcUserIdentityRepository(JdbcClient.create(dataSource()))
        interference = Executors.newFixedThreadPool(2)
    }

    @AfterAll
    fun shutdown() {
        interference.shutdownNow()
    }

    @Test
    @DisplayName(
        "같은 제공자 신원으로 동시에 최초 콜백 두 개가 오면 정확히 하나만 계정을 만들고, " +
            "나머지는 롤백 뒤 재조회로 같은 사용자에게 로그인 토큰을 받는다 — " +
            "사용자·신원이 정확히 하나씩만 남는다(중복 계정이 생기지 않는다)",
    )
    fun `동시 최초 콜백은 자기 자신과 경쟁해도 같은 사용자로 수렴한다`() {
        val providerUserId = "concurrency-google-sub-${counter++}"
        val email = "callback-race${counter++}@example.test"
        val tokens = RecordingAccessTokens()

        val firstService = serviceOn(dataSource(), providerUserId, email, tokens)
        val secondService = serviceOn(dataSource(), providerUserId, email, tokens)
        val firstState = firstService.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        val secondState = secondService.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        // 두 스레드가 최대한 같은 순간에 callback 을 부르게 한다 — `SocialUnlinkConcurrencyTest`
        // 와 같은 이유(하나가 커밋해야 다른 하나가 유일 인덱스에 걸린다는 것이 이 테스트의 전제).
        val barrier = CyclicBarrier(2)
        val firstAttempt =
            interference.submit<Result<IssuedAccessToken>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching {
                    firstService.callback(SocialLoginProviderId.GOOGLE, "code-1", firstState, REDIRECT_URI)
                }
            }
        val secondAttempt =
            interference.submit<Result<IssuedAccessToken>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching {
                    secondService.callback(SocialLoginProviderId.GOOGLE, "code-2", secondState, REDIRECT_URI)
                }
            }

        val firstResult = firstAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val secondResult = secondAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val results = listOf(firstResult, secondResult)

        assertThat(results.map { it.isSuccess })
            .withFailMessage(
                "자기 자신과의 경쟁은 재조회로 복구돼 둘 다 성공해야 하는데 실제로는 %s",
                results.map { it.exceptionOrNull() },
            ).containsExactly(true, true)

        val userIds = results.map { tokens.verify(it.getOrThrow().token) }
        assertThat(userIds.toSet())
            .withFailMessage("두 콜백이 서로 다른 사용자로 갈렸다 — %s", userIds)
            .hasSize(1)

        val remainingUsers = database.queryFirstColumn("SELECT id FROM users WHERE email = '$email'")
        assertThat(remainingUsers)
            .withFailMessage("사용자가 하나가 아니라 %d 명 남았다 — 중복 계정이 생겼다", remainingUsers.size)
            .hasSize(1)

        val remainingIdentity = identities.findByProviderIdentity(SocialLoginProviderId.GOOGLE, providerUserId)
        assertThat(remainingIdentity)
            .withFailMessage("승자 신원이 남아 있지 않다")
            .isNotNull()
        assertThat(remainingIdentity!!.userId.toString()).isEqualTo(remainingUsers.single())
    }

    /** `SocialLoginService` 실물 — [providerUserId]·[email] 이 같은 신원을 항상 돌려주는 대역 제공자를 문다. */
    private fun serviceOn(
        dataSource: DataSource,
        providerUserId: String,
        email: String,
        accessTokens: AccessTokens,
    ): SocialLoginService {
        val client = JdbcClient.create(dataSource)
        return SocialLoginService(
            providers = mapOf(SocialLoginProviderId.GOOGLE to FixedIdentitySocialLoginProvider(providerUserId, email)),
            states = JdbcOAuthStateStore(client, Clock.systemUTC()),
            repositories =
                SocialLoginRepositories(
                    users = JdbcUserRepository(client),
                    identities = JdbcUserIdentityRepository(client),
                    workspaces = JdbcWorkspaceRepository(client),
                ),
            accessTokens = accessTokens,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            stateTtl = Duration.ofMinutes(10),
            // 두 신원 모두 emailVerified = true 로 준비한다 — 이 테스트는 이메일 인증 코드
            // 발급 경로(네이버 전용)를 재지 않는다.
            emailVerification = { error("이 테스트는 이메일 인증 코드 발급을 부르지 않는다") },
            credits = CreditAccountService(JdbcCreditAccountRepository(client), enforced = false),
        )
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 같은 제공자 신원을 항상 돌려주는 대역 — 두 스레드가 "같은 사람"을 흉내 내야 한다. */
    private class FixedIdentitySocialLoginProvider(
        private val providerUserId: String,
        private val email: String,
    ) : SocialLoginProvider {
        override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri == REDIRECT_URI

        override fun authorizationUrl(
            state: String,
            nonce: String,
            redirectUri: String,
        ): String = "https://accounts.social.test/o/oauth2/auth?state=$state&nonce=$nonce"

        override fun exchange(
            code: String,
            redirectUri: String,
            nonce: String,
        ): SocialIdentity = SocialIdentity(providerUserId, email, emailVerified = true)
    }

    /** JWT 없이 토큰↔사용자를 오가는 최소 대역 — 이 테스트는 서명 자체를 재지 않는다. */
    private class RecordingAccessTokens : AccessTokens {
        override fun ensureConfigured() {
            // no-op — 이 테스트는 "설정 안 됨" 갈래를 재지 않는다.
        }

        override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("token:$userId", 3600)

        override fun verify(token: String): UUID = UUID.fromString(token.removePrefix("token:"))
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"

        /** 방해 스레드가 서로를 기다리고, 잠긴 트랜잭션이 풀리기를 기다리는 상한. */
        const val TASK_TIMEOUT_SECONDS = 30L

        var counter = 0
    }
}

package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
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
 * `linkCallback` 자기 경쟁(PR #54 리뷰 후속 A, `SocialLoginService.linkCallback` KDoc 「self-race」)을
 * 실제 PostgreSQL로 확인한다.
 *
 * 같은 사용자가 같은 제공자 신원을 동시에 두 번 연결하면(브라우저 탭 두 개 등), 둘 다
 * 사전 검사(`findByProviderIdentity`·`findByUserAndProvider`)를 통과한 뒤 `JdbcUserIdentityRepository.link`
 * 에서 유일 인덱스(V6 `(provider, provider_user_id)` 또는 V9 `(user_id, provider)`)에 걸려 하나만
 * 커밋한다. `SocialLoginService.linkCallback` 이 진 쪽의 409를 재조회로 "이미 이 사용자에게
 * 연결됐다"는 무해한 성공으로 되돌리는지 — `SocialLoginCallbackConcurrencyTest`·
 * `SocialUnlinkConcurrencyTest`와 같은 방식(실제 PostgreSQL, `CyclicBarrier`로 동시 실행)으로 잰다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialLinkCallbackConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var identities: JdbcUserIdentityRepository
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("social_link_callback_concurrency")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val jdbcClient = JdbcClient.create(dataSource())
        users = JdbcUserRepository(jdbcClient)
        identities = JdbcUserIdentityRepository(jdbcClient)
        interference = Executors.newFixedThreadPool(2)
    }

    @AfterAll
    fun shutdown() {
        interference.shutdownNow()
    }

    @Test
    @DisplayName(
        "같은 사용자가 같은 제공자 신원을 동시에 두 번 연결하면 둘 다 성공하고(멱등), " +
            "user_identities 에는 정확히 한 행만 남는다",
    )
    fun `동시 연결 콜백은 자기 자신과 경쟁해도 신원이 하나만 남는다`() {
        val user = users.createWithoutPassword(uniqueEmail(), emailVerified = true)
        val providerUserId = "concurrency-link-google-sub-${counter++}"

        val firstService = serviceOn(dataSource(), providerUserId)
        val secondService = serviceOn(dataSource(), providerUserId)
        val firstState = firstService.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        val secondState = secondService.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        // 두 스레드가 최대한 같은 순간에 linkCallback 을 부르게 한다 — `SocialLoginCallbackConcurrencyTest`
        // 와 같은 이유(하나가 커밋해야 다른 하나가 유일 인덱스에 걸린다는 것이 이 테스트의 전제).
        val barrier = CyclicBarrier(2)
        val firstAttempt =
            interference.submit<Result<Unit>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching {
                    firstService.linkCallback(
                        user.id,
                        SocialLoginProviderId.GOOGLE,
                        "code-1",
                        firstState,
                        REDIRECT_URI,
                    )
                }
            }
        val secondAttempt =
            interference.submit<Result<Unit>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching {
                    secondService.linkCallback(
                        user.id,
                        SocialLoginProviderId.GOOGLE,
                        "code-2",
                        secondState,
                        REDIRECT_URI,
                    )
                }
            }

        val firstResult = firstAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val secondResult = secondAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val results = listOf(firstResult, secondResult)

        assertThat(results.map { it.isSuccess })
            .withFailMessage(
                "자기 자신과의 연결 경쟁은 재조회로 복구돼 둘 다 성공해야 하는데 실제로는 %s",
                results.map { it.exceptionOrNull() },
            ).containsExactly(true, true)

        val remainingIdentities = identities.findAllByUser(user.id)
        assertThat(remainingIdentities)
            .withFailMessage("동시 연결 뒤 신원이 정확히 하나가 아니다 — %s", remainingIdentities)
            .hasSize(1)
        assertThat(remainingIdentities.single().providerUserId).isEqualTo(providerUserId)
    }

    /** `SocialLoginService` 실물 — [providerUserId] 가 같은 신원을 항상 돌려주는 대역 제공자를 문다. */
    private fun serviceOn(
        dataSource: DataSource,
        providerUserId: String,
    ): SocialLoginService {
        val client = JdbcClient.create(dataSource)
        return SocialLoginService(
            providers = mapOf(SocialLoginProviderId.GOOGLE to FixedIdentitySocialLoginProvider(providerUserId)),
            states = JdbcOAuthStateStore(client, Clock.systemUTC()),
            repositories =
                SocialLoginRepositories(
                    users = JdbcUserRepository(client),
                    identities = JdbcUserIdentityRepository(client),
                    workspaces = JdbcWorkspaceRepository(client),
                ),
            accessTokens = NeverCalledAccessTokens,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            stateTtl = Duration.ofMinutes(10),
            emailVerification = { error("이 테스트는 linkCallback 만 부른다 — 이메일 인증 코드 발급은 부르지 않는다") },
        )
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueEmail(): String = "link-callback-race${counter++}@example.test"

    /** 같은 제공자 신원을 항상 돌려주는 대역 — 두 스레드가 "같은 연결 시도"를 흉내 내야 한다. */
    private class FixedIdentitySocialLoginProvider(private val providerUserId: String) : SocialLoginProvider {
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
        ): SocialIdentity = SocialIdentity(providerUserId, email = null, emailVerified = false)
    }

    /** `linkCallback` 은 이 포트를 부르지 않는다 — 불리면 배선 실수다. */
    private object NeverCalledAccessTokens : AccessTokens {
        override fun ensureConfigured(): Unit = error("linkCallback 은 토큰 발급을 부르지 않는다")

        override fun issue(userId: UUID): IssuedAccessToken = error("linkCallback 은 토큰 발급을 부르지 않는다")

        override fun verify(token: String): UUID = error("linkCallback 은 토큰 검증을 부르지 않는다")
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"

        /** 방해 스레드가 서로를 기다리고, 잠긴 트랜잭션이 풀리기를 기다리는 상한. */
        const val TASK_TIMEOUT_SECONDS = 30L

        var counter = 0
    }
}

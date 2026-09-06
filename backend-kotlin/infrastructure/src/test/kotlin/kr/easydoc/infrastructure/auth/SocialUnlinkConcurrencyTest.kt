package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.ConsumedOAuthState
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.OAuthChallenge
import kr.easydoc.application.auth.OAuthStateStore
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.core.exceptions.ConflictException
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 연결 해제(`SocialLoginService.unlink`) 동시 요청 경합 — 리뷰 지적 H1.
 *
 * 비밀번호가 없는 계정에서 서로 **다른** 제공자를 동시에 해제하면, 신원 개수만으로
 * 판정하는 낙관적 검사는(각 트랜잭션이 자기 시작 시점의 스냅샷만 보므로) 둘 다
 * "신원이 둘이니 안전하다"고 통과시켜 로그인 수단이 0개로 떨어질 수 있다.
 * `UserRepository.lockForUpdate`(`SELECT … FOR UPDATE`)가 두 번째 트랜잭션을 첫
 * 트랜잭션의 커밋까지 대기시켜 이 경쟁을 막는다 — `EnvelopeRotationConcurrencyTest`와
 * 같은 실제 PostgreSQL 기반 동시성 검증이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialUnlinkConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var identities: JdbcUserIdentityRepository
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("social_unlink_concurrency")
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
        "비밀번호 없는 계정에서 서로 다른 제공자를 동시에 해제하면 " +
            "정확히 하나는 성공하고 하나는 409(마지막 로그인 수단)다 — 신원은 하나가 남는다",
    )
    fun `동시 해제는 하나만 성공하고 로그인 수단이 0개로 떨어지지 않는다`() {
        val user = users.createWithoutPassword(uniqueEmail(), emailVerified = true)
        identities.link(user.id, SocialLoginProviderId.GOOGLE, "concurrency-google-sub", user.email, true)
        identities.link(user.id, SocialLoginProviderId.KAKAO, "concurrency-kakao-sub", user.email, true)

        // 두 스레드가 최대한 같은 순간에 unlink 를 부르게 한다 — 하나가 먼저 행 잠금을
        // 얻으면 다른 하나는 그 트랜잭션이 끝날 때까지 블록돼야 한다(그것이 이 테스트의 전제).
        val barrier = CyclicBarrier(2)
        val googleService = serviceOn(dataSource())
        val kakaoService = serviceOn(dataSource())

        val googleAttempt =
            interference.submit<Result<Unit>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching { googleService.unlink(user.id, SocialLoginProviderId.GOOGLE) }
            }
        val kakaoAttempt =
            interference.submit<Result<Unit>> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching { kakaoService.unlink(user.id, SocialLoginProviderId.KAKAO) }
            }

        val googleResult = googleAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val kakaoResult = kakaoAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val results = listOf(googleResult, kakaoResult)

        val successes = results.filter { it.isSuccess }
        val conflicts = results.mapNotNull { it.exceptionOrNull() }

        assertThat(successes)
            .withFailMessage("정확히 하나만 성공해야 하는데 %d 개가 성공했다 — %s", successes.size, results)
            .hasSize(1)
        assertThat(conflicts)
            .withFailMessage("정확히 하나는 409(ConflictException)여야 하는데 실제로는 %s", results)
            .hasSize(1)
        assertThat(conflicts.single())
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.LAST_LOGIN_METHOD_MESSAGE)

        val remaining = identities.findAllByUser(user.id)
        assertThat(remaining)
            .withFailMessage(
                "동시 해제 뒤 남은 신원이 정확히 하나가 아니다 — 로그인 수단이 0개로 떨어졌거나 " +
                    "둘 다 남아 경쟁이 제대로 막히지 않았다: %s",
                remaining,
            ).hasSize(1)
    }

    /** `SocialLoginService` 실물 — `unlink` 만 부르므로 나머지 협력자는 부르면 끊는 대역이다. */
    private fun serviceOn(dataSource: DataSource): SocialLoginService {
        val client = JdbcClient.create(dataSource)
        return SocialLoginService(
            providers = emptyMap(),
            states = NeverCalledOAuthStateStore,
            repositories =
                SocialLoginRepositories(
                    users = JdbcUserRepository(client),
                    identities = JdbcUserIdentityRepository(client),
                    workspaces = JdbcWorkspaceRepository(client),
                ),
            accessTokens = NeverCalledAccessTokens,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            stateTtl = Duration.ofMinutes(10),
            emailVerification = { error("이 테스트는 unlink 만 부른다 — 이메일 인증 발급은 부르지 않는다") },
        )
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueEmail(): String = "unlink-race${counter++}@example.test"

    /** `unlink` 는 이 포트를 부르지 않는다 — 불리면 배선 실수다. */
    private object NeverCalledAccessTokens : AccessTokens {
        override fun ensureConfigured(): Unit = error("unlink 는 토큰 발급을 부르지 않는다")

        override fun issue(userId: UUID): IssuedAccessToken = error("unlink 는 토큰 발급을 부르지 않는다")

        override fun verify(token: String): UUID = error("unlink 는 토큰 검증을 부르지 않는다")
    }

    /** `unlink` 는 이 포트를 부르지 않는다 — 불리면 배선 실수다. */
    private object NeverCalledOAuthStateStore : OAuthStateStore {
        override fun issue(
            provider: SocialLoginProviderId,
            redirectUri: String,
            ttl: Duration,
            userId: UUID?,
        ): OAuthChallenge = error("unlink 는 state 발급을 부르지 않는다")

        override fun consume(
            provider: SocialLoginProviderId,
            state: String,
            redirectUri: String,
        ): ConsumedOAuthState? = error("unlink 는 state 소비를 부르지 않는다")
    }

    private companion object {
        /** 방해 스레드가 서로를 기다리고, 잠긴 트랜잭션이 풀리기를 기다리는 상한. */
        const val TASK_TIMEOUT_SECONDS = 30L

        var counter = 0
    }
}

package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.application.auth.UserIdentity
import kr.easydoc.application.auth.UserIdentityRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `SocialLinkCallbackConcurrencyTest`(자기 경쟁, PR #54 리뷰 후속 A)가 CI에서 간헐적으로
 * 실패한 원인을 latch 로 순서를 고정해 결정적으로 재현한다.
 *
 * **처음 의심했던 자리(`link()` 의 유일 인덱스 충돌 → `recoverFromLinkRace`)는 결백하다** —
 * `link()` 삽입만 latch 로 이긴 스레드의 커밋 뒤로 미루는 재현을 먼저 만들어 돌렸더니
 * (이 파일 이전 버전) 항상 통과했다. `recoverFromLinkRace` 는 재조회로 자기 경쟁을
 * 정확히 가려낸다.
 *
 * **진짜 경쟁 창은 `linkCallback` 의 사전 검사 두 번 사이다**(`SocialLoginService.linkCallback`,
 * `existing = findByProviderIdentity(...)` 다음 `findByUserAndProvider(...)`). 첫 번째가
 * "없음"을 본 뒤 두 번째가 실행되기 **전에** 다른 스레드가 통째로 커밋해 버리면, 두
 * 번째 조회는 그 커밋된 행을 찾아내지만 — **그 행이 자기 자신과 같은 신원인지
 * (`providerUserId` 일치) 확인하지 않고** 곧장 `providerAlreadyLinkedMessage` 로 409를
 * 던진다. 이 분기는 `link()` 를 부르기도 전, 즉 try/catch 바깥이라 `recoverFromLinkRace`
 * 를 전혀 타지 않는다 — 유일 인덱스도, 복구 로직도 무관하게 순수히 사전 검사 자체의
 * TOCTOU다.
 *
 * 그래서 여기서는 진 스레드(B)의 사전 검사 두 번 사이 순서를 latch 로 고정한다 — B의
 * 첫 번째 검사(`findByProviderIdentity`)가 "없음"을 본 뒤에야 A가 시작하고, A가 통째로
 * 커밋한 뒤에야 B의 두 번째 검사(`findByUserAndProvider`)가 실행되게 만든다(아래
 * [LinkRaceOrderingUserIdentityRepository]). 원래 테스트가 노리던 경쟁 창을 스레드
 * 스케줄링 운(`Thread.sleep`)에 맡기지 않고 두 latch 로 그대로 만든다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialLinkCallbackDeterministicRaceTest {
    private lateinit var database: DatabaseHandle
    private lateinit var identities: JdbcUserIdentityRepository
    private lateinit var users: JdbcUserRepository
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("social_link_callback_deterministic_race")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val jdbcClient = JdbcClient.create(dataSource())
        users = JdbcUserRepository(jdbcClient)
        identities = JdbcUserIdentityRepository(jdbcClient)
        interference = Executors.newFixedThreadPool(1)
    }

    @AfterAll
    fun shutdown() {
        interference.shutdownNow()
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    @Test
    @DisplayName(
        "진 스레드의 두 번째 사전 검사(findByUserAndProvider)가 이긴 스레드의 커밋 뒤로 " +
            "latch 고정돼도, 그 행이 자기 자신과 같은 신원이면 409가 아니라 멱등 성공이어야 한다",
    )
    fun `사전 검사 두 번 사이에 자기 자신이 커밋해도 자기 경쟁은 멱등 성공으로 복구된다`() {
        val user = users.createWithoutPassword("link-callback-deterministic@example.test", emailVerified = true)
        val providerUserId = "deterministic-link-google-sub"

        val firstService = serviceOn(dataSource(), providerUserId, identities)

        // B(진 스레드)의 identities 저장소만 이 순서 고정 데코레이터를 씌운다. A(이긴
        // 스레드)는 손대지 않은 identities 를 그대로 쓰므로, 스레드로 호출자를 가릴
        // 필요 없이 "B 의 호출인지"가 곧 "이 데코레이터를 거치는지"와 같다.
        val firstPrecheckDone = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        val stallingIdentities =
            LinkRaceOrderingUserIdentityRepository(identities, firstPrecheckDone, winnerCommitted)
        val secondService = serviceOn(dataSource(), providerUserId, stallingIdentities)
        val firstState = firstService.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        val secondState = secondService.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        // B를 먼저 스레드에 올린다 — 첫 번째 검사가 "없음"을 본 뒤 두 번째 검사에서 멈춰 선다.
        val secondAttempt =
            interference.submit<Result<Unit>> {
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
        // B의 첫 번째 검사가 "없음"을 본 뒤에만 A를 시작한다 — Thread.sleep 같은 시간
        // 어림짐작이 아니라, B 자신이 신호를 연 뒤라는 사실로 순서를 고정한다.
        check(firstPrecheckDone.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "진 스레드의 첫 번째 사전 검사를 기다리다 시간 초과했다"
        }

        // A(이긴 스레드) 는 메인 스레드에서 그대로 끝까지 돌려 커밋을 확정한다.
        val firstResult =
            runCatching {
                firstService.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "code-1", firstState, REDIRECT_URI)
            }
        assertThat(firstResult.isSuccess)
            .withFailMessage("이긴 스레드(A) 자체가 실패했다 — %s", firstResult.exceptionOrNull())
            .isTrue()

        // A가 커밋을 끝냈으니 이제 B의 두 번째 검사를 A의 행이 이미 있는 상태로 밀어 넣는다.
        winnerCommitted.countDown()
        val secondResult = secondAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertThat(secondResult.isSuccess)
            .withFailMessage(
                "자기 자신과의 연결 경쟁은 재조회로 복구돼 성공해야 하는데 실제로는 %s",
                secondResult.exceptionOrNull(),
            ).isTrue()

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
        identityRepository: UserIdentityRepository,
    ): SocialLoginService {
        val client = JdbcClient.create(dataSource)
        return SocialLoginService(
            providers = mapOf(SocialLoginProviderId.GOOGLE to FixedIdentitySocialLoginProvider(providerUserId)),
            states = JdbcOAuthStateStore(client, Clock.systemUTC()),
            repositories =
                SocialLoginRepositories(
                    users = JdbcUserRepository(client),
                    identities = identityRepository,
                    workspaces = JdbcWorkspaceRepository(client),
                ),
            accessTokens = NeverCalledAccessTokens,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            stateTtl = Duration.ofMinutes(10),
            emailVerification = { error("이 테스트는 linkCallback 만 부른다 — 이메일 인증 코드 발급은 부르지 않는다") },
            credits = CreditAccountService(JdbcCreditAccountRepository(client), enforced = false),
        )
    }

    /**
     * B(진 스레드)의 사전 검사 두 번 사이 순서를 latch 로 고정한다.
     *
     * `findByProviderIdentity`(첫 번째 검사)는 그대로 위임하되, 끝나는 대로
     * [firstPrecheckDone] 을 연다 — 호출자(테스트 메인 스레드)가 이 신호를 보고서야
     * A(이긴 스레드)를 시작하므로, B의 첫 번째 검사가 "없음"을 본 뒤에만 A가 커밋을
     * 시작한다. `findByUserAndProvider`(두 번째 검사)는 [winnerCommitted] 가 열릴 때까지
     * 멈춘다 — A가 커밋을 끝낸 뒤에만 두 번째 검사가 실행된다. `link()` 는 손대지 않는다.
     */
    private class LinkRaceOrderingUserIdentityRepository(
        private val delegate: UserIdentityRepository,
        private val firstPrecheckDone: CountDownLatch,
        private val winnerCommitted: CountDownLatch,
    ) : UserIdentityRepository by delegate {
        override fun findByProviderIdentity(
            provider: SocialLoginProviderId,
            providerUserId: String,
        ): UserIdentity? {
            val result = delegate.findByProviderIdentity(provider, providerUserId)
            firstPrecheckDone.countDown()
            return result
        }

        override fun findByUserAndProvider(
            userId: UUID,
            provider: SocialLoginProviderId,
        ): UserIdentity? {
            check(winnerCommitted.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "이긴 스레드의 커밋 신호를 기다리다 시간 초과했다"
            }
            return delegate.findByUserAndProvider(userId, provider)
        }
    }

    /** `linkCallback` 은 이 포트를 부르지 않는다 — 불리면 배선 실수다. */
    private object NeverCalledAccessTokens : AccessTokens {
        override fun ensureConfigured(): Unit = error("linkCallback 은 토큰 발급을 부르지 않는다")

        override fun issue(userId: UUID): IssuedAccessToken = error("linkCallback 은 토큰 발급을 부르지 않는다")

        override fun verify(token: String): UUID = error("linkCallback 은 토큰 검증을 부르지 않는다")
    }

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

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val TASK_TIMEOUT_SECONDS = 30L
    }
}

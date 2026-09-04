package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.PostSignupEmailVerification
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * 401 로 끝나는 갈래들이 같은 양의 일을 한다 — 계약 `x-auth.failure_uniformity` 의 시간
 * 문장을 구조로 강제한다 (게이트 23: codex C-2 · privacy-gate 기록 ①).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationWorkUniformityTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbcClient: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var counting: CountingDataSource
    private lateinit var countedAuth: AuthService
    private lateinit var tokens: JwtAccessTokens

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("auth_work_uniformity")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbcClient = JdbcClient.create(dataSource())
        users = JdbcUserRepository(jdbcClient)
        tokens = JwtAccessTokens(Secret(SIGNING_KEY), LIFETIME, MIN_SECRET_BYTES, Clock.systemUTC())

        counting = CountingDataSource(dataSource())
        val countingJdbc = JdbcClient.create(counting)
        countedAuth =
            AuthService(
                users = JdbcUserRepository(countingJdbc),
                workspaces = JdbcWorkspaceRepository(countingJdbc),
                passwords = UnusedPasswordHasher,
                accessTokens = tokens,
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting))),
                // 이 테스트는 `signup` 을 부르지 않는다(`authenticate`·`login` 시간만 잰다) —
                // 발급 자체가 관심사가 아니므로 no-op 이면 충분하다.
                emailVerification = PostSignupEmailVerification { },
            )
    }

    @Test
    @DisplayName("토큰이 든 401 갈래와 성공 갈래가 같은 수의 SQL 문을 낸다 — failure_uniformity 의 시간 문장")
    fun `인증 경계의 일하는 양이 실패 사유로 갈리지 않는다`() {
        val live = newUser()
        val deleted = newUser()
        deleteUserRow(deleted)

        val liveToken = tokens.issue(live).token
        val counts =
            linkedMapOf(
                "유효 토큰(성공)" to countAuthenticate(liveToken),
                "삭제 계정" to countAuthenticate(tokens.issue(deleted).token),
                "위조 서명" to countAuthenticate(withBrokenSignature(liveToken)),
                "만료 토큰" to countAuthenticate(expiredToken(live)),
                "JWT 형식 아님" to countAuthenticate("이건 토큰이 아니다"),
            )

        assertThat(counts.values)
            .withFailMessage(
                "인증 경계가 도는 SQL 문 수가 갈리거나 %d 가 아니다 — %s. " +
                    "실패 사유가 「일한 양」으로 새면 반복 측정으로 계정 삭제 여부를 가릴 수 있다 " +
                    "(계약 x-auth.failure_uniformity).",
                AUTHENTICATE_STATEMENTS,
                counts,
            ).containsOnly(AUTHENTICATE_STATEMENTS)
    }

    @Test
    @DisplayName("균일화가 결과를 바꾸지 않는다 — 실패 갈래는 여전히 InvalidCredentials 다")
    fun `더미 조회가 실패를 성공으로 만들지 않는다`() {
        val live = newUser()
        val liveToken = tokens.issue(live).token

        listOf(withBrokenSignature(liveToken), expiredToken(live), "이건 토큰이 아니다").forEach { token ->
            assertThat(runCatching { countedAuth.authenticate(token) }.exceptionOrNull())
                .withFailMessage("실패해야 할 토큰이 통과했거나 다른 예외로 끝났다")
                .isInstanceOf(InvalidCredentialsException::class.java)
        }
        assertThat(countedAuth.authenticate(liveToken)).isEqualTo(live)
    }

    private fun countAuthenticate(token: String): Int =
        counting.countStatements { runCatching { countedAuth.authenticate(token) } }

    private fun newUser(): UUID = users.create(uniqueEmail(), FIXTURE_HASH).id

    private fun uniqueEmail(): String = "auth-uniformity${counter++}@example.test"

    private fun deleteUserRow(userId: UUID) {
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", userId).update()
    }

    /**
     * 서명 첫 바이트의 1비트만 뒤집는다. 헤더·페이로드가 그대로라 갈리는 것이 서명 검증
     * 하나다 — 다른 것을 함께 바꾸면 무엇 때문에 갈렸는지 알 수 없다.
     */
    private fun withBrokenSignature(token: String): String {
        val parts = token.split('.')
        require(parts.size == 3) { "JWT 가 세 조각이 아니다" }
        val flipped =
            Base64
                .getUrlDecoder()
                .decode(parts[2])
                .also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        return "${parts[0]}.${parts[1]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)}"
    }

    private fun expiredToken(userId: UUID): String {
        val past = Instant.now().minus(LIFETIME).minusSeconds(60)
        return JwtAccessTokens(Secret(SIGNING_KEY), LIFETIME, MIN_SECRET_BYTES, Clock.fixed(past, ZoneOffset.UTC))
            .issue(userId)
            .token
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 이 테스트는 비밀번호를 쓰지 않는다. 불리면 시끄럽게 깨져야 배선 실수가 드러난다. */
    private object UnusedPasswordHasher : PasswordHasher {
        override fun hash(rawPassword: String): PasswordHash = error("이 테스트는 해시를 쓰지 않는다")

        override fun verify(
            rawPassword: String,
            stored: PasswordHash,
        ): Boolean = error("이 테스트는 해시를 쓰지 않는다")

        override fun needsRehash(stored: PasswordHash): Boolean = error("이 테스트는 해시를 쓰지 않는다")

        override fun dummyHash(): PasswordHash = error("이 테스트는 해시를 쓰지 않는다")
    }

    private companion object {
        var counter = 0

        /** 형태만 맞으면 되는 더미 PHC — 이 파일은 비밀번호 검증을 재지 않는다. */
        val FIXTURE_HASH = PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")

        const val SIGNING_KEY = "test-only-signing-key-0123456789-abcdef"
        const val MIN_SECRET_BYTES = 32
        val LIFETIME: Duration = Duration.ofMinutes(60)

        /** 인증 경계 한 번이 도는 SQL 문 수. */
        const val AUTHENTICATE_STATEMENTS = 1
    }
}

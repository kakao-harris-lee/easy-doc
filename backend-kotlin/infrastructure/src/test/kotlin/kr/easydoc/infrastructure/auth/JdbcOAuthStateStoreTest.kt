package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** `oauth_states` 저장소 — 실제 PostgreSQL 에서만 잴 수 있는 단발 소비·만료. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcOAuthStateStoreTest {
    private lateinit var database: DatabaseHandle
    private lateinit var clock: MutableClock
    private lateinit var states: JdbcOAuthStateStore

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("oauth_state_store")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        clock = MutableClock(Instant.parse("2026-09-04T00:00:00Z"))
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        states = JdbcOAuthStateStore(JdbcClient.create(dataSource), clock)
    }

    @Test
    @DisplayName("발급한 state·redirect_uri 로 소비하면 발급 당시 nonce 를 돌려준다")
    fun `정상 소비는 nonce 를 돌려준다`() {
        val challenge = states.issue(SocialLoginProviderId.GOOGLE, REDIRECT_URI, Duration.ofMinutes(10))

        val nonce = states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)

        assertThat(nonce).isEqualTo(challenge.nonce)
    }

    @Test
    @DisplayName("한 번 소비한 state 는 다시 쓸 수 없다 — 단발")
    fun `재사용은 null 이다`() {
        val challenge = states.issue(SocialLoginProviderId.GOOGLE, REDIRECT_URI, Duration.ofMinutes(10))
        states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)

        val second = states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)

        assertThat(second).isNull()
    }

    @Test
    @DisplayName("발급하지 않은 state 는 null 이다")
    fun `없는 state 는 null 이다`() {
        assertThat(states.consume(SocialLoginProviderId.GOOGLE, "never-issued", REDIRECT_URI)).isNull()
    }

    @Test
    @DisplayName("redirect_uri 가 발급 시점과 다르면 소비되지 않는다")
    fun `redirect_uri 불일치는 null 이다`() {
        val challenge = states.issue(SocialLoginProviderId.GOOGLE, REDIRECT_URI, Duration.ofMinutes(10))

        val nonce = states.consume(SocialLoginProviderId.GOOGLE, challenge.state, "https://other.example.test/callback")

        assertThat(nonce).isNull()
        // 잘못된 redirect_uri 로의 시도가 그 state 를 태우지 않는다 — 올바른 redirect_uri 로는 여전히 쓸 수 있다.
        val retried = states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)
        assertThat(retried).isEqualTo(challenge.nonce)
    }

    @Test
    @DisplayName("TTL 이 지난 state 는 만료로 소비되지 않는다")
    fun `만료된 state 는 null 이다`() {
        val challenge = states.issue(SocialLoginProviderId.GOOGLE, REDIRECT_URI, Duration.ofMinutes(10))
        clock.advance(Duration.ofMinutes(11))

        assertThat(states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)).isNull()
    }

    @Test
    @DisplayName("다른 provider 이름으로는 소비되지 않는다 — provider+state+redirect_uri 가 함께 바인딩이다")
    fun `provider 불일치는 null 이다`() {
        val challenge = states.issue(SocialLoginProviderId.GOOGLE, REDIRECT_URI, Duration.ofMinutes(10))

        // enum 이 google 하나뿐이라 문자열로 다른 provider 를 직접 발급해 대조한다.
        database.connect().use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE oauth_states SET provider = 'kakao' WHERE state = '${challenge.state}'",
                )
            }
        }

        assertThat(states.consume(SocialLoginProviderId.GOOGLE, challenge.state, REDIRECT_URI)).isNull()
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
    }
}

/** 만료 경계를 재기 위한 시계 — [JwtAccessTokensTest] 와 같은 필요다. */
private class MutableClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

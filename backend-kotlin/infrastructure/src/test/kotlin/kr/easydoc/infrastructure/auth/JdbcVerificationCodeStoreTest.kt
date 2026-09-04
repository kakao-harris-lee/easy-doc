package kr.easydoc.infrastructure.auth

import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import java.util.UUID

/** `email_verification_codes` 저장소 — 실제 PostgreSQL 에서만 잴 수 있는 단발성·만료·상한. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcVerificationCodeStoreTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var clock: VerificationCodeClock
    private lateinit var codes: JdbcVerificationCodeStore

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("verification_code_store")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
    }

    @BeforeEach
    fun resetClock() {
        clock = VerificationCodeClock(Instant.parse("2026-09-04T00:00:00Z"))
        codes = JdbcVerificationCodeStore(jdbc, clock)
    }

    @Test
    @DisplayName("발급된 코드는 6자리 숫자이고 평문으로 저장되지 않는다")
    fun `발급 코드는 6자리이고 해시로 저장된다`() {
        val userId = newUser()

        val code = codes.issue(userId, TTL, COOLDOWN)

        assertThat(code).hasSize(6)
        assertThat(code.all(Char::isDigit)).isTrue()
        val storedHash = queryColumn(userId, "code_hash")
        assertThat(storedHash).isNotEqualTo(code)
    }

    @Test
    @DisplayName("정답 코드로 확인하면 소비되고, 같은 코드는 다시 쓸 수 없다")
    fun `정답은 단발 소비된다`() {
        val userId = newUser()
        val code = codes.issue(userId, TTL, COOLDOWN)

        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS)).isTrue()
        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS))
            .withFailMessage("소비된 코드가 재사용됐다")
            .isFalse()
    }

    @Test
    @DisplayName("오답은 실패고, 시도 횟수가 상한에 닿으면 정답도 더는 통하지 않는다")
    fun `오답 상한을 넘으면 정답도 거절된다`() {
        val userId = newUser()
        val code = codes.issue(userId, TTL, COOLDOWN)

        repeat(MAX_ATTEMPTS) {
            assertThat(codes.attempt(userId, "000000", MAX_ATTEMPTS)).isFalse()
        }

        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS))
            .withFailMessage("시도 상한을 넘긴 코드가 여전히 정답을 받아들인다")
            .isFalse()
    }

    @Test
    @DisplayName("발급하지 않은 사용자의 확인은 항상 false다")
    fun `발급하지 않았으면 항상 실패다`() {
        val userId = newUser()

        assertThat(codes.attempt(userId, "123456", MAX_ATTEMPTS)).isFalse()
    }

    @Test
    @DisplayName("TTL 이 지난 코드는 정답이어도 거절된다")
    fun `만료된 코드는 거절된다`() {
        val userId = newUser()
        val code = codes.issue(userId, TTL, COOLDOWN)
        clock.advance(TTL.plusSeconds(1))

        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS)).isFalse()
    }

    @Test
    @DisplayName("재발급은 이전 활성 코드를 무효화한다 — 활성 코드는 항상 최대 하나")
    fun `재발급은 이전 코드를 무효화한다`() {
        val userId = newUser()
        val firstCode = codes.issue(userId, TTL, COOLDOWN)
        clock.advance(COOLDOWN.plusSeconds(1))

        val secondCode = codes.issue(userId, TTL, COOLDOWN)

        assertThat(codes.attempt(userId, firstCode, MAX_ATTEMPTS))
            .withFailMessage("재발급 뒤에도 이전 코드가 살아 있다")
            .isFalse()
        assertThat(codes.attempt(userId, secondCode, MAX_ATTEMPTS)).isTrue()
    }

    @Test
    @DisplayName("쿨다운 안의 재발급은 RateLimitedException — 남은 시간이 실려 나간다")
    fun `쿨다운 안의 재발급은 거절된다`() {
        val userId = newUser()
        codes.issue(userId, TTL, COOLDOWN)
        clock.advance(Duration.ofSeconds(10))

        assertThatThrownBy { codes.issue(userId, TTL, COOLDOWN) }
            .isInstanceOf(RateLimitedException::class.java)
            .satisfies({ exception ->
                assertThat((exception as RateLimitedException).retryAfterSeconds).isEqualTo(50)
            })
    }

    @Test
    @DisplayName("쿨다운이 지나면 다시 발급할 수 있다")
    fun `쿨다운이 지나면 재발급된다`() {
        val userId = newUser()
        codes.issue(userId, TTL, COOLDOWN)
        clock.advance(COOLDOWN.plusSeconds(1))

        assertThat(codes.issue(userId, TTL, COOLDOWN)).isNotBlank()
    }

    @Test
    @DisplayName("서로 다른 사용자의 코드는 섞이지 않는다")
    fun `사용자마다 독립이다`() {
        val first = newUser()
        val second = newUser()
        val firstCode = codes.issue(first, TTL, COOLDOWN)
        codes.issue(second, TTL, COOLDOWN)

        assertThat(codes.attempt(second, firstCode, MAX_ATTEMPTS))
            .withFailMessage("남의 코드로 확인이 통과했다")
            .isFalse()
    }

    private fun newUser(): UUID = users.create("code-${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_HASH)).id

    private fun queryColumn(
        userId: UUID,
        column: String,
    ): String =
        jdbc
            .sql(
                """
                SELECT $column FROM email_verification_codes
                WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
            ).param("userId", userId)
            .query { rs, _ -> rs.getString(column) }
            .single()

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        val COOLDOWN: Duration = Duration.ofSeconds(60)
        const val MAX_ATTEMPTS = 5

        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$dGVzdC1oYXNo"
    }
}

/** 만료·쿨다운 경계를 재기 위한 시계 — [JdbcOAuthStateStoreTest] 와 같은 필요다. */
private class VerificationCodeClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

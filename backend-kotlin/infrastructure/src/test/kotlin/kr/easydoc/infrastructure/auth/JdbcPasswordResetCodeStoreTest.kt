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

/**
 * `password_reset_codes` 저장소 — `JdbcVerificationCodeStoreTest`와 같은 계약을
 * [JdbcOneTimeCodeStore] 공유 메커니즘 위에서 테이블만 바꿔 잰다. 두 저장소가 SQL을
 * 공유하므로 상수 시간 비교·경쟁 방지 가드 같은 세부 사항은 여기서 되풀이하지 않고
 * "이 테이블에 대해서도 발급·확인·쿨다운·상한이 동작한다"는 배선만 확인한다 — 이
 * 클래스 자체가 V11 마이그레이션이 실제로 적용됨을 함께 증명한다(`prepare`가 전체
 * 마이그레이션 체인을 돈다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPasswordResetCodeStoreTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var clock: PasswordResetCodeClock
    private lateinit var codes: JdbcPasswordResetCodeStore

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("password_reset_code_store")
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
        clock = PasswordResetCodeClock(Instant.parse("2026-09-06T00:00:00Z"))
        codes = JdbcPasswordResetCodeStore(jdbc, clock)
    }

    @Test
    @DisplayName("발급된 코드는 6자리 숫자이고 password_reset_codes 에 해시로 저장된다")
    fun `발급 코드는 6자리이고 해시로 저장된다`() {
        val userId = newUser()

        val code = codes.issue(userId, TTL, COOLDOWN)

        assertThat(code).hasSize(6)
        assertThat(code.all(Char::isDigit)).isTrue()
        val storedHash =
            jdbc
                .sql(
                    """
                    SELECT code_hash FROM password_reset_codes
                    WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1
                    """.trimIndent(),
                ).param("userId", userId)
                .query { rs, _ -> rs.getString("code_hash") }
                .single()
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
    @DisplayName("오답 5회는 코드를 무효화한다 — 이후 정답도 통하지 않는다")
    fun `오답 상한을 넘으면 정답도 거절된다`() {
        val userId = newUser()
        val code = codes.issue(userId, TTL, COOLDOWN)

        repeat(MAX_ATTEMPTS) {
            assertThat(codes.attempt(userId, "000000", MAX_ATTEMPTS)).isFalse()
        }

        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS)).isFalse()
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
    @DisplayName("이메일 인증 코드 테이블과 섞이지 않는다 — 같은 사용자라도 저장소가 다르다")
    fun `이메일 인증 코드 저장소와 독립이다`() {
        val userId = newUser()
        val verificationCodes = JdbcVerificationCodeStore(jdbc, clock)
        val verificationCode = verificationCodes.issue(userId, TTL, COOLDOWN)

        val resetCode = codes.issue(userId, TTL, COOLDOWN)

        assertThat(codes.attempt(userId, verificationCode, MAX_ATTEMPTS))
            .withFailMessage("이메일 인증 코드로 비밀번호 재설정 확인이 통과했다")
            .isFalse()
        assertThat(codes.attempt(userId, resetCode, MAX_ATTEMPTS)).isTrue()
    }

    private fun newUser(): UUID = users.create("reset-${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_HASH)).id

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        val COOLDOWN: Duration = Duration.ofSeconds(60)
        const val MAX_ATTEMPTS = 5

        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$dGVzdC1oYXNo"
    }
}

/** 만료·쿨다운 경계를 재기 위한 시계 — [JdbcVerificationCodeStoreTest]의 것과 같은 필요다. */
private class PasswordResetCodeClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

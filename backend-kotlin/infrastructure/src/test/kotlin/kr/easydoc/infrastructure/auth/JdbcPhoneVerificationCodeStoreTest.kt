package kr.easydoc.infrastructure.auth

import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
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
 * `phone_verification_codes` 저장소의 [JdbcOneTimeCodeStore.revoke] 스코프 — PR #123 리뷰
 * MEDIUM 지적: `revoke(userId)` 가 사용자의 활성 코드를 무조건 지우면, 발급 → 발송 지연 →
 * 재발급(구코드 무효화) → 발송 성공 → 원래 요청의 실패 처리 순서가 겹칠 때 방금 전달된
 * 새 코드까지 회수해버린다([kr.easydoc.application.auth.OneTimeCodeStore.revoke] KDoc).
 * 여기서는 그 회귀를 코드 스코프로 직접 잰다 — 오래된(stale) 코드로 회수해도 이후 재발급된
 * 활성 코드는 살아남아야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPhoneVerificationCodeStoreTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var clock: PhoneVerificationCodeClock
    private lateinit var codes: JdbcPhoneVerificationCodeStore

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("phone_verification_code_store")
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
        clock = PhoneVerificationCodeClock(Instant.parse("2026-09-16T00:00:00Z"))
        codes = JdbcPhoneVerificationCodeStore(jdbc, clock)
    }

    @Test
    @DisplayName("일치하는 코드로 회수하면 그 활성 코드가 무효화된다")
    fun `일치하는 코드 회수는 활성 코드를 무효화한다`() {
        val userId = newUser()
        val code = codes.issue(userId, TTL, COOLDOWN)

        codes.revoke(userId, code)

        assertThat(codes.attempt(userId, code, MAX_ATTEMPTS))
            .withFailMessage("회수된 코드가 여전히 통과한다")
            .isFalse()
    }

    @Test
    @DisplayName("오래된(stale) 코드로 회수해도 그 뒤에 재발급된 활성 코드는 살아남는다")
    fun `stale 코드 회수는 최신 활성 코드를 건드리지 않는다`() {
        val userId = newUser()
        val staleCode = codes.issue(userId, TTL, COOLDOWN)
        clock.advance(COOLDOWN.plusSeconds(1))
        val activeCode = codes.issue(userId, TTL, COOLDOWN)

        codes.revoke(userId, staleCode)

        assertThat(codes.attempt(userId, activeCode, MAX_ATTEMPTS))
            .withFailMessage("stale 코드 회수가 재발급된 최신 활성 코드까지 무효화했다 — revoke 가 사용자 단위로 동작한다")
            .isTrue()
    }

    @Test
    @DisplayName("발급하지 않은 사용자에게 회수를 불러도 아무 일도 일어나지 않는다")
    fun `발급 없이 회수해도 예외가 없다`() {
        val userId = newUser()

        codes.revoke(userId, "000000")
    }

    private fun newUser(): UUID =
        users
            .create("phone-code-${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_HASH))
            .id

    private companion object {
        val TTL: Duration = Duration.ofMinutes(5)
        val COOLDOWN: Duration = Duration.ofSeconds(60)
        const val MAX_ATTEMPTS = 5

        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$dGVzdC1oYXNo"
    }
}

/** 만료·쿨다운 경계를 재기 위한 시계 — [JdbcVerificationCodeStoreTest] 의 `VerificationCodeClock` 과 같은 필요다. */
private class PhoneVerificationCodeClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

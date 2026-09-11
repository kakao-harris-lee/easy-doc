package kr.easydoc.infrastructure.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgeObserver
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgePolicy
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgeResult
import kr.easydoc.application.auth.LoggingExpiredAuthArtifactPurgeObserver
import kr.easydoc.application.auth.PurgeExpiredAuthArtifacts
import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * 만료 인증 아티팩트(`email_verification_codes`·`password_reset_codes`·`oauth_states`)
 * 파기 — 실제 PostgreSQL 에서 `created_at` 기준 경계 판정, `oauth_states`의 `user_id`
 * NULL 행 파기, 배치 반복, 재발송 쿨다운 비회귀를 잰다(`docs/plans/2026-09-10-personal-data-inventory.md`
 * §2.2). `JdbcSignupGrantRecordPurgeTest`와 비슷한 뼈대다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcExpiredAuthArtifactPurgeTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("expired_auth_artifact_purge")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    /**
     * 재발송 쿨다운 회귀 방지 — 가장 중요한 테스트다. 코드를 발급받아 **소비한 직후**
     * 파기를 돌려도(보존기간 안이라 아직 지워지지 않는다) 그 행이 남아 있어 재발송이
     * 여전히 쿨다운으로 거절되는지 확인한다. `consumed_at` 기준으로 지우는 잘못된
     * 구현이었다면 이 테스트가 실패한다 — 소비 직후 그 행이 사라져 쿨다운이 무력화된다.
     */
    @Test
    @DisplayName("쿨다운 회귀 방지 — 소비 직후 파기를 돌려도 재발송은 여전히 쿨다운으로 거절된다")
    fun `소비 직후 파기해도 쿨다운은 살아있다`() {
        jdbc.sql("DELETE FROM users").update()
        val userId = insertUser()
        val clock = CodeStoreClock(Instant.parse("2026-09-10T00:00:00Z"))
        val codes = JdbcVerificationCodeStore(jdbc, clock)
        val cooldown = Duration.ofSeconds(60)

        val code = codes.issue(userId, Duration.ofMinutes(10), cooldown)
        assertThat(codes.attempt(userId, code, 5)).isTrue()

        // 소비 직후, 보존기간(24시간)보다 훨씬 이른 시각 — 파기가 이 행을 건드리면 안 된다.
        clock.advance(Duration.ofSeconds(1))
        val purge =
            purgeUseCase(
                retentionHours = 24,
                now = clock.instant(),
                observer = LoggingExpiredAuthArtifactPurgeObserver(),
            )
        val result = purge.run()
        assertThat(result.emailVerificationCodesDeleted)
            .describedAs("소비된 지 1초 지난 행은 24시간 보존기간 안이라 지워지면 안 된다")
            .isZero()

        // 쿨다운(60초)이 아직 안 지났다 — 재발급 시도는 여전히 거절돼야 한다.
        assertThatThrownBy { codes.issue(userId, Duration.ofMinutes(10), cooldown) }
            .isInstanceOf(RateLimitedException::class.java)
    }

    @Test
    @DisplayName("보존기간보다 오래된 행만 세 표에서 사라지고 최근 행은 남는다 — oauth_states 의 NULL user_id 오래된 행도 지워진다")
    fun `오래된 행만 지우고 최근 행과 NULL user_id 오래된 행도 규칙대로 처리한다`() {
        jdbc.sql("DELETE FROM users").update()
        jdbc.sql("DELETE FROM oauth_states").update()
        val userId = insertUser()

        val oldVerification = insertEmailVerificationCode(userId, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val recentVerification = insertEmailVerificationCode(userId, createdAt = FIXED_NOW.minus(ONE_HOUR))
        val oldReset = insertPasswordResetCode(userId, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val recentReset = insertPasswordResetCode(userId, createdAt = FIXED_NOW.minus(ONE_HOUR))
        val oldOauthWithUser = insertOauthState(userId = userId, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val oldOauthNullUser = insertOauthState(userId = null, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val recentOauthNullUser = insertOauthState(userId = null, createdAt = FIXED_NOW.minus(ONE_HOUR))

        val result = purgeUseCase(retentionHours = 24, now = FIXED_NOW).run()

        assertThat(result.emailVerificationCodesDeleted).isEqualTo(1)
        assertThat(result.passwordResetCodesDeleted).isEqualTo(1)
        assertThat(result.oauthStatesDeleted).isEqualTo(2)

        assertThat(emailVerificationCodeExists(oldVerification)).isFalse()
        assertThat(emailVerificationCodeExists(recentVerification)).isTrue()
        assertThat(passwordResetCodeExists(oldReset)).isFalse()
        assertThat(passwordResetCodeExists(recentReset)).isTrue()
        assertThat(oauthStateExists(oldOauthWithUser)).isFalse()
        assertThat(oauthStateExists(oldOauthNullUser))
            .describedAs("user_id 가 NULL 인 오래된 oauth_states 행도 지워져야 한다 — 이 조각의 핵심")
            .isFalse()
        assertThat(oauthStateExists(recentOauthNullUser)).isTrue()
    }

    @Test
    @DisplayName("배치 크기보다 많은 행에서 반복 파기가 돈다")
    fun `배치보다 많은 대상을 한 번에 지운다`() {
        jdbc.sql("DELETE FROM users").update()
        jdbc.sql("DELETE FROM oauth_states").update()
        val userId = insertUser()
        val old = FIXED_NOW.minus(TWO_DAYS)
        val ids =
            (0 until 3).map { offset ->
                insertEmailVerificationCode(userId, createdAt = old.plusSeconds(offset.toLong()))
            }

        val result = purgeUseCase(retentionHours = 24, now = FIXED_NOW, batchSize = 2).run()

        assertThat(result.emailVerificationCodesDeleted).isEqualTo(3)
        ids.forEach { assertThat(emailVerificationCodeExists(it)).isFalse() }
    }

    @Test
    @DisplayName("enabled=false 면 저장소를 아예 부르지 않는다")
    fun `비활성이면 아무것도 지우지 않는다`() {
        jdbc.sql("DELETE FROM users").update()
        val userId = insertUser()
        val old = insertEmailVerificationCode(userId, createdAt = FIXED_NOW.minus(TWO_DAYS))

        val result =
            PurgeExpiredAuthArtifacts(
                store = JdbcExpiredAuthArtifactPurge(jdbc),
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
                observer = RecordingObserver,
                policy =
                    ExpiredAuthArtifactPurgePolicy(enabled = false, retention = Duration.ofHours(24), batchSize = 100),
                clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            ).run()

        assertThat(result.enabled).isFalse()
        assertThat(emailVerificationCodeExists(old))
            .describedAs("비활성 정책이면 만료된 행도 그대로 남아야 한다")
            .isTrue()
    }

    /**
     * 루트 로거를 통째로 TRACE 로 올린다 — `JdbcSignupGrantRecordPurgeTest`의 넓은 관문과
     * 같다. 이 파기는 세 표 모두 서브쿼리 하나로 서버 안에서 끝나 `code_hash`·`salt`·
     * `state`·`nonce`가 JVM으로 들어오지 않으므로 넓은 관문을 그대로 써도 된다.
     */
    @Test
    @DisplayName("파기 로그에 해시·salt·state·nonce 원문이 남지 않는다")
    fun `로그에 비밀 값이 새지 않는다`() {
        jdbc.sql("DELETE FROM users").update()
        jdbc.sql("DELETE FROM oauth_states").update()
        val userId = insertUser()
        val secretVerification = insertEmailVerificationCode(userId, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val secretReset = insertPasswordResetCode(userId, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val secretState = insertOauthState(userId = null, createdAt = FIXED_NOW.minus(TWO_DAYS))
        val secretValues =
            codeSecretValues(secretVerification) + codeSecretValues(secretReset) + oauthSecretValues(secretState)

        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.addAppender(appender)
        root.level = Level.TRACE
        try {
            purgeUseCase(
                retentionHours = 24,
                now = FIXED_NOW,
                observer = LoggingExpiredAuthArtifactPurgeObserver(),
            ).run()
        } finally {
            root.level = previousLevel
            root.detachAppender(appender)
            appender.stop()
        }

        assertThat(appender.list)
            .describedAs("강제 TRACE 캡처에 로그가 한 건도 없다 — 이 테스트가 재려는 로그 자체가 없다")
            .isNotEmpty()
        val rendered = appender.list.joinToString(System.lineSeparator()) { it.formattedMessage }
        secretValues.forEach { secret ->
            assertThat(rendered)
                .withFailMessage("강제 TRACE 로그에 비밀 값이 실렸다: %s", secret)
                .doesNotContain(secret)
        }
    }

    private fun purgeUseCase(
        retentionHours: Long,
        now: Instant,
        batchSize: Int = 100,
        observer: ExpiredAuthArtifactPurgeObserver = RecordingObserver,
    ): PurgeExpiredAuthArtifacts =
        PurgeExpiredAuthArtifacts(
            store = JdbcExpiredAuthArtifactPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = observer,
            policy =
                ExpiredAuthArtifactPurgePolicy(
                    enabled = true,
                    retention = Duration.ofHours(retentionHours),
                    batchSize = batchSize,
                ),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun insertUser(): UUID {
        val userId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO users (id, email, password_hash, created_at, email_verified_at)
            VALUES ('$userId', '${UUID.randomUUID()}@example.test', 'argon2id${'$'}dummy', now(), now());
            """.trimIndent(),
        )
        return userId
    }

    private fun insertEmailVerificationCode(
        userId: UUID,
        createdAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO email_verification_codes (id, user_id, code_hash, salt, expires_at, created_at)
            VALUES ('$id', '$userId', 'hash-$id', 'salt-$id',
                    '${java.sql.Timestamp.from(createdAt.plus(Duration.ofMinutes(10)))}',
                    '${java.sql.Timestamp.from(createdAt)}');
            """.trimIndent(),
        )
        return id
    }

    private fun insertPasswordResetCode(
        userId: UUID,
        createdAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO password_reset_codes (id, user_id, code_hash, salt, expires_at, created_at)
            VALUES ('$id', '$userId', 'hash-$id', 'salt-$id',
                    '${java.sql.Timestamp.from(createdAt.plus(Duration.ofMinutes(10)))}',
                    '${java.sql.Timestamp.from(createdAt)}');
            """.trimIndent(),
        )
        return id
    }

    private fun insertOauthState(
        userId: UUID?,
        createdAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        val userIdSql = userId?.let { "'$it'" } ?: "NULL"
        database.execute(
            """
            INSERT INTO oauth_states (id, provider, state, nonce, redirect_uri, expires_at, user_id, created_at)
            VALUES ('$id', 'google', 'state-$id', 'nonce-$id', 'http://localhost/callback',
                    '${java.sql.Timestamp.from(createdAt.plus(Duration.ofMinutes(10)))}', $userIdSql,
                    '${java.sql.Timestamp.from(createdAt)}');
            """.trimIndent(),
        )
        return id
    }

    private fun oauthSecretValues(id: UUID): List<String> = listOf("state-$id", "nonce-$id")

    /** 두 코드 표의 공통 삽입 형태([insertEmailVerificationCode]·[insertPasswordResetCode])가 심는 `code_hash`·`salt` 값. */
    private fun codeSecretValues(id: UUID): List<String> = listOf("hash-$id", "salt-$id")

    private fun emailVerificationCodeExists(id: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM email_verification_codes WHERE id = '$id'") == 1

    private fun passwordResetCodeExists(id: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM password_reset_codes WHERE id = '$id'") == 1

    private fun oauthStateExists(id: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM oauth_states WHERE id = '$id'") == 1

    private object RecordingObserver : ExpiredAuthArtifactPurgeObserver {
        override fun record(result: ExpiredAuthArtifactPurgeResult) = Unit
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
        val ONE_HOUR: Duration = Duration.ofHours(1)
        val TWO_DAYS: Duration = Duration.ofDays(2)
    }
}

/** 만료 경계를 재기 위한 시계 — `JdbcOAuthStateStoreTest.CodeStoreClock`과 같은 필요다. */
private class CodeStoreClock(private var instant: Instant) : Clock() {
    fun advance(duration: Duration) {
        instant += duration
    }

    override fun instant(): Instant = instant

    override fun withZone(zone: ZoneId?): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

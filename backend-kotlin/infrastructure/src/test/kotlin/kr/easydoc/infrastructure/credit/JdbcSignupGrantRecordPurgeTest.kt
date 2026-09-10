package kr.easydoc.infrastructure.credit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.credit.LoggingSignupGrantRecordPurgeObserver
import kr.easydoc.application.credit.PurgeSignupGrantRecords
import kr.easydoc.application.credit.SignupGrantRecordPurgeObserver
import kr.easydoc.application.credit.SignupGrantRecordPurgePolicy
import kr.easydoc.application.credit.SignupGrantRecordPurgeResult
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * 가입 크레딧 원장(`signup_grant_records`, V20) 파기 — 실제 PostgreSQL 에서
 * `granted_at` 기준 경계 판정과 배치 반복을 잰다. `JdbcFeedbackCommentPurgeTest`
 * (`infrastructure.document`)와 비슷한 뼈대이지만, [JdbcSignupGrantRecordPurge] 자체는
 * 잠금 후 별도 `DELETE`가 아니라 **한 문장**(서브쿼리 `FOR UPDATE SKIP LOCKED`)으로
 * 끝난다 — 그 판단의 이유는 [JdbcSignupGrantRecordPurge] KDoc.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSignupGrantRecordPurgeTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("signup_grant_record_purge")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    @BeforeEach
    fun cleanRecords() {
        jdbc.sql("DELETE FROM signup_grant_records").update()
    }

    @Test
    @DisplayName("보유기간보다 오래된 행만 지워지고 최근 행은 남는다")
    fun `오래된 행만 지운다`() {
        val old = insertRecord(grantedAt = FIXED_NOW.minus(THREE_YEARS))
        val recent = insertRecord(grantedAt = FIXED_NOW.minus(ONE_YEAR))

        val store = JdbcSignupGrantRecordPurge(jdbc)
        val deleted = store.purge(grantedBefore = FIXED_NOW.minus(TWO_YEARS), batchSize = 100).deleted

        assertThat(deleted).isEqualTo(1)
        assertThat(exists(old)).isFalse()
        assertThat(exists(recent)).isTrue()
    }

    @Test
    @DisplayName("경계값 — grantedBefore 와 정확히 같은 시각의 행은 지우지 않는다(granted_at < grantedBefore)")
    fun `경계값은 남긴다`() {
        val cutoff = FIXED_NOW.minus(TWO_YEARS)
        val onBoundary = insertRecord(grantedAt = cutoff)
        val justBefore = insertRecord(grantedAt = cutoff.minusSeconds(1))

        val store = JdbcSignupGrantRecordPurge(jdbc)
        val deleted = store.purge(grantedBefore = cutoff, batchSize = 100).deleted

        assertThat(deleted).isEqualTo(1)
        assertThat(exists(onBoundary))
            .describedAs("경계 시각과 정확히 같은 행은 아직 보유기간을 넘기지 않았다 — 남아야 한다")
            .isTrue()
        assertThat(exists(justBefore)).isFalse()
    }

    @Test
    @DisplayName("한 스케줄이 배치를 넘겨 대상을 모두 지운다")
    fun `배치보다 많은 대상을 한 번에 지운다`() {
        val old = FIXED_NOW.minus(THREE_YEARS)
        val first = insertRecord(grantedAt = old)
        val second = insertRecord(grantedAt = old.plusSeconds(1))
        val third = insertRecord(grantedAt = old.plusSeconds(2))

        val purge = purgeUseCase(batchSize = 2)
        val result = purge.run()

        assertThat(result.deleted).isEqualTo(3)
        assertThat(exists(first)).isFalse()
        assertThat(exists(second)).isFalse()
        assertThat(exists(third)).isFalse()
    }

    /**
     * 루트 로거를 통째로 TRACE 로 올린다 — `DocumentPersonalDataLogLeakReachTest` 의
     * `CanaryProbe` 와 같은 넓은 관문이다. 처음에는 이 파기가 지울 행의 `email_hash`를
     * `DELETE ... WHERE email_hash IN (:hash0, ...)` 파라미터로 애플리케이션 메모리를
     * 거쳐 다시 밀어 넣는 두 단계 구조였고, 그래서 루트 TRACE 를 켜면 (우리 코드가 아니라)
     * Spring JDBC 드라이버 자신의 바인드 파라미터 트레이스 로그가 해시를 찍어 이 테스트가
     * 오탐으로 걸렸다. 지금은 [JdbcSignupGrantRecordPurge] 가 서브쿼리 하나로 서버 안에서
     * 끝나 해시가 JVM 으로 들어오지 않으므로 — 즉 해시를 SQL 파라미터로 보내는 경로 자체가
     * 사라졌으므로 — 넓은 관문을 그대로 써도 된다.
     */
    @Test
    @DisplayName("파기 로그에 이메일 해시 원문이 남지 않는다")
    fun `로그에 이메일 해시가 새지 않는다`() {
        val emailHash = insertRecord(grantedAt = FIXED_NOW.minus(THREE_YEARS))

        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.addAppender(appender)
        root.level = Level.TRACE
        try {
            purgeUseCase(batchSize = 100, observer = LoggingSignupGrantRecordPurgeObserver()).run()
        } finally {
            root.level = previousLevel
            root.detachAppender(appender)
            appender.stop()
        }

        assertThat(appender.list)
            .describedAs("강제 TRACE 캡처에 로그가 한 건도 없다 — 이 테스트가 재려는 로그 자체가 없다")
            .isNotEmpty()
        val rendered = appender.list.joinToString(System.lineSeparator()) { it.formattedMessage }
        assertThat(rendered)
            .withFailMessage("강제 TRACE 로그에 이메일 해시 원문이 실렸다: %s", emailHash)
            .doesNotContain(emailHash)
    }

    private fun purgeUseCase(
        batchSize: Int,
        observer: SignupGrantRecordPurgeObserver = RecordingObserver(),
    ): PurgeSignupGrantRecords =
        PurgeSignupGrantRecords(
            store = JdbcSignupGrantRecordPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = observer,
            policy = SignupGrantRecordPurgePolicy(enabled = true, ttl = Period.ofYears(2), batchSize = batchSize),
            clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        )

    private fun insertRecord(grantedAt: Instant): String {
        val hash = uniqueHash()
        jdbc
            .sql("INSERT INTO signup_grant_records (email_hash, granted_at) VALUES (:hash, :grantedAt)")
            .param("hash", hash)
            .param("grantedAt", java.sql.Timestamp.from(grantedAt))
            .update()
        return hash
    }

    private fun exists(emailHash: String): Boolean =
        jdbc
            .sql("SELECT count(*) FROM signup_grant_records WHERE email_hash = :hash")
            .param("hash", emailHash)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    /** char(64) PK 를 채우는 합성 해시 — 실제 HMAC 형식과 무관하게 자리만 채운다. */
    private fun uniqueHash(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .repeat(2)

    private class RecordingObserver : SignupGrantRecordPurgeObserver {
        override fun record(result: SignupGrantRecordPurgeResult) = Unit
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")

        // Instant 는 ChronoUnit.YEARS 같은 날짜 기반 단위를 지원하지 않는다 — 테스트 데이터
        // 배치일 뿐이므로 365일 근사로 충분하다(운영 계산은 JdbcSignupGrantRecordPurgeTest
        // 가 아니라 PurgeSignupGrantRecords.drainPurges 의 Period 산술이 맡는다).
        val ONE_YEAR: Duration = Duration.ofDays(365)
        val TWO_YEARS: Duration = ONE_YEAR.multipliedBy(2)
        val THREE_YEARS: Duration = ONE_YEAR.multipliedBy(3)
    }
}

package kr.easydoc.infrastructure.accesslog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.accesslog.LoggingPersonalDataAccessLogPurgeObserver
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgeObserver
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgePolicy
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgeResult
import kr.easydoc.application.accesslog.PurgePersonalDataAccessLogs
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
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * 접속기록(`personal_data_access_logs`, V22) 파기 — 실제 PostgreSQL에서 `accessed_at`
 * 기준 경계 판정과 배치 반복을 잰다. `JdbcSignupGrantRecordPurgeTest`(`infrastructure.credit`)
 * 와 같은 뼈대 — [JdbcPersonalDataAccessLogPurge]도 서브쿼리 한 문장(`FOR UPDATE SKIP LOCKED`)
 * 으로 끝난다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPersonalDataAccessLogPurgeTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var actorId: UUID

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("access_log_purge")
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
        jdbc.sql("DELETE FROM personal_data_access_logs").update()
        jdbc.sql("DELETE FROM users").update()
        actorId = insertUser()
    }

    @Test
    @DisplayName("보관기간보다 오래된 행만 지워지고 최근 행은 남는다")
    fun `오래된 행만 지운다`() {
        val old = insertAccessLogRow(accessedAt = FIXED_NOW.minus(TWO_YEARS))
        val recent = insertAccessLogRow(accessedAt = FIXED_NOW.minus(ONE_MONTH))

        val store = JdbcPersonalDataAccessLogPurge(jdbc)
        val result = store.purge(accessedBefore = FIXED_NOW.minus(ONE_YEAR), batchSize = 100)

        assertThat(result.deleted).isEqualTo(1)
        assertThat(accessLogRowExists(old)).isFalse()
        assertThat(accessLogRowExists(recent)).isTrue()
    }

    @Test
    @DisplayName("경계값 — accessedBefore 와 정확히 같은 시각의 행은 지우지 않는다(accessed_at < accessedBefore)")
    fun `경계값은 남긴다`() {
        val cutoff = FIXED_NOW.minus(ONE_YEAR)
        val onBoundary = insertAccessLogRow(accessedAt = cutoff)
        val justBefore = insertAccessLogRow(accessedAt = cutoff.minusSeconds(1))

        val store = JdbcPersonalDataAccessLogPurge(jdbc)
        val result = store.purge(accessedBefore = cutoff, batchSize = 100)

        assertThat(result.deleted).isEqualTo(1)
        assertThat(accessLogRowExists(onBoundary))
            .describedAs("경계 시각과 정확히 같은 행은 아직 보관기간을 넘기지 않았다 — 남아야 한다")
            .isTrue()
        assertThat(accessLogRowExists(justBefore)).isFalse()
    }

    @Test
    @DisplayName("배치를 넘는 대상도 한 스케줄 실행이 배치를 반복해 전부 지운다")
    fun `배치보다 많은 대상을 한 번에 지운다`() {
        val old = FIXED_NOW.minus(TWO_YEARS)
        val first = insertAccessLogRow(accessedAt = old)
        val second = insertAccessLogRow(accessedAt = old.plusSeconds(1))
        val third = insertAccessLogRow(accessedAt = old.plusSeconds(2))

        val purge = purgeUseCase(batchSize = 2)
        val result = purge.run()

        assertThat(result.deleted).isEqualTo(3)
        assertThat(accessLogRowExists(first)).isFalse()
        assertThat(accessLogRowExists(second)).isFalse()
        assertThat(accessLogRowExists(third)).isFalse()
    }

    /**
     * 루트 로거를 통째로 TRACE 로 올린다 — `JdbcSignupGrantRecordPurgeTest`의 누출 테스트와
     * 같은 넓은 관문이다. [JdbcPersonalDataAccessLogPurge]가 서브쿼리 하나로 서버 안에서
     * 끝나 `client_ip`가 JVM으로 들어오지 않으므로 이 관문을 그대로 써도 된다.
     */
    @Test
    @DisplayName("파기 로그에 지워진 행의 client_ip 원문이 남지 않는다")
    fun `로그에 client_ip 가 새지 않는다`() {
        val clientIp = uniqueClientIp()
        insertAccessLogRow(accessedAt = FIXED_NOW.minus(TWO_YEARS), clientIp = clientIp)

        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = root.level
        root.addAppender(appender)
        root.level = Level.TRACE
        try {
            purgeUseCase(batchSize = 100, observer = LoggingPersonalDataAccessLogPurgeObserver()).run()
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
            .withFailMessage("강제 TRACE 로그에 client_ip 원문이 실렸다: %s", clientIp)
            .doesNotContain(clientIp)
    }

    private fun purgeUseCase(
        batchSize: Int,
        observer: PersonalDataAccessLogPurgeObserver = RecordingObserver(),
    ): PurgePersonalDataAccessLogs =
        PurgePersonalDataAccessLogs(
            store = JdbcPersonalDataAccessLogPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = observer,
            policy =
                PersonalDataAccessLogPurgePolicy(enabled = true, retention = Period.ofYears(1), batchSize = batchSize),
            clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        )

    private fun insertAccessLogRow(
        accessedAt: Instant,
        clientIp: String = uniqueClientIp(),
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO personal_data_access_logs
                    (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
                VALUES (:id, :actorId, :accessedAt, :clientIp, 'listAdminWorkspaces', NULL, 'success')
                """,
            ).param("id", id)
            .param("actorId", actorId)
            .param("accessedAt", OffsetDateTime.ofInstant(accessedAt, ZoneOffset.UTC))
            .param("clientIp", clientIp)
            .update()
        return id
    }

    private fun accessLogRowExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM personal_data_access_logs WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO users (id, email, password_hash, email_verified_at) VALUES (:id, :email, :hash, now())",
            ).param("id", id)
            .param("email", "actor-$id@example.com")
            .param("hash", DUMMY_PHC)
            .update()
        return id
    }

    /** 고유한 IPv4 값 — 강제 TRACE 로그 누출 검사에서 우연한 일치를 피한다. */
    private fun uniqueClientIp(): String {
        val suffix = (1..3).map { (0..255).random() }
        return "203.0.${suffix[0]}.${suffix[1]}"
    }

    private class RecordingObserver : PersonalDataAccessLogPurgeObserver {
        override fun record(result: PersonalDataAccessLogPurgeResult) = Unit
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-17T00:00:00Z")

        // Instant 는 ChronoUnit.YEARS 같은 날짜 기반 단위를 지원하지 않는다 — 테스트 데이터
        // 배치일 뿐이므로 365일 근사로 충분하다(운영 계산은 이 테스트가 아니라
        // PurgePersonalDataAccessLogs.drainPurges 의 Period 산술이 맡는다).
        val ONE_MONTH: Duration = Duration.ofDays(30)
        val ONE_YEAR: Duration = Duration.ofDays(365)
        val TWO_YEARS: Duration = ONE_YEAR.multipliedBy(2)
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

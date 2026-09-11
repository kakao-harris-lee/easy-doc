package kr.easydoc.infrastructure.accesslog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.easydoc.application.accesslog.PersonalDataAccessLogEntry
import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `personal_data_access_logs`(V22) 삽입 — 실제 PostgreSQL. `JdbcSignupGrantRecordPurgeTest`
 * 와 같은 뼈대다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPersonalDataAccessLogWriterTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource
    private lateinit var actorId: UUID

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("personal_data_access_log_writer")
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
    @DisplayName("한 번 부르면 한 행이 그대로 실린다")
    fun `한 행을 그대로 남긴다`() {
        val writer = JdbcPersonalDataAccessLogWriter(jdbc)
        val entry =
            PersonalDataAccessLogEntry(
                actorUserId = actorId,
                accessedAt = FIXED_NOW,
                clientIp = "203.0.113.7",
                operation = "listAdminWorkspaces",
                subjectScope = "page=1&size=20",
                outcome = PersonalDataAccessOutcome.SUCCESS,
            )

        writer.insert(entry)

        val rows =
            jdbc
                .sql(
                    "SELECT actor_user_id, client_ip, operation, subject_scope, outcome FROM personal_data_access_logs",
                ).query { rs, _ ->
                    listOf(
                        rs.getObject("actor_user_id", UUID::class.java).toString(),
                        rs.getString("client_ip"),
                        rs.getString("operation"),
                        rs.getString("subject_scope"),
                        rs.getString("outcome"),
                    )
                }.list()
        assertThat(rows).containsExactly(
            listOf(actorId.toString(), "203.0.113.7", "listAdminWorkspaces", "page=1&size=20", "success"),
        )
    }

    @Test
    @DisplayName("subject_scope 가 없으면 NULL 로 남는다")
    fun `subject_scope 가 없으면 NULL`() {
        val writer = JdbcPersonalDataAccessLogWriter(jdbc)
        writer.insert(
            PersonalDataAccessLogEntry(
                actorUserId = actorId,
                accessedAt = FIXED_NOW,
                clientIp = "203.0.113.7",
                operation = "createAdminAnnouncement",
                subjectScope = null,
                outcome = PersonalDataAccessOutcome.REJECTED,
            ),
        )

        val outcomeAndScope =
            jdbc
                .sql("SELECT outcome, subject_scope FROM personal_data_access_logs")
                .query { rs, _ -> rs.getString("outcome") to rs.getObject("subject_scope") }
                .single()
        assertThat(outcomeAndScope.first).isEqualTo("rejected")
        assertThat(outcomeAndScope.second).isNull()
    }

    /**
     * `kr.easydoc` 로거만 TRACE 로 올려 **우리 코드**가 `client_ip` 원문을 로그로 남기지
     * 않는지 잰다 — `JdbcSignupGrantRecordPurgeTest`의 「로그에 이메일 해시가 새지 않는다」
     * 와는 캡처 범위가 다르다.
     *
     * **루트 로거를 올릴 수 없다.** [JdbcPersonalDataAccessLogWriter]에는 로거 자체가
     * 없다 — `client_ip`가 나타나는 자리는 INSERT의 바인드 파라미터 하나뿐이다. 루트를
     * TRACE 로 올리면 (우리 코드가 아니라) JDBC 드라이버 자신의 바인드 파라미터 트레이스
     * 로그가 그 값을 그대로 찍어 이 테스트가 오탐으로 걸린다.
     *
     * `JdbcSignupGrantRecordPurgeTest`(가입 크레딧 원장 파기, #106)는 반대로 판단했다 —
     * 거기서는 지울 이메일 해시를 애플리케이션 메모리로 꺼낼 이유 자체가 없어서(서브쿼리
     * 한 문장으로 서버 안에서 끝난다) 값이 파라미터로 나가는 경로 자체를 없앴고, 그래서
     * 루트 TRACE 를 그대로 켜도 오탐이 없었다. 여기서는 저장이 이 클래스의 목적이라
     * `client_ip`가 파라미터로 나가는 경로를 없앨 수 없다 — 그래서 캡처를 우리 패키지로
     * 좁히는 쪽을 택했다. 지킬 수 있는 불변식은 「드라이버가 무엇을 찍든 우리 코드는
     * 찍지 않는다」이다.
     *
     * **양성 대조(positive control)를 심는다.** [JdbcPersonalDataAccessLogWriter]가 스스로는
     * 아무것도 로그로 남기지 않으므로, 캡처를 `kr.easydoc`으로 좁힌 뒤 아무 로그도 없이
     * "캡처가 비어 있음 = 안전함"으로 잘못 읽힐 수 있다 — 캡처 장치 자체가 죽어 있어도
     * 똑같이 빈 결과가 나오기 때문이다. 삽입 **전** 이 테스트 자신의 로거로 마커 한 줄을
     * 찍어(`DocumentPersonalDataLogLeakReachTest`의 `POSITIVE_CONTROL_MARKER`와 같은
     * 기법) 캡처가 실제로 작동함을 먼저 확인한다.
     */
    @Test
    @DisplayName("삽입 로그에 client_ip 원문이 새지 않는다")
    fun `로그에 client_ip 가 새지 않는다`() {
        val writer = JdbcPersonalDataAccessLogWriter(jdbc)
        val clientIp = "198.51.100.99"

        val easydocLogger = LoggerFactory.getLogger("kr.easydoc") as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = easydocLogger.level
        easydocLogger.addAppender(appender)
        easydocLogger.level = Level.TRACE
        try {
            LoggerFactory.getLogger(JdbcPersonalDataAccessLogWriterTest::class.java).warn(POSITIVE_CONTROL_MARKER)
            writer.insert(
                PersonalDataAccessLogEntry(
                    actorUserId = actorId,
                    accessedAt = FIXED_NOW,
                    clientIp = clientIp,
                    operation = "readAdminUsage",
                    subjectScope = null,
                    outcome = PersonalDataAccessOutcome.SUCCESS,
                ),
            )
        } finally {
            easydocLogger.level = previousLevel
            easydocLogger.detachAppender(appender)
            appender.stop()
        }

        assertThat(appender.list)
            .describedAs("양성 대조 마커조차 잡히지 않았다 — 캡처 장치 자체가 죽어 있다")
            .isNotEmpty()
        val rendered = appender.list.joinToString(System.lineSeparator()) { it.formattedMessage }
        assertThat(rendered)
            .withFailMessage("강제 TRACE 로그에 client_ip 원문이 실렸다: %s", clientIp)
            .doesNotContain(clientIp)
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", id)
            .param("email", "actor-$id@example.com")
            .param("hash", DUMMY_PHC)
            .update()
        return id
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-11T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** 개인정보가 아닌 임의 문자열 — 캡처 장치가 실제로 작동하는지만 증명한다. */
        const val POSITIVE_CONTROL_MARKER = "personal-data-access-log-writer-test-canary"
    }
}

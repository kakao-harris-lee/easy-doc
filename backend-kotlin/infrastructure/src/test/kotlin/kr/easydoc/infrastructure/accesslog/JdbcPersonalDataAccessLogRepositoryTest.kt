package kr.easydoc.infrastructure.accesslog

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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** 점검 보고서(§3.5) 읽기 — `[from, toExclusive)` 경계 판정을 실제 PostgreSQL 로 잰다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPersonalDataAccessLogRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource
    private lateinit var actorId: UUID

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("personal_data_access_log_repository")
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
    @DisplayName("구간 안의 행만 돌려주고 상한과 정확히 같은 시각은 제외한다")
    fun `구간 경계`() {
        val inside = insertRow(accessedAt = Instant.parse("2026-08-15T00:00:00Z"))
        val onUpperBoundary = insertRow(accessedAt = Instant.parse("2026-09-01T00:00:00Z"))
        val before = insertRow(accessedAt = Instant.parse("2026-07-31T23:59:59Z"))

        val repository = JdbcPersonalDataAccessLogRepository(jdbc)
        val rows =
            repository.findBetween(
                fromInstant = Instant.parse("2026-08-01T00:00:00Z"),
                toExclusiveInstant = Instant.parse("2026-09-01T00:00:00Z"),
            )

        val ids = rows.map { it.id }
        assertThat(ids).contains(inside).doesNotContain(onUpperBoundary, before)
    }

    @Test
    @DisplayName("outcome·client_ip·operation·subject_scope 를 그대로 읽는다")
    fun `필드를 그대로 읽는다`() {
        jdbc
            .sql(
                """
                INSERT INTO personal_data_access_logs
                    (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
                VALUES (:id, :actorId, :accessedAt, :clientIp, :operation, :subjectScope, :outcome)
                """,
            ).param("id", UUID.randomUUID())
            .param("actorId", actorId)
            .param("accessedAt", OffsetDateTime.ofInstant(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC))
            .param("clientIp", "203.0.113.5")
            .param("operation", "readAdminWorkspace")
            .param("subjectScope", "workspace_id=abc")
            .param("outcome", "rejected")
            .update()

        val repository = JdbcPersonalDataAccessLogRepository(jdbc)
        val row =
            repository
                .findBetween(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))
                .single()

        assertThat(row.actorUserId).isEqualTo(actorId)
        assertThat(row.clientIp).isEqualTo("203.0.113.5")
        assertThat(row.operation).isEqualTo("readAdminWorkspace")
        assertThat(row.subjectScope).isEqualTo("workspace_id=abc")
        assertThat(row.outcome).isEqualTo(PersonalDataAccessOutcome.REJECTED)
    }

    private fun insertRow(accessedAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO personal_data_access_logs
                    (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
                VALUES (:id, :actorId, :accessedAt, '127.0.0.1', 'listAdminWorkspaces', NULL, 'success')
                """,
            ).param("id", id)
            .param("actorId", actorId)
            .param("accessedAt", OffsetDateTime.ofInstant(accessedAt, ZoneOffset.UTC))
            .update()
        return id
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
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

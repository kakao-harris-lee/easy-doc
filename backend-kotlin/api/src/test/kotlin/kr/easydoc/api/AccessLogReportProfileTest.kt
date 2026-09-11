package kr.easydoc.api

import kr.easydoc.api.accesslog.AccessLogReportRunner
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * `access-log-report` profile 의 실 배선(§3.5) — `UsageReportProfileTest`와 같은 자리.
 * 집계 자체는 `PersonalDataAccessReportServiceTest`(application)가 이미 재므로, 이
 * 테스트는 **CLI 인자를 읽어 BOM 붙은 UTF-8 CSV 를 쓰고 종료 코드를 내는가**만 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=access-log-report"],
    args = [
        "--from=2026-08-01",
        "--to=2026-08-31",
        "--out=build/test-access-log-report/report.csv",
    ],
)
class AccessLogReportProfileTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("시딩된 접속기록을 지정 기간으로 BOM 붙은 UTF-8 CSV에 쓰고 종료 코드 0이다")
    fun `지정 기간 보고서가 파일로 나온다`() {
        val runner = context.getBean(AccessLogReportRunner::class.java)

        assertThat(runner.exitCode).isZero()
        val csvBytes = Files.readAllBytes(File(OUT_PATH).toPath())
        assertThat(csvBytes.copyOfRange(0, 3))
            .describedAs("UTF-8 BOM(EF BB BF)이 없다")
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val csv = String(csvBytes, 3, csvBytes.size - 3, Charsets.UTF_8)
        assertThat(csv.lineSequence().first())
            .isEqualTo("id,actor_user_id,accessed_at,client_ip,operation,subject_scope,outcome")
        assertThat(csv).contains("listAdminWorkspaces")
        assertThat(csv).contains("rejected")
    }

    companion object {
        private const val OUT_PATH = "build/test-access-log-report/report.csv"
        private val SEED_AT: Instant = Instant.parse("2026-08-15T00:00:00Z")

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("access_log_report_profile")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            Files.deleteIfExists(File(OUT_PATH).toPath())
            migrate()
            seedAccessLogRows()
        }

        private fun migrate() {
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        private fun seedAccessLogRows() {
            val dataSource: DataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
            val jdbc = JdbcClient.create(dataSource)

            val actorId = UUID.randomUUID()
            jdbc
                .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", actorId)
                .param("email", "actor-$actorId@example.com")
                .param("hash", DUMMY_PHC)
                .update()
            insertRow(jdbc, actorId, "listAdminWorkspaces", "success")
            insertRow(jdbc, actorId, "listAdminWorkspaces", "rejected")
        }

        private fun insertRow(
            jdbc: JdbcClient,
            actorId: UUID,
            operation: String,
            outcome: String,
        ) {
            jdbc
                .sql(
                    """
                    INSERT INTO personal_data_access_logs
                        (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
                    VALUES (:id, :actorId, :accessedAt, '127.0.0.1', :operation, NULL, :outcome)
                    """,
                ).param("id", UUID.randomUUID())
                .param("actorId", actorId)
                .param("accessedAt", OffsetDateTime.ofInstant(SEED_AT, ZoneOffset.UTC))
                .param("operation", operation)
                .param("outcome", outcome)
                .update()
        }

        private const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

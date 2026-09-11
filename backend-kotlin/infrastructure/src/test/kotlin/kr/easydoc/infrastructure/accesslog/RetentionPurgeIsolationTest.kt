package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcExpiredAuthArtifactPurge
import kr.easydoc.infrastructure.auth.JdbcUnverifiedAccountPurge
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
import kr.easydoc.infrastructure.credit.JdbcSignupGrantRecordPurge
import kr.easydoc.infrastructure.document.JdbcExpiredDocumentPurge
import kr.easydoc.infrastructure.document.JdbcFeedbackCommentPurge
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
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

/**
 * 일일 보존 파기 배치(`worker.RetentionPurgeScheduler`)의 다섯 단계 **전부**가 실 저장소
 * 구현으로 `personal_data_access_logs`(V22)를 건드리지 않는지 잰다 — 수용 기준 6.
 *
 * `RetentionPurgeSchedulerTest`(worker 모듈)는 다섯 단계가 **모두 도는지**만 대역으로
 * 잰다 — `worker`는 `infrastructure`를 `runtimeOnly`로만 의존해 이 다섯
 * `Jdbc*Purge` 구현을 컴파일 시점에 볼 수 없다. 「이 표를 건드리지 않는다」는 SQL 자체의
 * 문제라 실 구현·실 DB가 있는 여기서만 잴 수 있다. `RetentionPurgeScheduler`가 그날그날
 * 이 다섯을 그대로 부르므로, 다섯을 직접(그 순서 그대로) 부른 결과가 곧 그 배치의 결과다
 * — 이 파일이 저장소를 새로 만들지 않는다.
 *
 * 각 파기를 **최대한 관대한 컷오프**(`Instant.now()`·`retentionDays=0`)로 불러 "지울 수
 * 있는 건 전부 지운다"에 가장 가까운 실행을 재현한다 — 그래도 `personal_data_access_logs`
 * 행 수는 그대로여야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionPurgeIsolationTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("retention_purge_isolation")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    @Test
    @DisplayName("다섯 파기 단계를 관대한 컷오프로 돌려도 접속기록 행 수는 그대로다")
    fun `다섯 단계 모두 접속기록을 건드리지 않는다`() {
        val actorId = insertUser()
        insertAccessLogRow(actorId)
        val before = countAccessLogRows()

        JdbcExpiredDocumentPurge(jdbc, CreditAccountService(JdbcCreditAccountRepository(jdbc), enforced = false))
            .purge(dryRun = false, limit = GENEROUS_LIMIT)
        JdbcFeedbackCommentPurge(jdbc).purge(dryRun = false, limit = GENEROUS_LIMIT, retentionDays = 0)
        JdbcUnverifiedAccountPurge(jdbc).purge(createdBefore = FAR_FUTURE, batchSize = GENEROUS_LIMIT)
        JdbcSignupGrantRecordPurge(jdbc).purge(grantedBefore = FAR_FUTURE, batchSize = GENEROUS_LIMIT)
        JdbcExpiredAuthArtifactPurge(jdbc).purge(createdBefore = FAR_FUTURE, batchSize = GENEROUS_LIMIT)

        assertThat(countAccessLogRows())
            .withFailMessage("다섯 파기 단계 중 하나가 personal_data_access_logs 행을 지웠다")
            .isEqualTo(before)
    }

    private fun countAccessLogRows(): Int =
        jdbc.sql("SELECT count(*) FROM personal_data_access_logs").query { rs, _ -> rs.getInt(1) }.single()

    private fun insertAccessLogRow(actorId: UUID) {
        jdbc
            .sql(
                """
                INSERT INTO personal_data_access_logs
                    (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
                VALUES (:id, :actorId, :accessedAt, '127.0.0.1', 'listAdminWorkspaces', NULL, 'success')
                """,
            ).param("id", UUID.randomUUID())
            .param("actorId", actorId)
            .param("accessedAt", OffsetDateTime.ofInstant(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC))
            .update()
    }

    /**
     * 이메일을 검증된 상태로 심는다 — 그렇지 않으면 [JdbcUnverifiedAccountPurge]의 관대한
     * 컷오프(`FAR_FUTURE`)가 이 사용자 행 자체를 지워 버려, "접속기록 행이 그대로인
     * 이유가 actor_user_id에 FK가 없어서인지 사용자가 여전히 있어서인지" 이 테스트의
     * 의도가 흐려진다.
     */
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

    private companion object {
        const val GENEROUS_LIMIT = 10_000

        /** 있는 대로 다 지우려는 관대한 컷오프 — 이 순간까지 만들어진 행은 전부 대상이다. */
        val FAR_FUTURE: Instant = Instant.parse("2999-01-01T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

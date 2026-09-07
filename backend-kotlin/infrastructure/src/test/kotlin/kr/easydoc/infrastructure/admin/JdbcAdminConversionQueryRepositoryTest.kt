package kr.easydoc.infrastructure.admin

import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * 관리자 최근 변환·오류 집계 — 실제 PostgreSQL 에서만 잴 수 있는 것들.
 * **소유 술어 없이** 워크스페이스를 가로지른다(관리자 전용, 의도적 설계).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdminConversionQueryRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcAdminConversionQueryRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("admin_conversion_query_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource())
        repository = JdbcAdminConversionQueryRepository(jdbc)
    }

    @Test
    @DisplayName("recentForWorkspace는 그 워크스페이스의 변환을 최신순으로 낸다 — 문서 제목·status·failure_code 포함")
    fun `워크스페이스별 최근 변환`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val done = newConversion(workspaceId, title = "완료 문서", status = "done", failureCode = null)
        val failed = newConversion(workspaceId, title = "실패 문서", status = "failed", failureCode = "LlmTimeout")

        val rows = repository.recentForWorkspace(workspaceId, limit = 20)

        assertThat(rows.map { it.id }).containsExactlyInAnyOrder(failed, done)
        val failedRow = rows.first { it.id == failed }
        assertThat(failedRow.documentTitle).isEqualTo("실패 문서")
        assertThat(failedRow.status).isEqualTo(ConversionStatus.FAILED)
        assertThat(failedRow.failureCode).isEqualTo("LlmTimeout")
    }

    @Test
    @DisplayName("recentForWorkspace는 다른 워크스페이스의 변환을 섞지 않는다")
    fun `다른 워크스페이스는 섞이지 않는다`() {
        val (_, workspaceA) = newOwnedWorkspace()
        val (_, workspaceB) = newOwnedWorkspace()
        newConversion(workspaceA, title = "A 문서", status = "done", failureCode = null)

        val rows = repository.recentForWorkspace(workspaceB, limit = 20)

        assertThat(rows).isEmpty()
    }

    @Test
    @DisplayName("failureCounts는 기간 내 failed 변환을 failure_code별로 센다")
    fun `실패 건수 집계`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val code = "Code-${UUID.randomUUID()}"
        newConversion(workspaceId, title = "실패1", status = "failed", failureCode = code)
        newConversion(workspaceId, title = "실패2", status = "failed", failureCode = code)
        newConversion(workspaceId, title = "완료", status = "done", failureCode = null)

        val counts = repository.failureCounts(Instant.EPOCH, Instant.now().plusSeconds(60))

        assertThat(counts.first { it.failureCode == code }.count).isEqualTo(2)
    }

    @Test
    @DisplayName("recentFailures는 workspace_id를 포함해 최근 실패 목록을 낸다 — 본문 없음")
    fun `최근 실패 목록`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val failed = newConversion(workspaceId, title = "실패 문서", status = "failed", failureCode = "Boom")

        val rows = repository.recentFailures(Instant.EPOCH, Instant.now().plusSeconds(60), limit = 50)

        val row = rows.first { it.id == failed }
        assertThat(row.workspaceId).isEqualTo(workspaceId)
        assertThat(row.failureCode).isEqualTo("Boom")
    }

    private fun newOwnedWorkspace(): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", ownerId)
            .param("email", "admin-conv-$ownerId@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "ws-$workspaceId")
            .update()
        return ownerId to workspaceId
    }

    private fun newConversion(
        workspaceId: UUID,
        title: String,
        status: String,
        failureCode: String?,
    ): UUID {
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id, user_id, title, source_format, source_text_encrypted, encryption_scheme, key_version,
                     char_count, workspace_id)
                SELECT :id, user_id, :title, 'text', :sourceText, 'aes256gcm-v1', 1, 4, :workspaceId
                FROM workspaces WHERE id = :workspaceId
                """.trimIndent(),
            ).param("id", documentId)
            .param("title", title)
            .param("sourceText", ByteArray(8))
            .param("workspaceId", workspaceId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version, failure_code)
                VALUES (:id, :documentId, :status, 'aes256gcm-v1', 1, :failureCode)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("documentId", documentId)
            .param("status", status)
            .param("failureCode", failureCode)
            .update()
        return conversionId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = database.jdbcUrl
            username = database.username
            password = database.password
        }
}

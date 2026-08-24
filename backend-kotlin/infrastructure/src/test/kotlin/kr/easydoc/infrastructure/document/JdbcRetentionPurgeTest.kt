package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.RetentionPurgeObserver
import kr.easydoc.application.document.RetentionPurgePolicy
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** 보존 만료 파기 — 실제 PostgreSQL 에서 연쇄 삭제와 활성 리스 충돌을 잰다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcRetentionPurgeTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var queue: JdbcConversionQueue
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("retention_purge")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        queue = JdbcConversionQueue(jdbc)
    }

    @BeforeEach
    fun cleanDocuments() {
        jdbc.sql("DELETE FROM documents").update()
    }

    @Test
    @DisplayName("만료 문서를 지우면 변환·마스킹 대응표·작업 행이 함께 사라진다")
    fun `만료 문서와 변환이 같은 경계에서 사라진다`() {
        val seeded = seedDocument()
        expire(seeded.documentId)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isEqualTo(1)
        assertThat(result.purgedConversions).isEqualTo(1)
        assertThat(result.skippedLeased).isZero()
        assertThat(documentExists(seeded.documentId)).isFalse()
        assertThat(conversionExists(seeded.conversionId)).isFalse()
        assertThat(jobExists(seeded.conversionId)).isFalse()
    }

    @Test
    @DisplayName("아직 만료되지 않은 문서는 그대로 둔다")
    fun `유효 문서는 남긴다`() {
        val seeded = seedDocument()

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isZero()
        assertThat(documentExists(seeded.documentId)).isTrue()
        assertThat(conversionExists(seeded.conversionId)).isTrue()
    }

    @Test
    @DisplayName("활성 리스가 있는 만료 문서는 건너뛴다")
    fun `활성 리스와 충돌하지 않는다`() {
        val seeded = seedDocument()
        expire(seeded.documentId)
        acquire(seeded.conversionId)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isZero()
        assertThat(result.skippedLeased).isEqualTo(1)
        assertThat(documentExists(seeded.documentId)).isTrue()
        assertThat(jobState(seeded.conversionId)).isEqualTo(JdbcConversionQueue.LEASED_STATE)
    }

    @Test
    @DisplayName("dry-run 은 건수만 남기고 행을 지우지 않는다")
    fun `dry-run 은 지우지 않는다`() {
        val seeded = seedDocument()
        expire(seeded.documentId)

        val result = purge(dryRun = true).run()

        assertThat(result.dryRun).isTrue()
        assertThat(result.purgedDocuments).isEqualTo(1)
        assertThat(result.purgedConversions).isEqualTo(1)
        assertThat(documentExists(seeded.documentId)).isTrue()
        assertThat(conversionExists(seeded.conversionId)).isTrue()
        assertThat(result.toString()).doesNotContain("본문")
    }

    @Test
    @DisplayName("한 스케줄이 배치를 넘겨 만료된 문서를 모두 지운다")
    fun `배치보다 많은 만료 문서를 한 번에 비운다`() {
        val first = seedDocument()
        val second = seedDocument()
        val third = seedDocument()
        expire(first.documentId)
        expire(second.documentId)
        expire(third.documentId)

        val result = purge(dryRun = false, batchSize = 2).run()

        assertThat(result.purgedDocuments).isEqualTo(3)
        assertThat(documentExists(first.documentId)).isFalse()
        assertThat(documentExists(second.documentId)).isFalse()
        assertThat(documentExists(third.documentId)).isFalse()
    }

    private fun purge(
        dryRun: Boolean,
        batchSize: Int = BATCH,
    ): PurgeExpiredDocuments =
        PurgeExpiredDocuments(
            store = JdbcExpiredDocumentPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = NoopObserver,
            policy = RetentionPurgePolicy(enabled = true, dryRun = dryRun, batchSize = batchSize),
        )

    private fun seedDocument(): Seeded {
        val owner = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id
        val workspace = workspaces.create(owner, "공간").id
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, '제목', :format, :bytes, 4, :scheme, 1)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", owner)
            .param("workspace", workspace)
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(1, 2, 3, 4))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        conversions.insertPending(conversionId, documentId, EncryptionScheme.AES_256_GCM_V1, 1)
        queue.enqueue(conversionId)
        return Seeded(documentId, conversionId)
    }

    private fun expire(documentId: UUID) {
        jdbc
            .sql("UPDATE documents SET retention_expires_at = now() - INTERVAL '1 second' WHERE id = :id")
            .param("id", documentId)
            .update()
    }

    private fun acquire(conversionId: UUID) {
        val acquired =
            TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                queue.acquire(WORKER, Duration.ofMinutes(LEASE_MINUTES), MAX_ATTEMPTS)
            }
        check(acquired is ConversionAcquire.Held && acquired.lease.conversionId == conversionId) {
            "리스를 집지 못했다: $acquired"
        }
    }

    private fun documentExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM documents WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    private fun conversionExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM conversions WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    private fun jobExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    private fun jobState(id: UUID): String =
        jdbc
            .sql("SELECT state FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getString("state") }
            .single()

    private class Seeded(
        val documentId: UUID,
        val conversionId: UUID,
    )

    private object NoopObserver : RetentionPurgeObserver {
        override fun record(result: RetentionPurgeResult) = Unit
    }

    private companion object {
        const val WORKER: String = "worker-a"
        const val MAX_ATTEMPTS: Int = 3
        const val BATCH: Int = 100
        const val LEASE_MINUTES: Long = 2
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

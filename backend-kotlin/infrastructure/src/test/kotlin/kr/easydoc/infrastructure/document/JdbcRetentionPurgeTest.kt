package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.RetentionPurgeObserver
import kr.easydoc.application.document.RetentionPurgePolicy
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
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
import java.math.BigDecimal
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
    private lateinit var credits: CreditAccountService
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
        credits = CreditAccountService(JdbcCreditAccountRepository(jdbc), enforced = false)
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
    @DisplayName("만료 문서가 끝나지 않은 크레딧 예약을 쥐고 있으면 파기 전에 해제한다 — 리뷰 HIGH-1")
    fun `파기는 끝나지 않은 예약을 해제한다`() {
        val seeded = seedDocument(creditsReserved = BigDecimal("0.1"))
        expire(seeded.documentId)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isEqualTo(1)
        val row = checkNotNull(creditRow(seeded.workspaceId)) { "크레딧 계정 행이 없다" }
        assertThat(row.balance).isEqualByComparingTo("0")
        assertThat(row.reserved)
            .withFailMessage("파기가 예약을 해제하지 않아 크레딧이 영원히 묶였다")
            .isEqualByComparingTo("0")
        val release =
            jdbc
                .sql(
                    "SELECT reserved_delta FROM credit_transactions " +
                        "WHERE document_id = :documentId AND kind = 'release'",
                ).param("documentId", seeded.documentId)
                .query { rs, _ -> rs.getBigDecimal(1) }
                .single()
        assertThat(release).isEqualByComparingTo("-0.1")
    }

    @Test
    @DisplayName("만료 파기가 V28~V34 파생 행(검수·행동 안내·표 구조·이력·그림 배치)을 함께 지운다")
    fun `만료 파기가 파생 행을 남기지 않는다`() {
        val seeded = seedDocument()
        val jobId = seedDerivedRows(seeded)
        DERIVED_TABLES.forEach { (table, column) ->
            assertThat(countIn(table, column, if (column == DOCUMENT_ID) seeded.documentId else seeded.conversionId))
                .withFailMessage("%s 에 파생 행을 심지 못했다 — 이 테스트가 아무것도 재지 못한다", table)
                .isEqualTo(1)
        }
        expire(seeded.documentId)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isEqualTo(1)
        DERIVED_TABLES.forEach { (table, column) ->
            assertThat(countIn(table, column, if (column == DOCUMENT_ID) seeded.documentId else seeded.conversionId))
                .withFailMessage("%s 의 파생 행이 문서와 함께 사라지지 않았다 — 파기 범위가 새고 있다", table)
                .isZero()
        }
        // 작업 감사행에는 일부러 FK 가 없다(V29 주석) — 청구 근거로 남는다.
        assertThat(countIn("action_guide_jobs", "id", jobId))
            .withFailMessage("작업 감사행이 사라졌다 — 문서가 지워져도 정산 근거는 남아야 한다")
            .isEqualTo(1)
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
            store = JdbcExpiredDocumentPurge(jdbc, credits),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = NoopObserver,
            policy = RetentionPurgePolicy(enabled = true, dryRun = dryRun, batchSize = batchSize),
        )

    private fun seedDocument(creditsReserved: BigDecimal = BigDecimal.ZERO): Seeded {
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
        conversions.insertPending(
            conversionId,
            documentId,
            EncryptionScheme.AES_256_GCM_V1,
            1,
            creditsReserved,
        )
        queue.enqueue(conversionId)
        if (creditsReserved.signum() > 0) {
            // 실제 등록 흐름과 같은 순서 — 계정을 만들고 예약한다(리뷰 HIGH-1 재현 준비).
            credits.ensureAccount(workspace)
            credits.reserve(owner, workspace, documentId, Credits(creditsReserved))
        }
        return Seeded(documentId, conversionId, owner, workspace)
    }

    /**
     * V28~V34 가 문서·변환에 매단 파생 행을 한 벌 심는다. 반환값은 행동 안내 작업 id —
     * 그 표만 FK 가 없어(V29 주석: 청구 근거 보존) 파기 뒤에도 남는지 따로 본다.
     */
    private fun seedDerivedRows(seeded: Seeded): UUID {
        val jobId = UUID.randomUUID()
        seedReviewSupportRow(seeded.conversionId)
        seedTableStructureRow(seeded.documentId)
        seedReviewHistoryRows(seeded)
        seedIllustrationPlacementRow(seeded.conversionId)
        insertActionGuideJob(seeded, jobId, status = "succeeded", settlement = "consumed")
        seedActionGuideContentRows(seeded.conversionId, jobId)
        return jobId
    }

    /** 이름 있는 파라미터를 한 번에 묶는다 — 파생 행 SQL 이 전부 같은 모양이라 반복을 줄인다. */
    private fun insertRow(
        sql: String,
        params: Map<String, Any>,
    ) {
        var spec = jdbc.sql(sql.trimIndent())
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        spec.update()
    }

    private fun seedReviewSupportRow(conversionId: UUID) =
        insertRow(
            """
            INSERT INTO review_assessments
                (id, conversion_id, content_revision, analyzer_version,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversion, 1, 'fact-preservation-v1', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversion" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun seedTableStructureRow(documentId: UUID) =
        insertRow(
            """
            INSERT INTO document_table_structures
                (document_id, payload_encrypted, encryption_scheme, key_version)
            VALUES (:document, :bytes, :scheme, 1)
            """,
            mapOf(
                "document" to documentId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun seedReviewHistoryRows(seeded: Seeded) {
        val snapshotId = UUID.randomUUID()
        insertRow(
            """
            INSERT INTO review_snapshots
                (id, conversion_id, content_revision, kind, payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversion, 1, 'review_assessment', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to snapshotId,
                "conversion" to seeded.conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
        insertRow(
            """
            INSERT INTO review_events
                (id, conversion_id, event_type, actor_user_id, created_at, content_revision, snapshot_id)
            VALUES (:id, :conversion, 'item_confirmed', :actor, now(), 1, :snapshot)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversion" to seeded.conversionId,
                "actor" to seeded.ownerId,
                "snapshot" to snapshotId,
            ),
        )
    }

    private fun seedIllustrationPlacementRow(conversionId: UUID) =
        insertRow(
            """
            INSERT INTO illustration_placements
                (id, conversion_id, content_revision, payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversion, 1, :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversion" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun seedActionGuideContentRows(
        conversionId: UUID,
        jobId: UUID,
    ) {
        insertRow(
            """
            INSERT INTO action_guide_candidates
                (id, job_id, conversion_id, based_on_content_revision,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :job, :conversion, 1, :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "job" to jobId,
                "conversion" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
        insertRow(
            """
            INSERT INTO action_guides
                (id, conversion_id, based_on_content_revision, guide_revision, status,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversion, 1, 1, 'draft', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversion" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
    }

    private fun insertActionGuideJob(
        seeded: Seeded,
        jobId: UUID,
        status: String,
        settlement: String,
        reservedCredits: BigDecimal = BigDecimal.ONE,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO action_guide_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, reserved_credits, settlement)
                VALUES (:id, :request, :owner, :workspace, :document, :conversion,
                        1, 1, :fingerprint, :status, :credits, :settlement)
                """.trimIndent(),
            ).param("id", jobId)
            .param("request", UUID.randomUUID())
            .param("owner", seeded.ownerId)
            .param("workspace", seeded.workspaceId)
            .param("document", seeded.documentId)
            .param("conversion", seeded.conversionId)
            .param("fingerprint", FINGERPRINT)
            .param("status", status)
            .param("credits", reservedCredits)
            .param("settlement", settlement)
            .update()
    }

    private fun countIn(
        table: String,
        column: String,
        value: UUID,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM $table WHERE $column = :value")
            .param("value", value)
            .query { rs, _ -> rs.getInt(1) }
            .single()

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

    private fun creditRow(workspaceId: UUID): CreditRow? =
        jdbc
            .sql("SELECT balance, reserved FROM workspace_credit_accounts WHERE workspace_id = :id")
            .param("id", workspaceId)
            .query { rs, _ -> CreditRow(rs.getBigDecimal("balance"), rs.getBigDecimal("reserved")) }
            .optional()
            .orElse(null)

    private class CreditRow(
        val balance: BigDecimal,
        val reserved: BigDecimal,
    )

    private fun jobState(id: UUID): String =
        jdbc
            .sql("SELECT state FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getString("state") }
            .single()

    private class Seeded(
        val documentId: UUID,
        val conversionId: UUID,
        val ownerId: UUID = UUID.randomUUID(),
        val workspaceId: UUID = UUID.randomUUID(),
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

        const val DOCUMENT_ID = "document_id"
        const val CONVERSION_ID = "conversion_id"

        /** `ck_action_guide_jobs_fingerprint_length` 이 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val PAYLOAD: ByteArray = byteArrayOf(1, 2, 3, 4)

        /**
         * V28~V34 가 문서·변환에 매단 표와 그 결속 열. 전부 `ON DELETE CASCADE` 라 문서
         * 파기 한 번으로 사라져야 한다 — FK 를 일부러 두지 않은 `action_guide_jobs` 는
         * 여기 없다.
         */
        val DERIVED_TABLES: List<Pair<String, String>> =
            listOf(
                "review_assessments" to CONVERSION_ID,
                "document_table_structures" to DOCUMENT_ID,
                "review_snapshots" to CONVERSION_ID,
                "review_events" to CONVERSION_ID,
                "illustration_placements" to CONVERSION_ID,
                "action_guide_candidates" to CONVERSION_ID,
                "action_guides" to CONVERSION_ID,
            )
    }
}

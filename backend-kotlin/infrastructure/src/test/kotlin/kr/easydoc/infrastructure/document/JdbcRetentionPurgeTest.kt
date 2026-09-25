package kr.easydoc.infrastructure.document

import kr.easydoc.application.actionguide.ActionGuideCreditReservation
import kr.easydoc.application.actionguide.ActionGuideJobAcquire
import kr.easydoc.application.actionguide.ActionGuideJobInsert
import kr.easydoc.application.actionguide.ActionGuideJobLease
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.RetentionPurgeObserver
import kr.easydoc.application.document.RetentionPurgePolicy
import kr.easydoc.application.document.RetentionPurgeResult
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.DerivedRows
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideCreditPort
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideJobRepository
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private lateinit var actionGuideJobs: JdbcActionGuideJobRepository
    private lateinit var actionGuideCredits: JdbcActionGuideCreditPort
    private lateinit var tx: TransactionTemplate
    private lateinit var database: DatabaseHandle
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("retention_purge")
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
        actionGuideJobs = JdbcActionGuideJobRepository(jdbc)
        actionGuideCredits = JdbcActionGuideCreditPort(jdbc, enforced = false)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
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
        DerivedRows.requireNonEmptyCensus()
        val jobId =
            DerivedRows.seed(dataSource, seeded.ownerId, seeded.workspaceId, seeded.documentId, seeded.conversionId)
        assertThat(derivedCounts(seeded))
            .withFailMessage("파생 행을 심지 못한 표가 있다 — 이 테스트가 아무것도 재지 못한다")
            .isEqualTo(everyDerivedTable(1))
        expire(seeded.documentId)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedDocuments).isEqualTo(1)
        assertThat(derivedCounts(seeded))
            .withFailMessage("파생 행이 문서와 함께 사라지지 않았다 — 파기 범위가 새고 있다")
            .isEqualTo(everyDerivedTable(0))
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

    /**
     * **파기와 작업 정산이 잠금을 같은 순서로 잡는가** — 둘이 같은 이용량 계정에서 만나는 자리다.
     *
     * 문서 삭제 trigger(V29 `settle_action_guide_jobs_for_document`)와 worker 정산
     * (`ProcessActionGuideJob.settle`)은 둘 다 **작업 행을 먼저 잠그고** 그다음 계정을 갱신한다.
     * 파기가 끝나지 않은 변환 예약을 삭제 **앞**에서 해제하면 계정을 먼저 잡게 되어 순서가
     * 엇갈린다 — 파기는 계정을 든 채 trigger 의 작업 행 잠금을 기다리고, 정산 중인 worker 는
     * 그 계정을 기다린다.
     *
     * 교착에서 PostgreSQL 이 정산을 죽이면 이미 돈을 쓴 호출의 결과가 사라지고, 파기를 죽이면
     * 그 배치가 통째로 되돌아가 보존 기한을 넘긴 문서가 남는다.
     */
    @Test
    @DisplayName("파기는 작업 정산과 같은 순서로 잠금을 잡는다 — 정산 중인 행동 안내 작업과 교착하지 않는다")
    fun `파기와 행동 안내 정산이 교착하지 않는다`() {
        val reserved = seedDocument(creditsReserved = PENDING_CREDITS)
        val guided = seedDocument(ownerId = reserved.ownerId, workspaceId = reserved.workspaceId)
        val lease = startActionGuideJob(guided)
        expire(reserved.documentId)
        expire(guided.documentId)

        val pool = Executors.newSingleThreadExecutor()
        try {
            var pending: Future<RetentionPurgeResult>? = null
            tx.executeWithoutResult {
                val held = checkNotNull(actionGuideJobs.lockIfHeld(lease)) { "정산이 작업 행을 잠그지 못했다" }
                check(actionGuideJobs.markSucceeded(lease, NOW))
                val purging = pool.submit<RetentionPurgeResult> { purge(dryRun = false).run() }
                // 파기는 trigger 의 작업 행 잠금 앞에서 멈춰 서 있어야 한다 — 끝나 있으면 이 시험이
                // 아무것도 재지 못한다. 옛 순서라면 파기가 계정을 든 채 여기 멈추고, 아래 소비가
                // 그 계정을 기다려 교착한다.
                awaitBlockedOnLock(purging)
                actionGuideCredits.consume(held)
                pending = purging
            }

            val result = checkNotNull(pending).get(HANDOFF_SECONDS, TimeUnit.SECONDS)

            assertThat(result.purgedDocuments).isEqualTo(2)
            assertThat(documentExists(reserved.documentId)).isFalse()
            assertThat(documentExists(guided.documentId)).isFalse()
            // 정산이 살아남아 돈을 쓴 호출이 소비로 확정됐고, 파기는 남은 변환 예약만 되돌렸다.
            assertThat(actionGuideJobColumn(lease.jobId, "status")).isEqualTo(ActionGuideJobStatus.SUCCEEDED.wireName)
            assertThat(actionGuideJobColumn(lease.jobId, "settlement")).isEqualTo("consumed")
            val row = checkNotNull(creditRow(reserved.workspaceId)) { "크레딧 계정 행이 없다" }
            assertThat(row.reserved)
                .withFailMessage("파기나 정산 중 한쪽이 교착으로 죽어 예약이 남았다")
                .isEqualByComparingTo("0")
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * **한 문장으로 여러 문서를 지울 때도 작업 행이 계정보다 먼저 잠기는가.**
     *
     * 삭제 trigger 는 **행마다** 돈다 — 문서 A 의 작업 행을 잠그고 계정을 갱신한 뒤에야 문서 B 의
     * 작업 행을 잠그러 간다. 그래서 B 의 작업을 정산 중인 worker 가 그 행을 쥔 채 계정을 기다리면,
     * 파기는 A 때문에 이미 계정을 든 채 B 를 기다려 교착한다.
     *
     * 계정 행을 미리 잡아 두고 파기를 들여보내, **파기가 계정 앞에서 무엇을 이미 잠갔는지** 본다 —
     * 배치의 두 작업 행이 모두 잠겨 있어야 한다. 행마다 잠그는 옛 동작에서는 아직 처리되지 않은
     * 문서의 작업 행이 비어 있어 여기서 걸린다. 어느 문서가 먼저 처리되든 결과가 같다.
     *
     * 한 워크스페이스에 **두 소유자**가 필요하다 — 계정당 활성 작업은 소유자마다 하나
     * (`uq_action_guide_jobs_active_owner`)라서, 같은 소유자로는 활성 작업 둘을 만들 수 없다.
     * 오늘 행동 안내만으로는 이 상태가 제품 경로로 만들어지지 않는다(이용량 포트가 계정 소유자가
     * 아닌 작업의 예약을 막는다 — [reserveGuideCredits] 참고). 그림 제안 작업(R7)이 같은 trigger 로
     * 합쳐지면 **한 소유자만으로도** 같은 모양이 되므로, 그때를 기다리지 않고 순서를 지금 못 박는다.
     */
    @Test
    @DisplayName("한 배치로 여러 문서를 지울 때 활성 작업 행을 모두 이용량 계정보다 먼저 잠근다")
    fun `배치 삭제가 작업 행을 계정보다 먼저 잠근다`() {
        val first = seedDocument()
        val secondOwner = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id
        val second = seedDocument(ownerId = secondOwner, workspaceId = first.workspaceId)
        credits.ensureAccount(first.workspaceId)
        val firstLease = startActionGuideJob(first)
        val secondLease = startActionGuideJob(second)
        expire(first.documentId)
        expire(second.documentId)

        val pool = Executors.newSingleThreadExecutor()
        try {
            var pending: Future<RetentionPurgeResult>? = null
            tx.executeWithoutResult {
                lockCreditAccount(first.workspaceId)
                val purging = pool.submit<RetentionPurgeResult> { purge(dryRun = false).run() }
                awaitBlockedOnLock(purging)

                assertThat(listOf(firstLease.jobId, secondLease.jobId).filter { isRowLocked(it) })
                    .withFailMessage("파기가 계정에 닿을 때까지 잠그지 않은 활성 작업 행이 있다 — 정산과 교착한다")
                    .containsExactlyInAnyOrder(firstLease.jobId, secondLease.jobId)
                pending = purging
            }

            val result = checkNotNull(pending).get(HANDOFF_SECONDS, TimeUnit.SECONDS)

            assertThat(result.purgedDocuments).isEqualTo(2)
            assertThat(actionGuideJobColumn(firstLease.jobId, "settlement")).isEqualTo("released")
            assertThat(actionGuideJobColumn(secondLease.jobId, "settlement")).isEqualTo("released")
            val row = checkNotNull(creditRow(first.workspaceId)) { "크레딧 계정 행이 없다" }
            assertThat(row.reserved).isEqualByComparingTo("0")
        } finally {
            pool.shutdownNow()
        }
    }

    /** 계정 행을 이 트랜잭션에 묶어 둔다 — 파기가 계정에 닿는 순간을 붙잡는 장벽이다. */
    private fun lockCreditAccount(workspaceId: UUID) {
        jdbc
            .sql("SELECT workspace_id FROM workspace_credit_accounts WHERE workspace_id = :id FOR UPDATE")
            .param("id", workspaceId)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .single()
    }

    /**
     * [pending] 이 **실제로 잠금을 기다리는 중**임을 확인한다. 「아직 안 끝났다」만으로는 느린 CI 와
     * 구분되지 않아, 시험이 재는 것 없이 초록이 될 수 있다.
     */
    private fun awaitBlockedOnLock(pending: Future<*>) {
        assertThatThrownBy { pending.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
            .isInstanceOf(TimeoutException::class.java)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDOFF_SECONDS)
        while (System.nanoTime() < deadline) {
            if (backendsWaitingOnLock() > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("잠금을 기다리는 백엔드를 관측하지 못했다 — 멈춘 이유가 잠금이 아니다")
    }

    private fun backendsWaitingOnLock(): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                """.trimIndent(),
            ).query { rs, _ -> rs.getInt(1) }
            .single()

    /**
     * 다른 연결에서 `FOR UPDATE NOWAIT` 로 찔러 본다 — 잠겨 있으면 `55P03` 으로 즉시 끊긴다.
     * 실패한 문장은 그 트랜잭션을 통째로 중단시키므로 **반드시 별도 연결**이어야 한다.
     */
    private fun isRowLocked(jobId: UUID): Boolean =
        database.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(PROBE_LOCK_SQL).use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeQuery()
                }
                false
            } catch (failure: SQLException) {
                check(failure.sqlState == LOCK_NOT_AVAILABLE) { "잠금 확인이 아닌 오류다: ${failure.sqlState}" }
                true
            } finally {
                connection.rollback()
            }
        }

    /** 문서 [seeded] 에 provider 호출을 시작한 행동 안내 작업 하나를 남기고 그 리스를 준다. */
    private fun startActionGuideJob(seeded: Seeded): ActionGuideJobLease {
        val jobId = UUID.randomUUID()
        tx.executeWithoutResult {
            reserveGuideCredits(seeded, jobId)
            check(actionGuideJobs.insert(storedActionGuideJob(seeded, jobId)) is ActionGuideJobInsert.Inserted)
        }
        val acquired = tx.execute { actionGuideJobs.acquire(WORKER, Duration.ofMinutes(LEASE_MINUTES), MAX_ATTEMPTS) }
        check(acquired is ActionGuideJobAcquire.Held) { "행동 안내 작업 리스를 집지 못했다: $acquired" }
        tx.executeWithoutResult {
            check(actionGuideJobs.markProviderStarted(acquired.lease, UUID.randomUUID(), NOW))
        }
        return acquired.lease
    }

    /**
     * 행동 안내 예약을 계정에 올린다. 계정 소유자의 작업이면 실제 포트를 그대로 타고, 아니면
     * `reserved` 만 올린다 — `JdbcActionGuideCreditPort.reserve` 의 소유 술어
     * (`workspaces.user_id = :ownerId`)가 계정 소유자가 아닌 작업의 예약을 막기 때문이다.
     */
    private fun reserveGuideCredits(
        seeded: Seeded,
        jobId: UUID,
    ) {
        val reservation =
            actionGuideCredits.reserve(
                seeded.ownerId,
                seeded.workspaceId,
                seeded.documentId,
                jobId,
                Credits(GUIDE_CREDITS),
            )
        if (reservation is ActionGuideCreditReservation.Reserved) return
        jdbc
            .sql("UPDATE workspace_credit_accounts SET reserved = reserved + :amount WHERE workspace_id = :id")
            .param("amount", GUIDE_CREDITS)
            .param("id", seeded.workspaceId)
            .update()
    }

    private fun storedActionGuideJob(
        seeded: Seeded,
        jobId: UUID,
    ): StoredActionGuideJob =
        StoredActionGuideJob(
            jobId = jobId,
            ownerId = seeded.ownerId,
            workspaceId = seeded.workspaceId,
            documentId = seeded.documentId,
            conversionId = seeded.conversionId,
            requestId = UUID.randomUUID(),
            expectedGuideRevision = null,
            basedOnContentRevision = 1,
            reservedCredits = GUIDE_CREDITS,
            status = ActionGuideJobStatus.QUEUED,
            failureCode = null,
            executionId = null,
            providerStartedAt = null,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun actionGuideJobColumn(
        jobId: UUID,
        column: String,
    ): String =
        jdbc
            .sql("SELECT $column FROM action_guide_jobs WHERE id = :id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun purge(
        dryRun: Boolean,
        batchSize: Int = BATCH,
    ): PurgeExpiredDocuments =
        PurgeExpiredDocuments(
            store = JdbcExpiredDocumentPurge(jdbc, credits, DocumentJobLocks(jdbc)),
            transaction = SpringTransactionRunner(tx),
            observer = NoopObserver,
            policy = RetentionPurgePolicy(enabled = true, dryRun = dryRun, batchSize = batchSize),
        )

    /** [ownerId]·[workspaceId] 를 주면 그 계정에 문서를 더 단다 — 한 계정의 여러 문서가 필요한 시험용. */
    private fun seedDocument(
        creditsReserved: BigDecimal = BigDecimal.ZERO,
        ownerId: UUID? = null,
        workspaceId: UUID? = null,
    ): Seeded {
        val owner = ownerId ?: users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id
        val workspace = workspaceId ?: workspaces.create(owner, "공간").id
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

    /** [DerivedRows.CENSUS] 의 「표 이름 → 남은 행 수」. */
    private fun derivedCounts(seeded: Seeded): Map<String, Int> =
        DerivedRows.counts(dataSource, seeded.documentId, seeded.conversionId)

    /** 모든 파생 표가 [rows] 행씩인 기대값 — 어긋난 표 이름이 실패 메시지에 그대로 나온다. */
    private fun everyDerivedTable(rows: Int): Map<String, Int> =
        DerivedRows.CENSUS.associate { (table, _) -> table to rows }

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

        val NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")

        /** 끝나지 않은 변환 1건의 예약 — 파기가 계정을 건드리게 만드는 몫. */
        val PENDING_CREDITS: BigDecimal = BigDecimal("0.1")

        /** 정산 중인 행동 안내 작업 1건의 예약. */
        val GUIDE_CREDITS: BigDecimal = BigDecimal.ONE

        /** 파기가 잠금 앞에서 실제로 멈춰 서 있는지 볼 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS: Long = 300
        const val HANDOFF_SECONDS: Long = 20
        const val POLL_MILLIS: Long = 20

        /** `FOR UPDATE NOWAIT` 가 잠긴 행에서 내는 SQLSTATE(`lock_not_available`). */
        const val LOCK_NOT_AVAILABLE: String = "55P03"
        const val PROBE_LOCK_SQL: String = "SELECT id FROM action_guide_jobs WHERE id = ? FOR UPDATE NOWAIT"
    }
}

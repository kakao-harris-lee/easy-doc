package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideJobAcquire
import kr.easydoc.application.actionguide.ActionGuideJobInsert
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** AC-R2-a/b를 실제 PostgreSQL 트랜잭션과 원장 제약으로 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcActionGuideJobFlowTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var jobs: JdbcActionGuideJobRepository
    private lateinit var credits: JdbcActionGuideCreditPort
    private lateinit var ledger: JdbcActionGuideLlmCallLedger
    private lateinit var tx: TransactionTemplate

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("action_guide_job_flow")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        jobs = JdbcActionGuideJobRepository(jdbc)
        credits = JdbcActionGuideCreditPort(jdbc, enforced = true)
        ledger = JdbcActionGuideLlmCallLedger(jdbc)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    @DisplayName("같은 request는 한 번만 예약되고 성공 정산도 한 번만 소비한다")
    fun `같은 요청은 reserve와 consume이 한 번이다`() {
        val fixture = seed()
        val job = fixture.job()

        createIdempotently(job)
        createIdempotently(job)

        val lease = (tx.execute { jobs.acquire("worker-a", Duration.ofMinutes(2)) } as ActionGuideJobAcquire.Held).lease
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markProviderStarted(lease, executionId, NOW)).isTrue()
            ledger.start(held, executionId, NOW)
        }
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(1))).isTrue()
            ledger.complete(held, executionId, completedRecord())
            credits.consume(held)
        }

        assertThat(transactionCount(job.jobId, "reserve")).isEqualTo(1)
        assertThat(transactionCount(job.jobId, "consume")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isZero()
        assertThat(accountBalance(fixture.workspaceId)).isEqualTo(9)
        assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(2))).isFalse()
    }

    @Test
    @DisplayName("provider 시작 뒤 lease가 만료되면 재호출 없이 unknown과 release로 정산한다")
    fun `시작 뒤 만료는 unknown으로 회수한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val first = (tx.execute { jobs.acquire("worker-a", Duration.ofMinutes(2)) } as ActionGuideJobAcquire.Held).lease
        assertThat(first.jobId).isEqualTo(job.jobId)
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(first)!!
            assertThat(jobs.markProviderStarted(first, executionId, NOW)).isTrue()
            ledger.start(held, executionId, NOW)
        }
        jdbc
            .sql("UPDATE action_guide_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
            .param("id", job.jobId)
            .update()

        val recovered = tx.execute { jobs.acquire("worker-b", Duration.ofMinutes(2)) }
        assertThat(recovered).isInstanceOf(ActionGuideJobAcquire.RecoverUnknown::class.java)
        val lease = (recovered as ActionGuideJobAcquire.RecoverUnknown).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markFailed(lease, ActionGuideJobFailureCode.OUTCOME_UNKNOWN, NOW.plusSeconds(2))).isTrue()
            ledger.markOutcomeUnknown(held, executionId, NOW.plusSeconds(2))
            credits.release(held)
        }

        assertThat(llmOutcome(job.jobId)).isEqualTo("outcome_unknown")
        assertThat(transactionCount(job.jobId, "reserve")).isEqualTo(1)
        assertThat(transactionCount(job.jobId, "release")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isZero()
        assertThat(accountBalance(fixture.workspaceId)).isEqualTo(10)
        assertThat(providerAttempts(job.jobId)).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 계정의 다른 문서도 활성 작업은 하나만 허용한다")
    fun `계정 active unique가 다른 문서 경합을 막는다`() {
        val first = seed()
        val firstJob = first.job()
        assertThat(tx.execute { jobs.insert(firstJob) }).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
        val second = seedDocument(first.ownerId, first.workspaceId)

        assertThat(tx.execute { jobs.insert(second.job()) }).isEqualTo(ActionGuideJobInsert.ActiveConflict)
    }

    @Test
    @DisplayName("문서 삭제는 활성 작업 예약을 해제하고 superseded로 보존한다")
    fun `문서 삭제 trigger가 예약을 정산한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(jobs.findOwned(fixture.ownerId, fixture.conversionId, job.jobId)?.status)
            .isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(transactionCount(job.jobId, "release")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isZero()
    }

    private fun createIdempotently(job: StoredActionGuideJob) {
        tx.executeWithoutResult {
            if (jobs.findByRequestId(job.ownerId, job.conversionId, job.requestId) == null) {
                assertThat(credits.reserve(job.ownerId, job.workspaceId, job.documentId, job.jobId, Credits(1)))
                    .isInstanceOf(kr.easydoc.application.actionguide.ActionGuideCreditReservation.Reserved::class.java)
                assertThat(jobs.insert(job)).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
            }
        }
    }

    private fun seed(): Fixture {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", ownerId)
            .param("email", "u-$ownerId@example.test")
            .param("password", DUMMY_PHC)
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", workspaceId)
            .param("owner", ownerId)
            .update()
        jdbc
            .sql("INSERT INTO workspace_credit_accounts (workspace_id,balance,reserved) VALUES (:id,10,0)")
            .param("id", workspaceId)
            .update()
        return seedDocument(ownerId, workspaceId)
    }

    private fun seedDocument(
        ownerId: UUID,
        workspaceId: UUID,
    ): Fixture {
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", ownerId)
            .param("workspace", workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("document", documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        return Fixture(ownerId, workspaceId, documentId, conversionId)
    }

    private fun completedRecord() =
        LlmCallRecord(
            LlmCallPurpose.ACTION_GUIDE,
            "fake",
            "fake",
            3,
            2,
            1,
            null,
            null,
            null,
            10,
            NOW.plusSeconds(1),
            LlmCallOutcome.COMPLETED,
        )

    private fun transactionCount(
        jobId: UUID,
        kind: String,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM credit_transactions WHERE action_guide_job_id=:id AND kind=:kind")
            .param("id", jobId)
            .param("kind", kind)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun accountReserved(workspaceId: UUID): Int = accountValue(workspaceId, "reserved")

    private fun accountBalance(workspaceId: UUID): Int = accountValue(workspaceId, "balance")

    private fun accountValue(
        workspaceId: UUID,
        column: String,
    ): Int =
        jdbc
            .sql("SELECT $column FROM workspace_credit_accounts WHERE workspace_id=:id")
            .param("id", workspaceId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun llmOutcome(jobId: UUID): String =
        jdbc
            .sql("SELECT outcome FROM llm_calls WHERE action_guide_job_id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun providerAttempts(jobId: UUID): Int =
        jdbc
            .sql("SELECT provider_attempts FROM action_guide_jobs WHERE id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private data class Fixture(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    ) {
        fun job(): StoredActionGuideJob =
            StoredActionGuideJob(
                UUID.randomUUID(),
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                null,
                1,
                1,
                ActionGuideJobStatus.QUEUED,
                null,
                null,
                null,
                NOW,
                NOW,
            )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-18T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

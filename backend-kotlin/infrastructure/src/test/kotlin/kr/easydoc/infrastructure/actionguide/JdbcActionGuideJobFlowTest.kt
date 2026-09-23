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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

        val lease = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
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
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("9")
        assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(2))).isFalse()
    }

    @Test
    @DisplayName("provider 시작 뒤 lease가 만료되면 재호출 없이 unknown과 release로 정산한다")
    fun `시작 뒤 만료는 unknown으로 회수한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val first = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
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

        val recovered = tx.execute { acquire("worker-b") }
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
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("10")
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
        val job = fixture.job().copy(reservedCredits = BigDecimal("0.1"))
        createIdempotently(job)

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(jobs.findOwned(fixture.ownerId, fixture.conversionId, job.jobId)?.status)
            .isEqualTo(ActionGuideJobStatus.SUPERSEDED)
        assertThat(transactionCount(job.jobId, "release")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isZero()
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("10")
        val releaseDelta =
            jdbc
                .sql(
                    "SELECT reserved_delta FROM credit_transactions " +
                        "WHERE action_guide_job_id = :id AND kind = 'release'",
                ).param("id", job.jobId)
                .query { rs, _ -> rs.getBigDecimal(1) }
                .single()
        assertThat(releaseDelta).isEqualByComparingTo("-0.1")
    }

    @Test
    @DisplayName("provider 호출을 시작한 작업이 3건이면 같은 변환의 새 접수를 상한으로 막는다")
    fun `provider 시작 3회는 네 번째 접수를 막는다`() {
        val fixture = seed()
        repeat(MAX_ATTEMPTS_PER_CONVERSION) { startAndSucceed(fixture.job()) }

        assertThat(tx.execute { jobs.insert(fixture.job()) }).isEqualTo(ActionGuideJobInsert.AttemptLimit)
    }

    @Test
    @DisplayName("provider 호출을 시작하지 못하고 끝난 작업은 시도로 세지 않는다")
    fun `미시작 종료 작업은 상한에 세지 않는다`() {
        val fixture = seed()
        repeat(MAX_ATTEMPTS_PER_CONVERSION) { supersedeWithoutStart(fixture.job()) }

        assertThat(tx.execute { jobs.insert(fixture.job()) })
            .isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
    }

    @Test
    @DisplayName("같은 변환의 동시 접수는 변환 행 잠금으로 직렬화돼 두 번째가 첫 번째의 커밋을 본다")
    fun `동시 접수는 변환 행 잠금으로 직렬화된다`() {
        // 시도 상한은 「시작된 시도 세기 → 삽입」이 원자적일 때만 지켜진다. 그 원자성은 접수
        // 트랜잭션이 먼저 잡는 lockOwnedContext 의 FOR NO KEY UPDATE OF c 가 만든다.
        val fixture = seed()
        val pool = Executors.newSingleThreadExecutor()
        try {
            var loser: Future<ActionGuideJobInsert>? = null
            tx.executeWithoutResult {
                assertThat(jobs.lockOwnedContext(fixture.ownerId, fixture.conversionId)).isNotNull()
                val pending =
                    pool.submit<ActionGuideJobInsert> {
                        checkNotNull(
                            tx.execute {
                                jobs.lockOwnedContext(fixture.ownerId, fixture.conversionId)
                                jobs.insert(fixture.job())
                            },
                        )
                    }
                // 뒤 트랜잭션은 잠금 앞에서 멈춰 서 있어야 한다 — 끝나 있으면 직렬화가 깨진 것이다.
                assertThatThrownBy { pending.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)
                assertThat(jobs.insert(fixture.job())).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
                loser = pending
            }

            assertThat(loser?.get(HANDOFF_SECONDS, TimeUnit.SECONDS)).isEqualTo(ActionGuideJobInsert.ActiveConflict)
            assertThat(jobCount(fixture.conversionId)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @DisplayName("소수 예약은 job에 저장된 양으로 성공 정산한다")
    fun `소수 예약은 저장된 양으로 소비한다`() {
        val fixture = seed()
        val job = fixture.job().copy(reservedCredits = BigDecimal("0.1"))
        createIdempotently(job)

        val lease = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(1))).isTrue()
            credits.consume(held)
        }

        assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo("0")
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("9.9")
        val consume =
            jdbc
                .sql(
                    """
                    SELECT balance_delta, reserved_delta
                    FROM credit_transactions
                    WHERE action_guide_job_id = :id AND kind = 'consume'
                    """.trimIndent(),
                ).param("id", job.jobId)
                .query { rs, _ -> rs.getBigDecimal(1) to rs.getBigDecimal(2) }
                .single()
        assertThat(consume.first).isEqualByComparingTo("-0.1")
        assertThat(consume.second).isEqualByComparingTo("-0.1")
    }

    @Test
    @DisplayName("provider를 시작하지 못한 작업이 리스 상한을 넘으면 다시 넘기지 않고 실패로 정산한다")
    fun `상한을 넘긴 미시작 작업은 dead letter로 끝난다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        // 시작 트랜잭션이 매번 깨지는 작업을 흉내 낸다 — 상한까지는 지금처럼 다시 넘어간다.
        repeat(MAX_LEASE_ATTEMPTS) {
            assertThat(tx.execute { acquire("worker-a") }).isInstanceOf(ActionGuideJobAcquire.Held::class.java)
            expireLease(job.jobId)
        }

        val acquired = tx.execute { acquire("worker-b") }

        assertThat(acquired).isInstanceOf(ActionGuideJobAcquire.DeadLettered::class.java)
        val lease = (acquired as ActionGuideJobAcquire.DeadLettered).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markFailed(lease, ActionGuideJobFailureCode.GENERATION_FAILED, NOW.plusSeconds(1)))
                .isTrue()
            credits.release(held)
        }

        val settled = jobs.findOwned(fixture.ownerId, fixture.conversionId, job.jobId)!!
        assertThat(settled.status).isEqualTo(ActionGuideJobStatus.FAILED)
        assertThat(settled.failureCode).isEqualTo(ActionGuideJobFailureCode.GENERATION_FAILED)
        // 호출을 시작한 적이 없으므로 원장도 문서당 시도 상한(D04)도 건드리지 않는다.
        assertThat(settled.providerStartedAt).isNull()
        assertThat(providerAttempts(job.jobId)).isZero()
        assertThat(llmCallCount(job.jobId)).isZero()
        assertThat(settlement(job.jobId)).isEqualTo("released")
        assertThat(isNullColumn(job.jobId, "lease_owner")).isTrue()
        assertThat(isNullColumn(job.jobId, "lease_until")).isTrue()
        assertThat(isNullColumn(job.jobId, "worker_slot")).isTrue()
        assertThat(transactionCount(job.jobId, "release")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isZero()
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("10")
        // 활성 작업 자리가 풀려 같은 계정이 다시 접수할 수 있다.
        assertThat(tx.execute { jobs.insert(fixture.job()) })
            .isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
    }

    @Test
    @DisplayName("상한까지의 만료된 미시작 작업은 지금처럼 다시 획득한다")
    fun `상한 안의 만료 작업은 다시 획득한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        repeat(MAX_LEASE_ATTEMPTS - 1) {
            assertThat(tx.execute { acquire("worker-a") }).isInstanceOf(ActionGuideJobAcquire.Held::class.java)
            expireLease(job.jobId)
        }

        val acquired = tx.execute { acquire("worker-b") }

        assertThat(acquired).isInstanceOf(ActionGuideJobAcquire.Held::class.java)
        assertThat(jobs.findOwned(fixture.ownerId, fixture.conversionId, job.jobId)?.status)
            .isEqualTo(ActionGuideJobStatus.RUNNING)
    }

    @Test
    @DisplayName("상한을 넘겨도 provider를 시작한 작업은 불명확 회수 경로를 그대로 탄다")
    fun `상한을 넘긴 시작 작업은 unknown 경로다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val first = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
        tx.executeWithoutResult { assertThat(jobs.markProviderStarted(first, UUID.randomUUID(), NOW)).isTrue() }
        jdbc
            .sql("UPDATE action_guide_jobs SET attempts = :attempts WHERE id = :id")
            .param("attempts", MAX_LEASE_ATTEMPTS + 1)
            .param("id", job.jobId)
            .update()
        expireLease(job.jobId)

        assertThat(tx.execute { acquire("worker-b") })
            .isInstanceOf(ActionGuideJobAcquire.RecoverUnknown::class.java)
    }

    private fun createIdempotently(job: StoredActionGuideJob) {
        tx.executeWithoutResult {
            if (jobs.findByRequestId(job.ownerId, job.conversionId, job.requestId) == null) {
                assertThat(
                    credits.reserve(
                        job.ownerId,
                        job.workspaceId,
                        job.documentId,
                        job.jobId,
                        Credits(job.reservedCredits),
                    ),
                ).isInstanceOf(kr.easydoc.application.actionguide.ActionGuideCreditReservation.Reserved::class.java)
                assertThat(jobs.insert(job)).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
            }
        }
    }

    /** 접수 → 획득 → provider 시작 → 성공까지 실제 경로로 한 번의 시도를 끝낸다. */
    private fun startAndSucceed(job: StoredActionGuideJob) {
        assertThat(tx.execute { jobs.insert(job) }).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
        val lease = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
        tx.executeWithoutResult { assertThat(jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)).isTrue() }
        tx.executeWithoutResult { assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(1))).isTrue() }
    }

    /** 입력이 바뀐 작업처럼 provider를 시작하지 않고 superseded로 끝낸다. */
    private fun supersedeWithoutStart(job: StoredActionGuideJob) {
        assertThat(tx.execute { jobs.insert(job) }).isInstanceOf(ActionGuideJobInsert.Inserted::class.java)
        val lease = (tx.execute { acquire("worker-a") } as ActionGuideJobAcquire.Held).lease
        tx.executeWithoutResult { assertThat(jobs.markSuperseded(lease, NOW.plusSeconds(1))).isTrue() }
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

    private fun accountReserved(workspaceId: UUID): BigDecimal = accountValue(workspaceId, "reserved")

    private fun accountBalance(workspaceId: UUID): BigDecimal = accountValue(workspaceId, "balance")

    private fun accountValue(
        workspaceId: UUID,
        column: String,
    ): BigDecimal =
        jdbc
            .sql("SELECT $column FROM workspace_credit_accounts WHERE workspace_id=:id")
            .param("id", workspaceId)
            .query { rs, _ -> rs.getBigDecimal(1) }
            .single()

    private fun llmOutcome(jobId: UUID): String =
        jdbc
            .sql("SELECT outcome FROM llm_calls WHERE action_guide_job_id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun jobCount(conversionId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM action_guide_jobs WHERE conversion_id=:id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun acquire(owner: String): ActionGuideJobAcquire =
        jobs.acquire(owner, Duration.ofMinutes(2), MAX_LEASE_ATTEMPTS)

    private fun expireLease(jobId: UUID) {
        jdbc
            .sql("UPDATE action_guide_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
            .param("id", jobId)
            .update()
    }

    private fun llmCallCount(jobId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM llm_calls WHERE action_guide_job_id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun settlement(jobId: UUID): String =
        jdbc
            .sql("SELECT settlement FROM action_guide_jobs WHERE id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun isNullColumn(
        jobId: UUID,
        column: String,
    ): Boolean =
        jdbc
            .sql("SELECT $column IS NULL FROM action_guide_jobs WHERE id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getBoolean(1) }
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
                BigDecimal.ONE,
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

        /** D04(개선 로드맵 §6) — 문서당 provider 호출을 시작한 작업은 3건까지다. */
        const val MAX_ATTEMPTS_PER_CONVERSION = 3

        /** 운영 기본값 하나를 그대로 쓴다 — 같은 숫자를 테스트에 다시 적지 않는다. */
        const val MAX_LEASE_ATTEMPTS = ActionGuideProperties.DEFAULT_MAX_LEASE_ATTEMPTS
        const val BLOCKED_MILLIS = 300L
        const val HANDOFF_SECONDS = 10L
    }
}

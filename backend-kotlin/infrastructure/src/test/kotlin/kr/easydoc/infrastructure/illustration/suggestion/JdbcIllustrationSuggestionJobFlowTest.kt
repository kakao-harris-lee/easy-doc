package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobAcquire
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobInsert
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobService
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionResult
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** V35 의 동시성·과금 경계를 실제 PostgreSQL 트랜잭션과 제약으로 확인한다(명세 §2·§3). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIllustrationSuggestionJobFlowTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var jobs: JdbcIllustrationSuggestionJobRepository
    private lateinit var credits: JdbcIllustrationSuggestionCreditPort
    private lateinit var ledger: JdbcIllustrationSuggestionLlmCallLedger
    private lateinit var results: JdbcIllustrationSuggestionResultRepository
    private lateinit var tx: TransactionTemplate
    private lateinit var service: IllustrationSuggestionJobService

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("illustration_suggestion_job_flow")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        jobs = JdbcIllustrationSuggestionJobRepository(jdbc, MAX_PROVIDER_ATTEMPTS)
        credits = JdbcIllustrationSuggestionCreditPort(jdbc, enforced = true)
        ledger = JdbcIllustrationSuggestionLlmCallLedger(jdbc)
        results = JdbcIllustrationSuggestionResultRepository(jdbc)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        service =
            IllustrationSuggestionJobService(
                enabled = true,
                creditsPer100Chars = BigDecimal("0.1"),
                jobs = jobs,
                credits = credits,
                transaction = SpringTransactionRunner(tx),
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
            )
    }

    @Test
    @DisplayName("같은 요청은 예약과 소비가 각각 한 번이고 CAS 울타리가 두 번째 종결을 막는다")
    fun `같은 요청은 한 번만 예약하고 소비한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        createIdempotently(job)

        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
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

        assertThat(jobCount(fixture.conversionId)).isEqualTo(1)
        assertThat(transactionCount(job.jobId, "reserve")).isEqualTo(1)
        assertThat(transactionCount(job.jobId, "consume")).isEqualTo(1)
        assertThat(settlement(job.jobId)).isEqualTo("consumed")
        assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("9")
        // 같은 리스로 다시 종결하려 해도 status 가 running 이 아니라 울타리가 막는다.
        assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(2))).isFalse()
    }

    @Test
    @DisplayName("예약 0인 작업은 이용량 거래 행을 만들지 않고 정산이 not_charged 로 남는다")
    fun `예약 0은 원장을 건드리지 않는다`() {
        val fixture = seed()
        val job = fixture.job(reserved = BigDecimal.ZERO)
        createIdempotently(job)

        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(1))).isTrue()
            credits.consume(held)
        }

        assertThat(settlement(job.jobId)).isEqualTo("not_charged")
        assertThat(transactionCount(job.jobId, "reserve")).isZero()
        assertThat(transactionCount(job.jobId, "consume")).isZero()
        assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("10")
    }

    @Test
    @DisplayName("provider 시작 뒤 리스가 만료되면 재호출 없이 unknown 과 release 로 정산한다")
    fun `시작 뒤 만료는 불명확 회수다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val first = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(first)!!
            assertThat(jobs.markProviderStarted(first, executionId, NOW)).isTrue()
            ledger.start(held, executionId, NOW)
        }
        expireLease(job.jobId)

        val recovered = tx.execute { acquire("worker-b") }

        assertThat(recovered).isInstanceOf(IllustrationSuggestionJobAcquire.RecoverUnknown::class.java)
        val lease = (recovered as IllustrationSuggestionJobAcquire.RecoverUnknown).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(
                jobs.markFailed(lease, IllustrationSuggestionJobFailureCode.OUTCOME_UNKNOWN, NOW.plusSeconds(2)),
            ).isTrue()
            ledger.markOutcomeUnknown(held, executionId)
            credits.release(held)
        }

        assertThat(llmOutcome(job.jobId)).isEqualTo("outcome_unknown")
        assertThat(llmCallCount(job.jobId)).isEqualTo(1)
        assertThat(transactionCount(job.jobId, "release")).isEqualTo(1)
        assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo(BigDecimal.ZERO)
        // 옛 리스로는 아무 것도 못 쓴다 — attempts 가 울타리다.
        assertThat(jobs.lockIfHeld(first)).isNull()
    }

    @Test
    @DisplayName("미시작 작업이 리스 상한을 넘기면 dead-letter 로 돌려준다")
    fun `상한을 넘긴 미시작 작업은 dead-letter 다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        tx.execute { acquire("worker-a") }
        repeat(MAX_LEASE_ATTEMPTS) {
            expireLease(job.jobId)
            tx.execute { acquire("worker-a") }
        }
        expireLease(job.jobId)

        assertThat(tx.execute { acquire("worker-a") })
            .isInstanceOf(IllustrationSuggestionJobAcquire.DeadLettered::class.java)
    }

    @Test
    @DisplayName("문서당 상한은 provider 를 시작한 작업만 센다 — 시작 없는 실패는 시도가 아니다")
    fun `상한은 시작한 작업만 센다`() {
        val fixture = seed()
        repeat(MAX_PROVIDER_ATTEMPTS + 1) { index ->
            val job = fixture.job()
            val inserted = jobs.insert(job)
            if (index < MAX_PROVIDER_ATTEMPTS) {
                assertThat(inserted).isInstanceOf(IllustrationSuggestionJobInsert.Inserted::class.java)
                val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
                tx.executeWithoutResult {
                    jobs.lockIfHeld(lease)
                    jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
                    jobs.markFailed(lease, IllustrationSuggestionJobFailureCode.RESULT_INVALID, NOW)
                }
            } else {
                assertThat(inserted).isEqualTo(IllustrationSuggestionJobInsert.AttemptLimit)
            }
        }

        // 시작 없이 끝난 작업을 더 심어도 상한 판정은 그대로다.
        val unstarted = fixture.job()
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, settlement, reserved_credits)
                VALUES (:id, :request, :owner, :workspace, :document, :conversion, 1, 1, :fingerprint,
                        'failed', 'released', 1.0)
                """.trimIndent(),
            ).param("id", unstarted.jobId)
            .param("request", unstarted.requestId)
            .param("owner", unstarted.ownerId)
            .param("workspace", unstarted.workspaceId)
            .param("document", unstarted.documentId)
            .param("conversion", unstarted.conversionId)
            .param("fingerprint", FINGERPRINT)
            .update()

        assertThat(jobs.insert(fixture.job())).isEqualTo(IllustrationSuggestionJobInsert.AttemptLimit)
    }

    @Test
    @DisplayName("계정당 활성 작업은 하나다 — 다른 문서라도 두 번째 접수는 활성 충돌이다")
    fun `계정당 활성 작업은 하나다`() {
        val fixture = seed()
        assertThat(jobs.insert(fixture.job())).isInstanceOf(IllustrationSuggestionJobInsert.Inserted::class.java)
        val other = seedDocument(fixture.ownerId, fixture.workspaceId)

        assertThat(jobs.insert(other.job())).isEqualTo(IllustrationSuggestionJobInsert.ActiveConflict)
    }

    @Test
    @DisplayName("본문이 바뀌면 hasCurrentInput 이 거짓이고 저장된 결과는 stale 로 읽힌다")
    fun `본문이 바뀌면 입력이 낡는다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
            jobs.markSucceeded(lease, NOW)
            assertThat(
                results.insertResult(
                    held,
                    StoredIllustrationSuggestionResult(
                        UUID.randomUUID(),
                        held.jobId,
                        held.conversionId,
                        held.basedOnContentRevision,
                        EncryptedContent(byteArrayOf(9), "aes256gcm-v1", 1),
                    ),
                ),
            ).isTrue()
        }

        jdbc
            .sql("UPDATE conversions SET content_revision = content_revision + 1 WHERE id = :id")
            .param("id", fixture.conversionId)
            .update()

        val stored = results.findLatestOwned(fixture.ownerId, fixture.conversionId)!!
        assertThat(stored.basedOnContentRevision).isEqualTo(1)
        assertThat(currentRevision(fixture.conversionId)).isEqualTo(2)
    }

    @Test
    @DisplayName("남의 결과는 조회되지 않는다 — 소유권을 같은 SQL 에서 확인한다")
    fun `남의 결과는 보이지 않는다`() {
        val mine = seed()
        val theirs = seed()
        val job = theirs.job()
        createIdempotently(job)
        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
            jobs.markSucceeded(lease, NOW)
            results.insertResult(
                held,
                StoredIllustrationSuggestionResult(
                    UUID.randomUUID(),
                    held.jobId,
                    held.conversionId,
                    held.basedOnContentRevision,
                    EncryptedContent(byteArrayOf(9), "aes256gcm-v1", 1),
                ),
            )
        }

        assertThat(results.findLatestOwned(mine.ownerId, theirs.conversionId)).isNull()
        assertThat(results.findLatestOwned(theirs.ownerId, theirs.conversionId)).isNotNull()
    }

    @Test
    @DisplayName("문서를 지우면 활성 작업의 예약이 풀리고 진행 중 호출이 outcome_unknown 이 된다")
    fun `문서 삭제가 활성 작업을 정산한다`() {
        val fixture = seed()
        val job = fixture.job()
        createIdempotently(job)
        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            jobs.markProviderStarted(lease, executionId, NOW)
            ledger.start(held, executionId, NOW)
        }

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(status(job.jobId)).isEqualTo("superseded")
        assertThat(settlement(job.jobId)).isEqualTo("released")
        assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(llmOutcome(job.jobId)).isEqualTo("outcome_unknown")
        // 작업 행 자체는 청구 근거로 남는다 — document_id·conversion_id 에 일부러 FK 를 두지 않았다.
        // (소유자·작업 공간이 NULL 이 되는 것은 users·workspaces 삭제, 즉 탈퇴 경로의 성질이다.)
        assertThat(jobCount(fixture.conversionId)).isEqualTo(1)
        assertThat(isNullColumn(job.jobId, "lease_owner")).isTrue()
        assertThat(isNullColumn(job.jobId, "worker_slot")).isTrue()
    }

    @Test
    @DisplayName("예약 0인 활성 작업도 문서 삭제로 종결되지만 정산 값은 not_charged 그대로다")
    fun `문서 삭제가 무과금 작업을 뭉개지 않는다`() {
        val fixture = seed()
        val job = fixture.job(reserved = BigDecimal.ZERO)
        createIdempotently(job)

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(status(job.jobId)).isEqualTo("superseded")
        assertThat(settlement(job.jobId)).isEqualTo("not_charged")
        assertThat(transactionCount(job.jobId, "release")).isZero()
    }

    /**
     * **접수와 정산이 잠금을 같은 순서로 잡는가** — 두 트랜잭션이 같은 계정을 두고 만나는 자리다.
     *
     * 정산은 작업 행을 먼저 잠그고(`markSucceeded`) 그 다음 이용량 계정을 잡는다
     * (`ProcessIllustrationSuggestionJob.settle`). 접수가 계정을 **먼저** 잡으면 두 순서가 엇갈려
     * 교착한다 — 접수는 계정을 든 채 새 작업 INSERT 가 정산 중인 작업의 활성 부분 UNIQUE 항목을
     * 기다리고, 정산은 그 계정을 기다린다.
     *
     * 교착에서 PostgreSQL 이 **정산 쪽을 죽이면** 이미 돈을 쓴 호출의 결과가 사라지고, 작업은
     * `running` 으로 남아 리스 만료 뒤 `outcome_unknown` 으로 정리되면서 시도 상한만 깎인다.
     * 그래서 접수는 작업 INSERT 를 계정 예약보다 **먼저** 한다.
     */
    @Test
    @DisplayName("접수는 정산과 같은 순서로 잠금을 잡는다 — 다른 문서의 정산과 교착하지 않는다")
    fun `접수와 정산이 교착하지 않는다`() {
        val fixture = seed()
        val other = seedDocument(fixture.ownerId, fixture.workspaceId)
        val running = fixture.job()
        createIdempotently(running)
        val lease = (tx.execute { acquire("worker-a") } as IllustrationSuggestionJobAcquire.Held).lease
        tx.executeWithoutResult {
            jobs.lockIfHeld(lease)
            jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
        }

        val pool = Executors.newSingleThreadExecutor()
        try {
            val terminalized = CountDownLatch(1)
            val settlement =
                pool.submit {
                    tx.executeWithoutResult {
                        val held = jobs.lockIfHeld(lease)!!
                        check(jobs.markSucceeded(lease, NOW.plusSeconds(1)))
                        terminalized.countDown()
                        // 접수가 계정을 먼저 잡을 시간을 준다 — 옛 순서라면 이 소비에서 교착한다.
                        Thread.sleep(BLOCKED_MILLIS)
                        credits.consume(held)
                    }
                }
            check(terminalized.await(HANDOFF_SECONDS, TimeUnit.SECONDS)) { "정산이 작업 행을 잠그지 못했다" }

            // 정산이 커밋될 때까지 INSERT 에서 기다렸다가 통과해야 한다 — 교착이면 둘 중 하나가 죽는다.
            val view = service.create(fixture.ownerId, other.conversionId, UUID.randomUUID(), 1)
            settlement.get(HANDOFF_SECONDS, TimeUnit.SECONDS)

            assertThat(view.job.status).isEqualTo(IllustrationSuggestionJobStatus.QUEUED)
            assertThat(settlement(running.jobId)).isEqualTo("consumed")
            assertThat(status(running.jobId)).isEqualTo("succeeded")
            // 예약 1.0(정산됨) + 새 접수 0.1 → 잔액 9, 예약 0.1 이 남는다.
            assertThat(accountBalance(fixture.workspaceId)).isEqualByComparingTo("9")
            assertThat(accountReserved(fixture.workspaceId)).isEqualByComparingTo(BigDecimal("0.1"))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun createIdempotently(job: StoredIllustrationSuggestionJob) {
        tx.executeWithoutResult {
            if (jobs.findByRequestId(job.ownerId, job.conversionId, job.requestId) != null) return@executeWithoutResult
            credits.reserve(job.ownerId, job.workspaceId, job.documentId, job.jobId, Credits(job.reservedCredits))
            jobs.insert(job)
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
            LlmCallPurpose.ILLUSTRATION_SUGGESTION,
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

    private fun acquire(owner: String): IllustrationSuggestionJobAcquire =
        jobs.acquire(owner, Duration.ofMinutes(2), MAX_LEASE_ATTEMPTS)

    private fun expireLease(jobId: UUID) {
        jdbc
            .sql("UPDATE illustration_suggestion_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
            .param("id", jobId)
            .update()
    }

    private fun transactionCount(
        jobId: UUID,
        kind: String,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM credit_transactions WHERE illustration_suggestion_job_id=:id AND kind=:kind")
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
            .sql("SELECT outcome FROM llm_calls WHERE illustration_suggestion_job_id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun llmCallCount(jobId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM llm_calls WHERE illustration_suggestion_job_id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun jobCount(conversionId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM illustration_suggestion_jobs WHERE conversion_id=:id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun settlement(jobId: UUID): String = jobColumn(jobId, "settlement")

    private fun status(jobId: UUID): String = jobColumn(jobId, "status")

    private fun jobColumn(
        jobId: UUID,
        column: String,
    ): String =
        jdbc
            .sql("SELECT $column FROM illustration_suggestion_jobs WHERE id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun isNullColumn(
        jobId: UUID,
        column: String,
    ): Boolean =
        jdbc
            .sql("SELECT $column IS NULL FROM illustration_suggestion_jobs WHERE id=:id")
            .param("id", jobId)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()

    private fun currentRevision(conversionId: UUID): Long =
        jdbc
            .sql("SELECT content_revision FROM conversions WHERE id=:id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getLong(1) }
            .single()

    private data class Fixture(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    ) {
        fun job(reserved: BigDecimal = BigDecimal.ONE): StoredIllustrationSuggestionJob =
            StoredIllustrationSuggestionJob(
                UUID.randomUUID(),
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                basedOnContentRevision = 1,
                reservedCredits = reserved,
                status = IllustrationSuggestionJobStatus.QUEUED,
                failureCode = null,
                executionId = null,
                providerStartedAt = null,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** `ck_illustration_suggestion_jobs_fingerprint_length` 이 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** 운영 기본값 하나를 그대로 쓴다 — 같은 숫자를 테스트에 다시 적지 않는다. */
        const val MAX_PROVIDER_ATTEMPTS = IllustrationSuggestionProperties.DEFAULT_MAX_PROVIDER_ATTEMPTS
        const val MAX_LEASE_ATTEMPTS = IllustrationSuggestionProperties.DEFAULT_MAX_LEASE_ATTEMPTS

        /** 접수가 계정 잠금을 먼저 잡을 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS = 300L
        const val HANDOFF_SECONDS = 20L
    }
}

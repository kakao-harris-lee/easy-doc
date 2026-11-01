@file:Suppress("LongMethod", "MaxLineLength", "UnusedParameter")

package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobAcquire
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobInsert
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobLease
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobOutcome
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobWorkerPolicy
import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.application.illustration.generation.ProcessIllustrationGenerationJob
import kr.easydoc.application.illustration.generation.StoredGeneratedIllustrationAsset
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.IllustrationProviderIoException
import kr.easydoc.core.exceptions.IllustrationProviderRejectedException
import kr.easydoc.core.exceptions.IllustrationProviderTimeoutException
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIllustrationGenerationJobFlowTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var jobs: JdbcIllustrationGenerationJobRepository
    private lateinit var credits: JdbcIllustrationGenerationCreditPort
    private lateinit var ledger: JdbcIllustrationGenerationLlmCallLedger
    private lateinit var assets: JdbcGeneratedIllustrationAssetRepository
    private lateinit var tx: TransactionTemplate

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("illustration_generation_job_flow")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        jobs = JdbcIllustrationGenerationJobRepository(jdbc)
        credits = JdbcIllustrationGenerationCreditPort(jdbc, enforced = true)
        ledger = JdbcIllustrationGenerationLlmCallLedger(jdbc)
        assets = JdbcGeneratedIllustrationAssetRepository(jdbc)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `접수와 삭제가 경합하면 새 작업은 삭제 정산과 입력 파기에 포함된다`() {
        val fixture = seed()
        val job = fixture.job()
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            lateinit var deletion: java.util.concurrent.Future<Int>
            tx.executeWithoutResult {
                assertThat(jobs.lockOwnedContext(fixture.ownerId, fixture.conversionId)).isNotNull()
                deletion =
                    pool.submit<Int> {
                        started.countDown()
                        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()
                    }
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
                org.assertj.core.api.Assertions
                    .assertThatThrownBy { deletion.get(300, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)
                assertThat(jobs.lockAndCountDocumentJobs(fixture.ownerId, fixture.documentId)).isZero()
                assertThat(jobs.insert(job)).isInstanceOf(IllustrationGenerationJobInsert.Inserted::class.java)
                credits.reserve(job.ownerId, job.workspaceId, job.documentId, job.jobId, Credits(job.reservedCredits))
            }
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(1)
            assertThat(scalar("SELECT status FROM illustration_generation_jobs WHERE id = '${job.jobId}'"))
                .isEqualTo("superseded")
            assertThat(
                scalar(
                    "SELECT input_payload_encrypted IS NULL FROM illustration_generation_jobs WHERE id = '${job.jobId}'",
                ),
            ).isEqualTo("t")
            assertThat(
                count("credit_transactions", "illustration_generation_job_id", job.jobId, "release"),
            ).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `성공은 PNG와 메타데이터를 암호문으로 저장하고 호출과 크레딧을 한 번만 정산한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertReserved(fixture, job)
        val lease = acquire()
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.hasCurrentInput(lease, 1, fixture.suggestionResultId)).isTrue()
            assertThat(jobs.markProviderStarted(lease, executionId, NOW)).isTrue()
            ledger.start(held, executionId, NOW)
        }
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(assets.insertAsset(held, asset(job.jobId, fixture.conversionId))).isTrue()
            assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(1))).isTrue()
            ledger.complete(held, executionId, completedRecord())
            credits.consume(held)
        }

        val stored = assets.listOwned(fixture.ownerId, fixture.conversionId).single()
        assertThat(stored.metadata.bytes).isNotEqualTo(byteArrayOf(1, 2, 3))
        assertThat(stored.png.bytes).isNotEqualTo(byteArrayOf(4, 5, 6))
        assertThat(stored.width).isEqualTo(1024)
        assertThat(stored.height).isEqualTo(1024)
        assertThat(count("llm_calls", "illustration_generation_job_id", job.jobId)).isEqualTo(1)
        assertThat(count("credit_transactions", "illustration_generation_job_id", job.jobId, "consume")).isEqualTo(1)
        assertThat(scalar("SELECT settlement FROM illustration_generation_jobs WHERE id = '${job.jobId}'"))
            .isEqualTo("consumed")
        assertThat(jobs.markSucceeded(lease, NOW.plusSeconds(2))).isFalse()
    }

    @Test
    fun `fake의 0 크레딧 성공은 거래 없이 not_charged로 끝난다`() {
        val fixture = seed()
        val job = fixture.job(reserved = BigDecimal.ZERO)
        insertReserved(fixture, job)
        val lease = acquire()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)).isTrue()
            assertThat(jobs.markSucceeded(lease, NOW)).isTrue()
            credits.consume(held)
        }

        assertThat(scalar("SELECT settlement FROM illustration_generation_jobs WHERE id = '${job.jobId}'"))
            .isEqualTo("not_charged")
        assertThat(count("credit_transactions", "illustration_generation_job_id", job.jobId)).isZero()
    }

    @Test
    fun `완료 전에 본문이 바뀌면 stale 결과는 저장되지 않고 예약을 반환한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertReserved(fixture, job)
        val lease = acquire()
        tx.executeWithoutResult {
            jobs.lockIfHeld(lease)
            jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
        }
        jdbc
            .sql("UPDATE conversions SET content_revision = 2 WHERE id = :id")
            .param("id", fixture.conversionId)
            .update()

        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            assertThat(jobs.hasCurrentInput(lease, 1, fixture.suggestionResultId)).isFalse()
            assertThat(assets.insertAsset(held, asset(job.jobId, fixture.conversionId))).isFalse()
            assertThat(jobs.markSuperseded(lease, NOW)).isTrue()
            credits.release(held)
        }

        assertThat(assets.listOwned(fixture.ownerId, fixture.conversionId)).isEmpty()
        assertThat(count("credit_transactions", "illustration_generation_job_id", job.jobId, "release")).isEqualTo(1)
    }

    @Test
    fun `provider 시작 뒤 만료는 재호출 없이 outcome_unknown으로 회수한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertReserved(fixture, job)
        val first = acquire()
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(first)!!
            jobs.markProviderStarted(first, executionId, NOW)
            ledger.start(held, executionId, NOW)
        }
        jdbc
            .sql("UPDATE illustration_generation_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
            .param("id", job.jobId)
            .update()

        val recovered = tx.execute { jobs.acquire("worker-b", java.time.Duration.ofSeconds(120), 5) }
        assertThat(recovered).isInstanceOf(IllustrationGenerationJobAcquire.RecoverUnknown::class.java)
        val lease = (recovered as IllustrationGenerationJobAcquire.RecoverUnknown).lease
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            jobs.markFailed(lease, IllustrationGenerationJobFailureCode.OUTCOME_UNKNOWN, NOW)
            ledger.markOutcomeUnknown(held, executionId)
            credits.release(held)
        }

        assertThat(scalar("SELECT outcome FROM llm_calls WHERE illustration_generation_job_id = '${job.jobId}'"))
            .isEqualTo("outcome_unknown")
        assertThat(count("llm_calls", "illustration_generation_job_id", job.jobId)).isEqualTo(1)
        assertThat(jobs.lockIfHeld(first)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["timeout", "io", "rejected"])
    fun `응답 불명확과 명시적 거절을 구분하고 비용 미확정과 예약 반환을 한 번만 기록한다`(failure: String) {
        val fixture = seed()
        val job = fixture.job()
        insertReserved(fixture, job)
        val cipher = generationInput(job)
        var calls = 0
        val provider =
            object : IllustrationGenerationProvider {
                override val name = "openai"

                override fun generate(request: IllustrationGenerationRequest): Nothing {
                    calls++
                    when (failure) {
                        "timeout" -> throw IllustrationProviderTimeoutException("test timeout")
                        "io" -> throw IllustrationProviderIoException("test I/O failure")
                        else -> throw IllustrationProviderRejectedException("test rejection")
                    }
                }
            }
        val process =
            ProcessIllustrationGenerationJob(
                jobs,
                credits,
                ledger,
                assets,
                cipher,
                ProviderIllustrationGenerationJobRunner(cipher, provider, BigDecimal("0.25")),
                SpringTransactionRunner(tx),
                IllustrationGenerationJobWorkerPolicy("worker-test", Duration.ofSeconds(120), 5),
            )

        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.FAILED)
        assertThat(process.processNext()).isEqualTo(IllustrationGenerationJobOutcome.IDLE)

        val unknown = failure != "rejected"
        assertThat(calls).isEqualTo(1)
        assertThat(
            scalar("SELECT status || ',' || settlement FROM illustration_generation_jobs WHERE id = '${job.jobId}'"),
        ).isEqualTo("failed,released")
        assertThat(scalar("SELECT failure_code FROM illustration_generation_jobs WHERE id = '${job.jobId}'"))
            .isEqualTo(if (unknown) "outcome_unknown" else "generation_failed")
        assertThat(scalar("SELECT outcome FROM llm_calls WHERE illustration_generation_job_id = '${job.jobId}'"))
            .isEqualTo(if (unknown) "outcome_unknown" else "provider_error")
        assertThat(
            scalar(
                "SELECT estimated_cost_usd IS NULL FROM llm_calls WHERE illustration_generation_job_id = '${job.jobId}'",
            ),
        ).isEqualTo("t")
        assertThat(count("llm_calls", "illustration_generation_job_id", job.jobId)).isEqualTo(1)
        assertThat(count("credit_transactions", "illustration_generation_job_id", job.jobId, "release")).isEqualTo(1)
        assertThat(count("credit_transactions", "illustration_generation_job_id", job.jobId, "consume")).isZero()
        assertThat(
            scalar(
                "SELECT reserved = 0 AND balance = 10 FROM workspace_credit_accounts WHERE workspace_id = '${fixture.workspaceId}'",
            ),
        ).isEqualTo("t")
        assertThat(assets.listOwned(fixture.ownerId, fixture.conversionId)).isEmpty()
    }

    @Test
    fun `적용과 제거는 본문과 자산 revision을 CAS로 검사하고 stale 적용을 거부한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertReserved(fixture, job)
        val lease = acquire()
        val assetId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(lease)!!
            jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
            assets.insertAsset(held, asset(job.jobId, fixture.conversionId, assetId))
            jobs.markSucceeded(lease, NOW)
            credits.consume(held)
        }

        assertThat(assets.updateApplication(fixture.ownerId, fixture.conversionId, assetId, 1, 1, true)).isTrue()
        jdbc
            .sql("UPDATE conversions SET content_revision = 2 WHERE id = :id")
            .param("id", fixture.conversionId)
            .update()
        assertThat(assets.updateApplication(fixture.ownerId, fixture.conversionId, assetId, 2, 2, true)).isFalse()
        assertThat(assets.updateApplication(fixture.ownerId, fixture.conversionId, assetId, 2, 2, false)).isTrue()
        assertThat(assets.updateApplication(fixture.ownerId, fixture.conversionId, assetId, 2, 2, false)).isFalse()

        val stored = assets.findOwned(fixture.ownerId, fixture.conversionId, assetId)!!
        assertThat(stored.applied).isFalse()
        assertThat(stored.assetRevision).isEqualTo(3)
        assertThat(scalar("SELECT content_revision FROM conversions WHERE id = '${fixture.conversionId}'"))
            .isEqualTo("2")
    }

    @Test
    fun `문서 삭제는 자산을 CASCADE로 파기하고 활성 예약과 시작 호출을 정산하며 감사 행만 남긴다`() {
        val fixture = seed()
        val succeeded = fixture.job()
        insertReserved(fixture, succeeded)
        val firstLease = acquire()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(firstLease)!!
            jobs.markProviderStarted(firstLease, UUID.randomUUID(), NOW)
            assets.insertAsset(held, asset(succeeded.jobId, fixture.conversionId))
            jobs.markSucceeded(firstLease, NOW)
            credits.consume(held)
        }
        val active = fixture.job()
        tx.executeWithoutResult {
            assertThat(jobs.insert(active)).isInstanceOf(IllustrationGenerationJobInsert.Inserted::class.java)
            credits.reserve(
                active.ownerId,
                active.workspaceId,
                active.documentId,
                active.jobId,
                Credits(active.reservedCredits),
            )
        }
        val activeLease = acquire()
        val executionId = UUID.randomUUID()
        tx.executeWithoutResult {
            val held = jobs.lockIfHeld(activeLease)!!
            jobs.markProviderStarted(activeLease, executionId, NOW)
            ledger.start(held, executionId, NOW)
        }

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(count("generated_illustration_assets", "job_id", succeeded.jobId)).isZero()
        assertThat(
            scalar("SELECT count(*) FROM illustration_generation_jobs WHERE document_id = '${fixture.documentId}'"),
        ).isEqualTo("2")
        assertThat(
            scalar("SELECT status || ',' || settlement FROM illustration_generation_jobs WHERE id = '${active.jobId}'"),
        ).isEqualTo("superseded,released")
        assertThat(scalar("SELECT outcome FROM llm_calls WHERE illustration_generation_job_id = '${active.jobId}'"))
            .isEqualTo("outcome_unknown")
        assertThat(
            scalar(
                "SELECT count(*) FROM illustration_generation_jobs WHERE document_id = '${fixture.documentId}' AND input_payload_encrypted IS NOT NULL",
            ),
        ).isEqualTo("0")
    }

    private fun insertReserved(
        fixture: Fixture,
        job: StoredIllustrationGenerationJob,
    ) {
        tx.executeWithoutResult {
            assertThat(jobs.insert(job)).isInstanceOf(IllustrationGenerationJobInsert.Inserted::class.java)
            credits.reserve(job.ownerId, job.workspaceId, job.documentId, job.jobId, Credits(job.reservedCredits))
        }
    }

    private fun acquire(): IllustrationGenerationJobLease =
        (
            tx.execute { jobs.acquire("worker-a", java.time.Duration.ofSeconds(120), 5) }
                as IllustrationGenerationJobAcquire.Held
        ).lease

    private fun generationInput(job: StoredIllustrationGenerationJob): ContentCipher {
        val cipher =
            AesGcmContentCipher(
                mapOf(1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))),
                writeKeyVersion = 1,
            )
        val plaintext =
            """
            {"final_prompt":"Show the application steps.","execution_mode":"provider","metadata":{
            "suggestion_id":"${job.suggestionId}","analysis_version":"${job.analysisVersion}",
            "configuration_revision":"${job.configurationRevision}","purpose":"procedure","reason":"r",
            "body_range":{"start":0,"end":0},"source_anchors":[],"scenes":["s"],"preserved_facts":[],"alt_text":"a"}}
            """.trimIndent()
        val input = cipher.encrypt(PlainBody(plaintext), job.jobId, EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT)
        jdbc
            .sql(
                """
                UPDATE illustration_generation_jobs
                SET input_payload_encrypted = :input, encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id
                """.trimIndent(),
            ).param("input", input.bytes)
            .param("scheme", input.scheme)
            .param("keyVersion", input.keyVersion)
            .param("id", job.jobId)
            .update()
        return cipher
    }

    private fun seed(): Fixture {
        val fixture = Fixture()
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", fixture.ownerId)
            .param("email", "generation-${fixture.ownerId}@example.test")
            .param("password", DUMMY_PHC)
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", fixture.workspaceId)
            .param("owner", fixture.ownerId)
            .update()
        jdbc
            .sql("INSERT INTO workspace_credit_accounts (workspace_id,balance,reserved) VALUES (:id,10,0)")
            .param("id", fixture.workspaceId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                                       encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", fixture.documentId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", fixture.conversionId)
            .param("document", fixture.documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_jobs
                    (id,request_id,owner_user_id,workspace_id,document_id,conversion_id,
                     expected_content_revision,based_on_content_revision,input_fingerprint,
                     status,reserved_credits,settlement)
                VALUES (:id,gen_random_uuid(),:owner,:workspace,:document,:conversion,1,1,:fingerprint,
                        'succeeded',1.0,'consumed')
                """.trimIndent(),
            ).param("id", fixture.suggestionJobId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("document", fixture.documentId)
            .param("conversion", fixture.conversionId)
            .param("fingerprint", FINGERPRINT)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_results
                    (id,job_id,conversion_id,based_on_content_revision,payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:job,:conversion,1,:payload,'aes256gcm-v1',1)
                """.trimIndent(),
            ).param("id", fixture.suggestionResultId)
            .param("job", fixture.suggestionJobId)
            .param("conversion", fixture.conversionId)
            .param("payload", byteArrayOf(3))
            .update()
        return fixture
    }

    private fun asset(
        jobId: UUID,
        conversionId: UUID,
        assetId: UUID = UUID.randomUUID(),
    ) = StoredGeneratedIllustrationAsset(
        assetId,
        jobId,
        conversionId,
        1,
        EncryptedContent(byteArrayOf(10, 11), "aes256gcm-v1", 1),
        EncryptedContent(byteArrayOf(12, 13), "aes256gcm-v1", 1),
        2,
        1024,
        1024,
        "fake",
    )

    private fun completedRecord() =
        LlmCallRecord(
            LlmCallPurpose.ILLUSTRATION_GENERATION,
            "fake",
            "fake-image",
            0,
            0,
            1,
            BigDecimal.ZERO,
            null,
            null,
            10,
            NOW,
            LlmCallOutcome.COMPLETED,
        )

    private fun count(
        table: String,
        column: String,
        id: UUID,
        kind: String? = null,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM $table WHERE $column = :id" + (kind?.let { " AND kind = '$it'" } ?: ""))
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun scalar(sql: String): String = jdbc.sql(sql).query { rs, _ -> rs.getString(1) }.single()

    private class Fixture {
        val ownerId: UUID = UUID.randomUUID()
        val workspaceId: UUID = UUID.randomUUID()
        val documentId: UUID = UUID.randomUUID()
        val conversionId: UUID = UUID.randomUUID()
        val suggestionJobId: UUID = UUID.randomUUID()
        val suggestionResultId: UUID = UUID.randomUUID()

        fun job(reserved: BigDecimal = BigDecimal.ONE): StoredIllustrationGenerationJob {
            val id = UUID.randomUUID()
            return StoredIllustrationGenerationJob(
                id,
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                suggestionResultId,
                UUID.randomUUID(),
                "r7-illustration-suggestion-2",
                FINGERPRINT,
                1,
                FINGERPRINT,
                EncryptedContent(byteArrayOf(7, 8), "aes256gcm-v1", 1),
                reserved,
                IllustrationGenerationJobStatus.QUEUED,
                null,
                null,
                null,
                NOW,
                NOW,
            )
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-31T00:00:00Z")
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

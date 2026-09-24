package kr.easydoc.infrastructure.document

import kr.easydoc.application.actionguide.ActionGuideCreditReservation
import kr.easydoc.application.actionguide.ActionGuideJobAcquire
import kr.easydoc.application.actionguide.ActionGuideJobInsert
import kr.easydoc.application.actionguide.ActionGuideJobLease
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideCreditPort
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideJobRepository
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 사용자가 문서를 지우는 경로(`DocumentService.delete`)의 **잠금 순서**를 실제 PostgreSQL 에서 잰다.
 *
 * 문서 삭제 trigger(V29 `settle_action_guide_jobs_for_document`)와 작업 worker 의 정산
 * (`ProcessActionGuideJob.settle`)은 둘 다 **작업 행을 먼저 잠그고 그다음 이용량 계정**이다.
 * 삭제가 예약 해제로 계정을 **먼저** 잡으면 순서가 엇갈려, 삭제는 계정을 든 채 trigger 의 작업 행
 * 잠금을 기다리고 정산 중인 worker 는 그 계정을 기다린다. 사용자가 문서를 지우는, 가장 잦은 경로다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDocumentDeleteLockOrderTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var credits: CreditAccountService
    private lateinit var actionGuideJobs: JdbcActionGuideJobRepository
    private lateinit var actionGuideCredits: JdbcActionGuideCreditPort
    private lateinit var tx: TransactionTemplate
    private lateinit var documents: DocumentService

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("document_delete_lock_order")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        credits = CreditAccountService(JdbcCreditAccountRepository(jdbc), enforced = false)
        actionGuideJobs = JdbcActionGuideJobRepository(jdbc)
        actionGuideCredits = JdbcActionGuideCreditPort(jdbc, enforced = false)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        documents =
            DocumentService(
                storage =
                    DocumentStorage(
                        documents = JdbcDocumentRepository(jdbc),
                        originals = JdbcDocumentOriginalRepository(jdbc),
                        conversions = conversions,
                        queue = JdbcConversionQueue(jdbc),
                    ),
                workspaces = JdbcWorkspaceLookup(jdbc),
                users = users,
                // 삭제 경로는 본문을 읽지도 쓰지도 않는다 — 실제 구현을 넘기되 쓰이지 않는다.
                cipher = AesGcmContentCipher(mapOf(1 to randomKey()), writeKeyVersion = 1),
                extractor = DocumentTextExtractor { _, _ -> error("삭제 경로는 추출기를 쓰지 않는다") },
                transaction = SpringTransactionRunner(tx),
                credits = credits,
            )
    }

    @Test
    @DisplayName("사용자 삭제는 정산과 같은 순서로 잠금을 잡는다 — 정산 중인 행동 안내 작업과 교착하지 않는다")
    fun `문서 삭제와 행동 안내 정산이 교착하지 않는다`() {
        val seeded = seed()
        val lease = startActionGuideJob(seeded)

        val pool = Executors.newSingleThreadExecutor()
        try {
            var pending: Future<*>? = null
            tx.executeWithoutResult {
                val held = checkNotNull(actionGuideJobs.lockIfHeld(lease)) { "정산이 작업 행을 잠그지 못했다" }
                check(actionGuideJobs.markSucceeded(lease, NOW))
                val deleting = pool.submit { documents.delete(seeded.ownerId, seeded.documentId) }
                // 삭제는 trigger 의 작업 행 잠금 앞에서 멈춰 서 있어야 한다. 옛 순서라면 삭제가
                // 예약 해제로 계정을 이미 들고 여기 멈추고, 아래 소비가 그 계정을 기다려 교착한다.
                assertThatThrownBy { deleting.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
                    .isInstanceOf(TimeoutException::class.java)
                actionGuideCredits.consume(held)
                pending = deleting
            }

            checkNotNull(pending).get(HANDOFF_SECONDS, TimeUnit.SECONDS)

            assertThat(documentExists(seeded.documentId)).isFalse()
            // 정산이 살아남아 돈을 쓴 호출이 소비로 확정됐고, 삭제는 남은 변환 예약만 되돌렸다.
            assertThat(jobColumn(lease.jobId, "status")).isEqualTo(ActionGuideJobStatus.SUCCEEDED.wireName)
            assertThat(jobColumn(lease.jobId, "settlement")).isEqualTo("consumed")
            assertThat(accountValue(seeded.workspaceId, "reserved"))
                .withFailMessage("삭제나 정산 중 한쪽이 교착으로 죽어 예약이 남았다")
                .isEqualByComparingTo("0")
            assertThat(accountValue(seeded.workspaceId, "balance")).isEqualByComparingTo("9")
        } finally {
            pool.shutdownNow()
        }
    }

    /** 끝나지 않은 변환 예약([PENDING_CREDITS])을 쥔 문서 하나 — 삭제가 계정을 건드리게 만드는 몫. */
    private fun seed(): Seeded {
        val ownerId = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id
        val workspaceId = workspaces.create(ownerId, "공간").id
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, '제목', :format, :bytes, 10, :scheme, 1)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", ownerId)
            .param("workspace", workspaceId)
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(1, 2, 3, 4))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        conversions.insertPending(conversionId, documentId, EncryptionScheme.AES_256_GCM_V1, 1, PENDING_CREDITS)
        credits.ensureAccount(workspaceId)
        jdbc
            .sql("UPDATE workspace_credit_accounts SET balance = :balance WHERE workspace_id = :id")
            .param("balance", STARTING_BALANCE)
            .param("id", workspaceId)
            .update()
        // 등록이 남긴 예약과 같은 자리 — 이것이 있어야 삭제가 계정을 갱신한다.
        credits.reserve(ownerId, workspaceId, documentId, Credits(PENDING_CREDITS))
        return Seeded(documentId, conversionId, ownerId, workspaceId)
    }

    /** provider 호출을 시작한 행동 안내 작업 하나를 남기고 그 리스를 준다. */
    private fun startActionGuideJob(seeded: Seeded): ActionGuideJobLease {
        val jobId = UUID.randomUUID()
        tx.executeWithoutResult {
            val reservation =
                actionGuideCredits.reserve(
                    seeded.ownerId,
                    seeded.workspaceId,
                    seeded.documentId,
                    jobId,
                    Credits(GUIDE_CREDITS),
                )
            check(reservation is ActionGuideCreditReservation.Reserved)
            check(actionGuideJobs.insert(storedJob(seeded, jobId)) is ActionGuideJobInsert.Inserted)
        }
        val acquired = tx.execute { actionGuideJobs.acquire(WORKER, LEASE, MAX_LEASE_ATTEMPTS) }
        check(acquired is ActionGuideJobAcquire.Held) { "행동 안내 작업 리스를 집지 못했다: $acquired" }
        tx.executeWithoutResult { check(actionGuideJobs.markProviderStarted(acquired.lease, UUID.randomUUID(), NOW)) }
        return acquired.lease
    }

    private fun storedJob(
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

    private fun documentExists(id: UUID): Boolean =
        jdbc
            .sql("SELECT count(*) FROM documents WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt(1) }
            .single() > 0

    private fun jobColumn(
        jobId: UUID,
        column: String,
    ): String =
        jdbc
            .sql("SELECT $column FROM action_guide_jobs WHERE id = :id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    private fun accountValue(
        workspaceId: UUID,
        column: String,
    ): BigDecimal =
        jdbc
            .sql("SELECT $column FROM workspace_credit_accounts WHERE workspace_id = :id")
            .param("id", workspaceId)
            .query { rs, _ -> rs.getBigDecimal(1) }
            .single()

    private class Seeded(
        val documentId: UUID,
        val conversionId: UUID,
        val ownerId: UUID,
        val workspaceId: UUID,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")
        val LEASE: Duration = Duration.ofMinutes(2)
        const val WORKER: String = "worker-a"
        const val MAX_LEASE_ATTEMPTS: Int = 3
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** 끝나지 않은 변환 1건의 예약과, 정산 중인 행동 안내 작업 1건의 예약. */
        val PENDING_CREDITS: BigDecimal = BigDecimal("0.1")
        val GUIDE_CREDITS: BigDecimal = BigDecimal.ONE
        val STARTING_BALANCE: BigDecimal = BigDecimal.TEN

        /** 삭제가 잠금 앞에서 실제로 멈춰 서 있는지 볼 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS: Long = 300
        const val HANDOFF_SECONDS: Long = 20

        private const val KEY_BYTES = 32

        fun randomKey(): Secret {
            val material = ByteArray(KEY_BYTES)
            SecureRandom().nextBytes(material)
            return Secret(Base64.getEncoder().encodeToString(material))
        }
    }
}

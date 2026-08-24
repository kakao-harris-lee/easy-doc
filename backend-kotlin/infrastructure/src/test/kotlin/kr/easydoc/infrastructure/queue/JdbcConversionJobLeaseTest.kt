package kr.easydoc.infrastructure.queue

import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.document.JdbcConversionRepository
import kr.easydoc.infrastructure.document.JdbcDocumentRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** `conversion_jobs` 리스 획득·갱신·완료·실패·재시도 계약. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcConversionJobLeaseTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var queue: JdbcConversionQueue
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var transactions: TransactionTemplate
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("conversion_job_lease")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        queue = JdbcConversionQueue(jdbc)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        documents = JdbcDocumentRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    @DisplayName("ready 작업을 집으면 leased 가 되고 attempts 가 1 이 된다")
    fun `획득이 리스를 건다`() {
        val conversionId = seedJob()

        val lease = acquire(WORKER_A)

        assertThat(lease).isNotNull()
        assertThat(lease?.conversionId).isEqualTo(conversionId)
        assertThat(lease?.owner).isEqualTo(WORKER_A)
        assertThat(lease?.attempts).isEqualTo(1)
        assertThat(jobState(conversionId)).isEqualTo(JdbcConversionQueue.LEASED_STATE)
    }

    @Test
    @DisplayName("이미 집힌 작업은 다른 worker 가 집지 못한다")
    fun `유효한 리스는 독점이다`() {
        seedJob()
        assertThat(acquire(WORKER_A)).isNotNull()

        assertThat(acquire(WORKER_B)).isNull()
    }

    @Test
    @DisplayName("만료된 리스는 다른 worker 가 회수하고 attempts 가 올라간다")
    fun `만료되면 회수한다`() {
        val conversionId = seedJob()
        val first = checkNotNull(acquire(WORKER_A))
        expireLease(conversionId)

        val second = checkNotNull(acquire(WORKER_B))

        assertThat(second.owner).isEqualTo(WORKER_B)
        assertThat(second.attempts).isEqualTo(first.attempts + 1)
        assertThat(queue.complete(first)).isFalse()
        assertThat(queue.complete(second)).isTrue()
        assertThat(jobState(conversionId)).isEqualTo(JdbcConversionQueue.DONE_STATE)
    }

    @Test
    @DisplayName("시도 상한을 쓴 만료 작업은 다시 집지 않고 failed 가 된다")
    fun `상한을 넘긴 만료는 실패로 확정한다`() {
        val conversionId = seedJob()
        checkNotNull(acquire(WORKER_A))
        jdbc
            .sql(
                """
                UPDATE conversion_jobs
                SET attempts = :attempts, lease_until = now() - INTERVAL '1 second'
                WHERE conversion_id = :id
                """.trimIndent(),
            ).param("attempts", MAX_ATTEMPTS)
            .param("id", conversionId)
            .update()

        val acquired =
            checkNotNull(
                transactions.execute { queue.acquire(WORKER_B, Duration.ofMinutes(2), MAX_ATTEMPTS) },
            )

        assertThat(acquired).isInstanceOf(ConversionAcquire.Exhausted::class.java)
        assertThat((acquired as ConversionAcquire.Exhausted).conversionId).isEqualTo(conversionId)
        assertThat(jobState(conversionId)).isEqualTo(JdbcConversionQueue.FAILED_STATE)
        assertThat(jobAttempts(conversionId)).isEqualTo(MAX_ATTEMPTS)
        assertThat(acquire(WORKER_A)).isNull()
    }

    @Test
    @DisplayName("갱신은 같은 fencing 토큰에서만 만료를 민다")
    fun `갱신은 주인만 한다`() {
        seedJob()
        val held = checkNotNull(acquire(WORKER_A))
        val untilBefore = leaseUntil(held.conversionId)

        assertThat(queue.renew(held, Duration.ofMinutes(5))).isTrue()
        assertThat(leaseUntil(held.conversionId)).isAfter(untilBefore)
        assertThat(queue.renew(ConversionJobLease(held.conversionId, WORKER_B, held.attempts), Duration.ofMinutes(5)))
            .isFalse()
    }

    @Test
    @DisplayName("실패 확정은 fencing 이 맞을 때만 failed 가 된다")
    fun `실패도 fencing 을 쓴다`() {
        seedJob()
        val held = checkNotNull(acquire(WORKER_A))

        assertThat(queue.fail(ConversionJobLease(held.conversionId, WORKER_B, held.attempts))).isFalse()
        assertThat(queue.fail(held)).isTrue()
        assertThat(jobState(held.conversionId)).isEqualTo(JdbcConversionQueue.FAILED_STATE)
    }

    @Test
    @DisplayName("재시도는 ready 로 되돌리고 그 시각 전에는 다시 집히지 않는다")
    fun `재시도는 backoff 를 지킨다`() {
        seedJob()
        val held = checkNotNull(acquire(WORKER_A))

        assertThat(queue.retry(held, Duration.ofHours(1))).isTrue()
        assertThat(jobState(held.conversionId)).isEqualTo(JdbcConversionQueue.READY_STATE)
        assertThat(acquire(WORKER_B)).isNull()
    }

    @Test
    @DisplayName("두 worker 가 동시에 집으면 서로 다른 작업을 받는다")
    fun `SKIP LOCKED 가 한 행을 두 번 주지 않는다`() {
        val first = seedJob()
        val second = seedJob()
        val ready = CountDownLatch(2)
        val acquired = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(WORKER_A, WORKER_B).map { owner ->
                    pool.submit<ConversionAcquire> {
                        ready.countDown()
                        check(ready.await(5, TimeUnit.SECONDS))
                        var got: ConversionAcquire = ConversionAcquire.Empty
                        transactions.execute {
                            try {
                                got = queue.acquire(owner, Duration.ofMinutes(2), MAX_ATTEMPTS)
                            } finally {
                                acquired.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                        }
                        got
                    }
                }
            val bothAcquired = acquired.await(5, TimeUnit.SECONDS)
            release.countDown()
            val ids =
                futures.map { future ->
                    when (val got = future.get(5, TimeUnit.SECONDS)) {
                        is ConversionAcquire.Held -> {
                            got.lease.conversionId
                        }

                        else -> {
                            null
                        }
                    }
                }
            assertThat(bothAcquired).isTrue()
            assertThat(ids).containsExactlyInAnyOrder(first, second)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun acquire(owner: String): ConversionJobLease? =
        when (val got = transactions.execute { queue.acquire(owner, Duration.ofMinutes(2), MAX_ATTEMPTS) }) {
            is ConversionAcquire.Held -> got.lease
            else -> null
        }

    private fun seedJob(): UUID {
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
        return conversionId
    }

    private fun expireLease(conversionId: UUID) {
        jdbc
            .sql("UPDATE conversion_jobs SET lease_until = now() - INTERVAL '1 second' WHERE conversion_id = :id")
            .param("id", conversionId)
            .update()
    }

    private fun jobState(conversionId: UUID): String =
        jdbc
            .sql("SELECT state FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString("state") }
            .single()

    private fun leaseUntil(conversionId: UUID): java.time.OffsetDateTime =
        jdbc
            .sql("SELECT lease_until FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getObject("lease_until", java.time.OffsetDateTime::class.java) }
            .single()

    private fun jobAttempts(conversionId: UUID): Int =
        jdbc
            .sql("SELECT attempts FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getInt("attempts") }
            .single()

    private companion object {
        const val WORKER_A: String = "worker-a"
        const val WORKER_B: String = "worker-b"
        const val MAX_ATTEMPTS: Int = 3
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

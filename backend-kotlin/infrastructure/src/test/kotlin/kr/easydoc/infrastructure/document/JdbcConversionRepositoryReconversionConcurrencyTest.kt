package kr.easydoc.infrastructure.document

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.document.ReconversionReservation
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.InsufficientCreditsException
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 재변환 호출 예약(V10)의 **동시성** 회귀 고정판 — 계획 §4 결정 3 「사후 카운터가 아니라
 * 즉시 터지는 장치」. `reserveReconversionCalls` 의 예산 판정이 `UPDATE ... WHERE` 안에 있어
 * PostgreSQL 행 잠금이 직렬화하므로, 예산 20에 2회씩 다투는 20 스레드 중 **정확히 10건**만
 * 성공해야 한다 — 사후에 세는 구현이었다면 이 수가 흔들린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcConversionRepositoryReconversionConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcConversionRepository
    private lateinit var creditRepository: JdbcCreditAccountRepository
    private lateinit var credits: CreditAccountService
    private lateinit var tx: TransactionTemplate

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("reconversion_budget_concurrency")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        repository = JdbcConversionRepository(jdbc)
        creditRepository = JdbcCreditAccountRepository(jdbc)
        credits = CreditAccountService(creditRepository, enforced = true)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    @DisplayName("동시 20 스레드가 2회씩 예약을 다투면 예산 20에서 정확히 10건만 성공한다")
    fun `동시 예약은 예산을 넘지 않는다`() {
        val (ownerId, conversionId) = seedDoneConversion()

        val threadCount = 20
        val budget = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<ReconversionReservation>())

        val futures =
            (1..threadCount).map {
                executor.submit {
                    ready.countDown()
                    start.await()
                    results += repository.reserveReconversionCalls(ownerId, conversionId, amount = 2, budget = budget)
                }
            }
        ready.await()
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        val reserved = results.count { it is ReconversionReservation.Reserved }
        val exhausted = results.filterIsInstance<ReconversionReservation.Exhausted>()

        assertThat(results).hasSize(threadCount)
        assertThat(reserved)
            .withFailMessage("예약 성공 건수가 10이 아니다 — 예산 판정이 즉시(WHERE 절)가 아니라 사후에 서는지 확인하라: %s", results)
            .isEqualTo(BUDGET_DIV_COST)
        assertThat(exhausted).hasSize(threadCount - BUDGET_DIV_COST)
        // 예산이 다 찬 뒤에는 남는 예산이 0이어야 한다 — 사후 카운터가 밀려서 음수/초과로 새지 않았는가.
        exhausted.forEach { assertThat(it.remainingCallBudget).isEqualTo(0) }

        val finalState =
            jdbc
                .sql("SELECT reconversion_calls_reserved, reconversion_calls_used FROM conversions WHERE id = :id")
                .param("id", conversionId)
                .query { rs, _ -> rs.getInt("reconversion_calls_reserved") to rs.getInt("reconversion_calls_used") }
                .single()
        assertThat(finalState.first)
            .withFailMessage("최종 예약 합이 예산을 넘었다: %s", finalState)
            .isEqualTo(budget)
    }

    @Test
    @DisplayName("크레딧 부족으로 예약 트랜잭션이 실패하면 먼저 잡은 변환 예산도 롤백된다")
    fun `크레딧 부족은 변환 예산 예약을 롤백한다`() {
        val (ownerId, conversionId) = seedDoneConversion()
        val context = contextOf(conversionId)
        creditRepository.ensureAccount(context.workspaceId)

        assertThatThrownBy {
            tx.executeWithoutResult {
                assertThat(repository.reserveReconversionCalls(ownerId, conversionId, amount = 2, budget = 20))
                    .isEqualTo(ReconversionReservation.Reserved)
                credits.reserve(ownerId, context.workspaceId, context.documentId, Credits(BigDecimal("0.1")))
            }
        }.isInstanceOf(InsufficientCreditsException::class.java)

        assertThat(reconversionState(conversionId)).isEqualTo(0 to 0)
        assertThat(accountValue(context.workspaceId, "reserved")).isEqualByComparingTo("0")
    }

    @Test
    @DisplayName("예산과 크레딧이 모두 부족하면 크레딧 부족 402 우선순위를 유지한다")
    fun `예산과 크레딧이 모두 부족하면 크레딧 오류가 우선한다`() {
        val (ownerId, conversionId) = seedDoneConversion()
        val context = contextOf(conversionId)
        creditRepository.ensureAccount(context.workspaceId)

        assertThatThrownBy {
            tx.executeWithoutResult {
                assertThat(repository.reserveReconversionCalls(ownerId, conversionId, amount = 2, budget = 0))
                    .isEqualTo(ReconversionReservation.Exhausted(0))
                credits.reserve(ownerId, context.workspaceId, context.documentId, Credits(BigDecimal("0.1")))
            }
        }.isInstanceOf(InsufficientCreditsException::class.java)

        assertThat(reconversionState(conversionId)).isEqualTo(0 to 0)
        assertThat(accountValue(context.workspaceId, "reserved")).isEqualByComparingTo("0")
    }

    @Test
    @DisplayName("같은 변환의 예약과 정산이 겹쳐도 변환 행을 먼저 잠그고 교착하지 않는다")
    fun `예약과 정산이 겹쳐도 잠금 순서가 교착하지 않는다`() {
        val (ownerId, conversionId) = seedDoneConversion()
        val context = contextOf(conversionId)
        seedExistingReservation(ownerId, conversionId, context)

        val reservationLockedConversion = CountDownLatch(1)
        val allowReservationCredit = CountDownLatch(1)
        val settlementStarted = CountDownLatch(1)
        val settlementPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        val reservation =
            executor.submit {
                tx.executeWithoutResult {
                    assertThat(repository.reserveReconversionCalls(ownerId, conversionId, amount = 2, budget = 20))
                        .isEqualTo(ReconversionReservation.Reserved)
                    reservationLockedConversion.countDown()
                    check(allowReservationCredit.await(LOCK_HOLD_SECONDS, TimeUnit.SECONDS)) {
                        "예약 트랜잭션이 크레딧 계정으로 진행할 수 있게 열리지 않았다"
                    }
                    credits.reserve(ownerId, context.workspaceId, context.documentId, Credits(BigDecimal("0.1")))
                }
            }

        val settlement =
            executor.submit {
                check(reservationLockedConversion.await(WAIT_SECONDS, TimeUnit.SECONDS))
                tx.executeWithoutResult {
                    settlementPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                    settlementStarted.countDown()
                    repository.settleReconversionCalls(
                        ownerId = ownerId,
                        conversionId = conversionId,
                        reservedAmount = 2,
                        actualUsed = 1,
                        budget = 20,
                    )
                    creditRepository.consume(
                        context.workspaceId,
                        ownerId,
                        context.documentId,
                        conversionId,
                        Credits(BigDecimal("0.1")),
                    )
                }
            }

        try {
            assertThat(reservationLockedConversion.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(settlementStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(awaitPostgresLockWait(settlementPid.get())).isTrue()

            allowReservationCredit.countDown()
            reservation.get(WAIT_SECONDS, TimeUnit.SECONDS)
            settlement.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertThat(reconversionState(conversionId)).isEqualTo(2 to 1)
            assertThat(accountValue(context.workspaceId, "reserved")).isEqualByComparingTo("0.1")
            assertThat(accountValue(context.workspaceId, "balance")).isEqualByComparingTo("0.9")
        } finally {
            allowReservationCredit.countDown()
            executor.shutdownNow()
        }
    }

    private fun seedExistingReservation(
        ownerId: UUID,
        conversionId: UUID,
        context: ConversionContext,
    ) {
        creditRepository.ensureAccount(context.workspaceId)
        creditRepository.grant(
            context.workspaceId,
            ownerId,
            BigDecimal("1"),
            CreditReason.SIGNUP,
            note = null,
            actorUserId = null,
        )
        // An earlier request has reserved capacity and is now ready to settle.
        tx.executeWithoutResult {
            repository.reserveReconversionCalls(ownerId, conversionId, amount = 2, budget = 20)
            credits.reserve(ownerId, context.workspaceId, context.documentId, Credits(BigDecimal("0.1")))
        }
    }

    private fun awaitPostgresLockWait(pid: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc
                    .sql("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = :pid AND wait_event_type = 'Lock')")
                    .param("pid", pid)
                    .query(Boolean::class.java)
                    .single()
            if (waiting) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun reconversionState(conversionId: UUID): Pair<Int, Int> =
        jdbc
            .sql("SELECT reconversion_calls_reserved, reconversion_calls_used FROM conversions WHERE id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }
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

    private fun contextOf(conversionId: UUID): ConversionContext =
        jdbc
            .sql(
                """
                SELECT d.id AS document_id, d.workspace_id
                FROM conversions c
                JOIN documents d ON d.id = c.document_id
                WHERE c.id = :conversionId
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .query { rs, _ ->
                ConversionContext(
                    documentId = rs.getObject("document_id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                )
            }.single()

    private data class ConversionContext(
        val documentId: UUID,
        val workspaceId: UUID,
    )

    /** 완료 상태 변환 한 건과 그 소유자·문서를 심는다. 반환은 (소유자 id, 변환 id). */
    private fun seedDoneConversion(): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()

        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, 'x')")
            .param("id", ownerId)
            .param("email", "reconversion-concurrency-$ownerId@example.test")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :ownerId, '기본')")
            .param("id", workspaceId)
            .param("ownerId", ownerId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id, user_id, title, source_format, source_text_encrypted, encryption_scheme, key_version,
                     char_count, workspace_id)
                VALUES (:id, :ownerId, '안내문', 'text', :sourceText, 'aes256gcm-v1', 1, 4, :workspaceId)
                """.trimIndent(),
            ).param("id", documentId)
            .param("ownerId", ownerId)
            .param("sourceText", ByteArray(SOURCE_BYTES_SIZE))
            .param("workspaceId", workspaceId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version)
                VALUES (:id, :documentId, 'done', 'aes256gcm-v1', 1)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("documentId", documentId)
            .update()

        return ownerId to conversionId
    }

    private companion object {
        const val BUDGET_DIV_COST = 10
        const val SOURCE_BYTES_SIZE = 32
        const val WAIT_SECONDS = 5L
        const val LOCK_HOLD_SECONDS = 30L
        const val POLL_MILLIS = 10L
    }
}

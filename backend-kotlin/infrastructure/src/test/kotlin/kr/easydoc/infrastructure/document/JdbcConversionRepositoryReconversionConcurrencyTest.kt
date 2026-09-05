package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ReconversionReservation
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 재변환 호출 예약(V10)의 **동시성** 회귀 고정판 — 계획 §4 결정 3 「사후 카운터가 아니라
 * 즉시 터지는 장치」. `reserveReconversionCalls` 의 예산 판정이 `UPDATE ... WHERE` 안에 있어
 * PostgreSQL 행 잠금이 직렬화하므로, 예산 20에 2회씩 다투는 20 스레드 중 **정확히 10건**만
 * 성공해야 한다 — 사후에 세는 구현이었다면 이 수가 흔들린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcConversionRepositoryReconversionConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcConversionRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("reconversion_budget_concurrency")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
        repository = JdbcConversionRepository(jdbc)
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
    }
}

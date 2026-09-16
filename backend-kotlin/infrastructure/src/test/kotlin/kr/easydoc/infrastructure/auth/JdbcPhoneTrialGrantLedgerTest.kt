package kr.easydoc.infrastructure.auth

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
 * `phone_trial_grant_records` 접근 — `phone_fingerprint` PK + `ON CONFLICT DO NOTHING`
 * 으로 번호당 체험 크레딧을 원자적으로 한 번만 내주는지 실제 PostgreSQL 에서 잰다
 * (`V26__phone_verification.sql`). 리뷰 MEDIUM 지적 — 이 원장에 테스트가 없었다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPhoneTrialGrantLedgerTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var ledger: JdbcPhoneTrialGrantLedger

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("phone_trial_grant_ledger")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        ledger = JdbcPhoneTrialGrantLedger(jdbc)
    }

    @Test
    @DisplayName("같은 지문의 첫 클레임은 true, 두 번째는 false다")
    fun `같은 지문은 한 번만 클레임된다`() {
        val fingerprint = "fp-${UUID.randomUUID()}"

        assertThat(ledger.claim(fingerprint)).isTrue()
        assertThat(ledger.claim(fingerprint))
            .withFailMessage("이미 클레임된 지문이 다시 체험 크레딧을 받았다")
            .isFalse()
    }

    @Test
    @DisplayName("서로 다른 지문은 독립적으로 클레임된다")
    fun `다른 지문은 서로 간섭하지 않는다`() {
        val first = "fp-${UUID.randomUUID()}"
        val second = "fp-${UUID.randomUUID()}"

        assertThat(ledger.claim(first)).isTrue()
        assertThat(ledger.claim(second)).isTrue()
    }

    @Test
    @DisplayName("같은 지문을 동시에 클레임하는 스레드 여럿 중 정확히 하나만 true다")
    fun `동시 클레임은 정확히 하나만 성공한다`() {
        val fingerprint = "fp-${UUID.randomUUID()}"
        val concurrency = 20
        val pool = Executors.newFixedThreadPool(concurrency)
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)

        val results =
            try {
                val futures =
                    (1..concurrency).map {
                        pool.submit<Boolean> {
                            ready.countDown()
                            start.await()
                            ledger.claim(fingerprint)
                        }
                    }
                ready.await()
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }

        assertThat(results.count { it })
            .withFailMessage("동시 클레임 %d건 중 true 여야 할 것이 정확히 1건이 아니었다: %s", concurrency, results)
            .isEqualTo(1)
    }
}

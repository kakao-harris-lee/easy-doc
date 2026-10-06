package kr.easydoc.infrastructure.credit

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class MonthlyLedgerMigrationTest {
    @Test
    @Suppress("LongMethod") // The test follows one V39 database through migration and later writes.
    fun `legacy dates stay unknown and unverified refund never invents a confirmation timestamp`() {
        val database = PostgresTestSupport.createEmptyDatabase("monthly_ledger_migration")
        val source = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .target("39")
            .load()
            .migrate()
        val jdbc = JdbcClient.create(source)
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val payment = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users(id,email,password_hash) VALUES(:id,:email,'hash')")
            .param("id", owner)
            .param("email", "$owner@example.test")
            .update()
        jdbc
            .sql("INSERT INTO workspaces(id,user_id,name) VALUES(:id,:owner,'legacy')")
            .param("id", workspace)
            .param("owner", owner)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO subscription_payments(workspace_id,id,plan_id,amount,status,created_at,
                    simulated_failure,provider,refunded_amount)
                VALUES(:workspace,:id,'starter',10000,'partially_refunded','2026-09-30'::timestamptz,false,'toss_test',2000)
                """.trimIndent(),
            ).param("workspace", workspace)
            .param("id", payment)
            .update()
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val oldEvents =
            jdbc
                .sql(
                    "SELECT amount_krw FROM subscription_payment_events WHERE workspace_id=:workspace " +
                        "AND occurred_at IS NULL AND historical ORDER BY amount_krw",
                ).param("workspace", workspace)
                .query(Int::class.java)
                .list()
        assertThat(oldEvents).containsExactly(2000, 10000)
        jdbc
            .sql("UPDATE subscription_payments SET refunded_amount=5000 WHERE workspace_id=:workspace")
            .param("workspace", workspace)
            .update()
        val newEvents =
            jdbc
                .sql(
                    "SELECT amount_krw FROM subscription_payment_events " +
                        "WHERE workspace_id=:workspace AND occurred_at IS NULL AND NOT historical",
                ).param("workspace", workspace)
                .query(Int::class.java)
                .list()
        assertThat(newEvents).containsExactly(3000)
        // The same order UUID in another workspace must not collide with the existing payment event.
        val otherWorkspace = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO workspaces(id,user_id,name) VALUES(:id,:owner,'other')")
            .param("id", otherWorkspace)
            .param("owner", owner)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO subscription_payments(workspace_id,id,plan_id,amount,status,created_at,
                    simulated_failure,provider,refunded_amount)
                VALUES(:workspace,:id,'starter',10000,'paid',now(),false,'stub',0)
                """.trimIndent(),
            ).param("workspace", otherWorkspace)
            .param("id", payment)
            .update()
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM subscription_payment_events WHERE payment_id=:id AND kind='payment'")
                .param("id", payment)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(2)
        val actor = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users(id,email,password_hash) VALUES(:id,:email,'hash')")
            .param("id", actor)
            .param("email", "$actor@example.test")
            .update()
        jdbc
            .sql(
                """
                INSERT INTO admin_credit_adjustments(operation_id,workspace_id,actor_user_id,credits,reason,note,
                    expected_balance,expected_reserved,expected_revision)
                VALUES(:id,:workspace,:actor,1,'manual','test',0,0,0)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspace", workspace)
            .param("actor", actor)
            .update()
        jdbc.sql("DELETE FROM users WHERE id=:actor").param("actor", actor).update()
        assertThat(
            jdbc
                .sql(
                    "SELECT count(*) FROM admin_credit_adjustments " +
                        "WHERE workspace_id=:workspace AND actor_user_id IS NULL",
                ).param("workspace", workspace)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1)
    }
}

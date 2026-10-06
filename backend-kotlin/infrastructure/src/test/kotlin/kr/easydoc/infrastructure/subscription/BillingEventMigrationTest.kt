package kr.easydoc.infrastructure.subscription

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID

class BillingEventMigrationTest {
    @Test
    fun `notifications deduplicate and report dates use approval instead of order creation`() {
        val db = PostgresTestSupport.createEmptyDatabase("billing_events")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val order = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$owner','billing-events@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$owner','events')")
        db.execute(
            """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,
                payload_encrypted,encryption_scheme,key_version,cycle_id,environment,approved_at)
            VALUES ('$order','$workspace','start',99000,'2026-01-31T23:00:00+09:00','2026-03-01T00:01:00+09:00',
                'charge','pending',decode('01','hex'),'aes256gcm-v1',1,'$order','toss_live','2026-02-01T00:01:00+09:00')
            """.trimIndent(),
        )
        repeat(2) { db.execute("UPDATE toss_billing_orders SET status='paid' WHERE id='$order'") }
        assertThat(db.queryInt("SELECT count(*) FROM billing_notifications WHERE workspace_id='$workspace'"))
            .isEqualTo(1)
        db.execute(
            """
            INSERT INTO subscription_payments(id,workspace_id,plan_id,amount,status,created_at,
                simulated_failure,provider)
            VALUES ('$order','$workspace','start',99000,'paid','2026-01-31T23:00:00+09:00',false,'toss_live')
            """.trimIndent(),
        )
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM subscription_payment_events WHERE payment_id='$order' " +
                    "AND occurred_at='2026-02-01T00:01:00+09:00' AND NOT is_test",
            ),
        ).isEqualTo(1)
        db.execute("UPDATE toss_billing_orders SET canceled_at='2026-03-01T00:01:00+09:00' WHERE id='$order'")
        db.execute(
            "UPDATE subscription_payments SET refunded_amount=1000,status='partially_refunded' WHERE id='$order'",
        )
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM subscription_payment_events WHERE payment_id='$order' AND kind='refund' " +
                    "AND occurred_at='2026-03-01T00:01:00+09:00' AND amount_krw=1000",
            ),
        ).isEqualTo(1)
    }
}

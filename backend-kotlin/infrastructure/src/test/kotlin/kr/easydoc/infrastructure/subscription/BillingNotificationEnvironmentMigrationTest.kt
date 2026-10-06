package kr.easydoc.infrastructure.subscription

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID

class BillingNotificationEnvironmentMigrationTest {
    @Test
    fun `only exact order associations restore legacy notification environment`() {
        val db = PostgresTestSupport.createEmptyDatabase("notification_environment")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .target("44")
            .load()
            .migrate()
        val mixed = workspace(db)
        val known = workspace(db)
        val unknown = workspace(db)
        order(db, mixed, "toss_test")
        order(db, mixed, "toss_live")
        order(db, known, "toss_test")
        db.execute(
            """
            INSERT INTO billing_notifications(workspace_id,event_key,event_type) VALUES
            ('$mixed','upcoming:$mixed:period','upcoming'),
            ('$known','upcoming:$known:period','upcoming'),
            ('$unknown','subscription:$unknown:period:canceling','canceling')
            """.trimIndent(),
        )
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE event_key LIKE 'order:%' AND environment='toss_test'",
            ),
        ).isEqualTo(2)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE event_key LIKE 'order:%' AND environment='toss_live'",
            ),
        ).isEqualTo(1)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE workspace_id='$known' AND event_type='upcoming' " +
                    "AND environment IS NULL AND state='manual_review'",
            ),
        ).isEqualTo(1)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE environment IS NULL AND state='manual_review'",
            ),
        ).isEqualTo(3)
        verifyNewEvents(db, mixed)
    }

    private fun verifyNewEvents(
        db: DatabaseHandle,
        mixed: UUID,
    ) {
        order(db, mixed, "toss_live")
        assertThat(db.queryInt("SELECT count(*) FROM billing_notifications WHERE environment='toss_live'"))
            .isEqualTo(2)
        db.execute(
            """
            INSERT INTO workspace_subscriptions(workspace_id,plan_id,allowance,monthly_price,
                status,cycle_ends_at,provider)
            VALUES('$mixed','start',50,99000,'canceling',now()+interval '1 month','toss_live')
            """.trimIndent(),
        )
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE workspace_id='$mixed' AND event_type='canceling' AND environment='toss_live'",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `test subscription event is not relabeled after subscription provider changes to live`() {
        val db = PostgresTestSupport.createEmptyDatabase("notification_provider_changed")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .target("44")
            .load()
            .migrate()
        val workspace = workspace(db)
        db.execute(
            """
            INSERT INTO workspace_subscriptions(workspace_id,plan_id,allowance,monthly_price,
                status,cycle_ends_at,provider)
            VALUES('$workspace','start',50,99000,'canceling',now()+interval '1 month','toss_test')
            """.trimIndent(),
        )
        db.execute("UPDATE workspace_subscriptions SET provider='toss_live' WHERE workspace_id='$workspace'")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM billing_notifications " +
                    "WHERE workspace_id='$workspace' AND environment IS NULL AND state='manual_review'",
            ),
        ).isEqualTo(1)
        assertThat(db.queryInt("SELECT count(*) FROM billing_notifications WHERE environment='toss_live'"))
            .isZero()
    }

    private fun workspace(db: DatabaseHandle): UUID {
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES('$owner','$owner@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES('$workspace','$owner','environment')")
        return workspace
    }

    private fun order(
        db: DatabaseHandle,
        workspace: UUID,
        environment: String,
    ) {
        val id = UUID.randomUUID()
        db.execute(
            """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,
                payload_encrypted,encryption_scheme,key_version,cycle_id,environment)
            VALUES('$id','$workspace','start',99000,now(),now()+interval '1 month','charge','paid',
                decode('01','hex'),'aes256gcm-v1',1,'$id','$environment')
            """.trimIndent(),
        )
        // Unchanged status must not enqueue twice, before or after environment migration.
        db.execute("UPDATE toss_billing_orders SET status='paid' WHERE id='$id'")
    }
}

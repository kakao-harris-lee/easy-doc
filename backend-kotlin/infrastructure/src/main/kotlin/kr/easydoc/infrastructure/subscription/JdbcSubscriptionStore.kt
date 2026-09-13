package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.Subscription
import kr.easydoc.application.subscription.SubscriptionPayment
import kr.easydoc.application.subscription.SubscriptionStore
import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class JdbcSubscriptionStore(private val jdbc: JdbcClient) : SubscriptionStore {
    override fun lockOwned(
        ownerId: UUID,
        workspaceId: UUID,
    ) {
        val found =
            jdbc
                .sql("SELECT id FROM workspaces WHERE id = :workspace AND user_id = :owner FOR UPDATE")
                .param("workspace", workspaceId)
                .param("owner", ownerId)
                .query(UUID::class.java)
                .optional()
        if (found.isEmpty) throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
    }

    override fun find(workspaceId: UUID): Subscription? =
        jdbc
            .sql(
                "SELECT s.*, w.user_id FROM workspace_subscriptions s " +
                    "JOIN workspaces w ON w.id = s.workspace_id WHERE s.workspace_id = :workspace",
            ).param("workspace", workspaceId)
            .query { rs, _ -> subscription(rs) }
            .optional()
            .orElse(null)

    override fun save(subscription: Subscription) {
        jdbc
            .sql(
                """
                INSERT INTO workspace_subscriptions (workspace_id, plan_id, allowance, monthly_price, status,
                  cycle_ends_at, provider)
                VALUES (:workspace, :plan, :allowance, :price, :status, :end, :provider)
                ON CONFLICT (workspace_id) DO UPDATE SET plan_id = EXCLUDED.plan_id, allowance = EXCLUDED.allowance,
                    monthly_price = EXCLUDED.monthly_price, status = EXCLUDED.status, cycle_ends_at =
                      EXCLUDED.cycle_ends_at, provider = EXCLUDED.provider
                """.trimIndent(),
            ).param("workspace", subscription.workspaceId)
            .param("provider", subscription.provider)
            .param("plan", subscription.planId)
            .param("allowance", subscription.allowance)
            .param("price", subscription.monthlyPrice)
            .param(
                "status",
                subscription.status,
            ).param("end", subscription.cycleEndsAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun payments(workspaceId: UUID): List<SubscriptionPayment> =
        jdbc
            .sql(
                "SELECT * FROM subscription_payments WHERE workspace_id = :workspace " +
                    "ORDER BY created_at DESC, id LIMIT 20",
            ).param("workspace", workspaceId)
            .query { rs, _ -> payment(rs) }
            .list()

    override fun payment(
        workspaceId: UUID,
        id: UUID,
    ): SubscriptionPayment? =
        jdbc
            .sql("SELECT * FROM subscription_payments WHERE workspace_id = :workspace AND id = :id")
            .param("workspace", workspaceId)
            .param("id", id)
            .query { rs, _ -> payment(rs) }
            .optional()
            .orElse(null)

    override fun record(payment: SubscriptionPayment) {
        jdbc
            .sql(
                """
                INSERT INTO subscription_payments (workspace_id, id, plan_id, amount, status, created_at,
                  simulated_failure, provider)
                VALUES (:workspace, :id, :plan, :amount, :status, :created, :failed, :provider)
                """.trimIndent(),
            ).param("workspace", payment.workspaceId)
            .param("id", payment.id)
            .param("provider", payment.provider)
            .param("plan", payment.planId)
            .param(
                "amount",
                payment.amount,
            ).param("status", payment.status)
            .param("created", payment.createdAt.atOffset(ZoneOffset.UTC))
            .param("failed", payment.simulatedFailure)
            .update()
    }

    override fun due(now: Instant): List<Subscription> =
        jdbc
            .sql(
                """
                SELECT s.*, w.user_id FROM workspace_subscriptions s JOIN workspaces w ON w.id = s.workspace_id
                WHERE s.status IN ('active', 'canceling') AND s.cycle_ends_at <= :now ORDER BY s.cycle_ends_at LIMIT 100
                """.trimIndent(),
            ).param("now", now.atOffset(ZoneOffset.UTC))
            .query { rs, _ -> subscription(rs) }
            .list()

    private fun subscription(rs: ResultSet) =
        Subscription(
            rs.getObject("workspace_id", UUID::class.java),
            rs.getObject("user_id", UUID::class.java),
            rs.getString("plan_id"),
            rs.getInt("allowance"),
            rs.getInt("monthly_price"),
            rs.getString("status"),
            rs.getObject("cycle_ends_at", OffsetDateTime::class.java).toInstant(),
            rs.getString("provider"),
        )

    private fun payment(rs: ResultSet) =
        SubscriptionPayment(
            rs.getObject("id", UUID::class.java),
            rs.getObject("workspace_id", UUID::class.java),
            rs.getString("plan_id"),
            rs.getInt("amount"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rs.getBoolean("simulated_failure"),
            rs.getString("provider"),
            rs.getInt("refunded_amount"),
        )
}

package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminBillingAction
import kr.easydoc.application.admin.AdminBillingActionView
import kr.easydoc.application.admin.AdminBillingOrder
import kr.easydoc.application.admin.AdminBillingStore
import kr.easydoc.application.admin.AdminRefundCommand
import kr.easydoc.application.admin.AdminRefundOperation
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

@Suppress("TooManyFunctions")
class JdbcAdminBillingStore(private val jdbc: JdbcClient) : AdminBillingStore {
    override fun lock(workspace: UUID) {
        val owner =
            jdbc
                .sql("SELECT user_id FROM workspaces WHERE id=:workspace")
                .param("workspace", workspace)
                .query(UUID::class.java)
                .optional()
                .orElseThrow { NotFoundException("작업 공간을 찾을 수 없습니다") }
        jdbc
            .sql(
                "SELECT id FROM users WHERE id=:owner FOR KEY SHARE",
            ).param("owner", owner)
            .query(UUID::class.java)
            .single()
        jdbc
            .sql(
                "SELECT id FROM workspaces WHERE id=:workspace FOR UPDATE",
            ).param("workspace", workspace)
            .query(UUID::class.java)
            .single()
    }

    override fun operation(id: UUID): AdminRefundOperation? =
        jdbc
            .sql("SELECT * FROM admin_billing_operations WHERE operation_id=:id")
            .param("id", id)
            .query { rs, _ -> operation(rs) }
            .optional()
            .orElse(null)

    override fun reserve(command: AdminRefundCommand) {
        val state =
            jdbc
                .sql(
                    "SELECT balance,reserved,revision FROM workspace_credit_accounts " +
                        "WHERE workspace_id=:workspace FOR UPDATE",
                ).param("workspace", command.workspaceId)
                .query {
                    rs,
                    _,
                    ->
                    Triple(rs.getBigDecimal("balance"), rs.getBigDecimal("reserved"), rs.getLong("revision"))
                }.single()
        if (state.third != command.expectedRevision) throw ConflictException("크레딧 상태가 변경되었습니다. 새로 조회한 뒤 확인하세요")
        if (command.recoveryCredits.signum() > 0 && command.recoveryCredits > state.first - state.second) {
            throw ConflictException("회수량이 사용 가능 크레딧을 초과합니다")
        }
        jdbc
            .sql(
                """
                INSERT INTO admin_billing_operations(operation_id,workspace_id,payment_id,actor_user_id,amount,
                    recovery_credits,stop_renewal,reason,expected_revision,before_state)
                SELECT :id,:workspace,:payment,:actor,:amount,:credits,:stop,:reason,:revision,to_jsonb(a)
                FROM workspace_credit_accounts a WHERE workspace_id=:workspace
                """.trimIndent(),
            ).param("id", command.operationId)
            .param("workspace", command.workspaceId)
            .param("payment", command.paymentId)
            .param(
                "actor",
                command.actorUserId,
            ).param("amount", command.amount)
            .param("credits", command.recoveryCredits)
            .param(
                "stop",
                command.stopRenewal,
            ).param("reason", command.reason)
            .param("revision", command.expectedRevision)
            .update()
        if (command.recoveryCredits.signum() > 0) {
            changeCredits(command, BigDecimal.ZERO, command.recoveryCredits, "reserve")
        }
    }

    override fun settle(
        operation: AdminRefundOperation,
        succeeded: Boolean,
    ) {
        val command = operation.command
        if (command.recoveryCredits.signum() > 0) {
            changeCredits(
                command,
                if (succeeded) -command.recoveryCredits else BigDecimal.ZERO,
                -command.recoveryCredits,
                if (succeeded) "adjust" else "release",
            )
        }
        if (succeeded && command.stopRenewal) {
            jdbc
                .sql(
                    "UPDATE workspace_subscriptions SET status='canceling' " +
                        "WHERE workspace_id=:workspace AND status IN ('active','past_due')",
                ).param("workspace", command.workspaceId)
                .update()
            jdbc
                .sql(
                    "UPDATE toss_billing_sessions SET state='revoking' " +
                        "WHERE workspace_id=:workspace AND state NOT IN ('revoked','revoking')",
                ).param("workspace", command.workspaceId)
                .update()
        }
        jdbc
            .sql(
                """
                UPDATE admin_billing_operations o SET status=:status,updated_at=now(),after_state=to_jsonb(a)
                FROM workspace_credit_accounts a WHERE o.operation_id=:id AND a.workspace_id=o.workspace_id
                """.trimIndent(),
            ).param("status", if (succeeded) "completed" else "failed")
            .param("id", command.operationId)
            .update()
    }

    private fun changeCredits(
        command: AdminRefundCommand,
        balance: BigDecimal,
        reserved: BigDecimal,
        kind: String,
    ) {
        jdbc
            .sql(
                "UPDATE workspace_credit_accounts SET balance=balance+:balance,reserved=reserved+:reserved," +
                    "refund_reserved=refund_reserved+:reserved," +
                    "updated_at=now() WHERE workspace_id=:workspace",
            ).param("balance", balance)
            .param("reserved", reserved)
            .param("workspace", command.workspaceId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions(id,workspace_id,owner_user_id,kind,balance_delta,reserved_delta,
                    reason,note,actor_user_id,payment_id)
                SELECT :id,:workspace,user_id,:kind,:balance,:reserved,'manual',:note,:actor,:payment
                FROM workspaces WHERE id=:workspace
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspace", command.workspaceId)
            .param("kind", kind)
            .param("balance", balance)
            .param("reserved", reserved)
            .param("note", command.reason)
            .param("actor", command.actorUserId.takeUnless { it == UUID(0, 0) })
            .param("payment", command.paymentId)
            .update()
    }

    override fun pending(): List<UUID> =
        jdbc
            .sql(
                "SELECT operation_id FROM admin_billing_operations " +
                    "WHERE status='pending' ORDER BY created_at LIMIT 100",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun operations(workspace: UUID): List<AdminRefundOperation> =
        jdbc
            .sql(
                "SELECT * FROM admin_billing_operations " +
                    "WHERE workspace_id=:workspace ORDER BY created_at DESC LIMIT 100",
            ).param("workspace", workspace)
            .query { rs, _ -> operation(rs) }
            .list()

    override fun orders(workspace: UUID): List<AdminBillingOrder> =
        jdbc
            .sql(
                """
                SELECT *,status IN ('pending','processing','manual_review') AND
                    created_at <= now()-interval '1 hour' AS needs_review
                FROM toss_billing_orders WHERE workspace_id=:workspace ORDER BY created_at DESC LIMIT 100
                """.trimIndent(),
            ).param("workspace", workspace)
            .query { rs, _ ->
                AdminBillingOrder(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("kind"),
                    rs.getString("status"),
                    rs.getInt("amount"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rs.getString("environment"),
                    rs.getBoolean("needs_review"),
                )
            }.list()

    override fun audit(
        workspace: UUID,
        actor: UUID,
        operation: UUID,
        action: String,
        reason: String,
        target: UUID?,
    ): Boolean {
        val existing =
            jdbc
                .sql(
                    """
                    SELECT workspace_id=:workspace AND actor_user_id=:actor AND action=:action AND reason=:reason
                        AND target_id IS NOT DISTINCT FROM :target AS matches,status
                    FROM admin_billing_actions WHERE operation_id=:id
                    """.trimIndent(),
                ).param(
                    "workspace",
                    workspace,
                ).param(
                    "actor",
                    actor,
                ).param("action", action)
                .param("reason", reason)
                .param("target", target)
                .param("id", operation)
                .query { rs, _ -> rs.getBoolean("matches") to rs.getString("status") }
                .optional()
        if (existing.isPresent) {
            if (!existing.get().first) throw ConflictException("같은 요청 번호로 다른 작업을 요청할 수 없습니다")
            return existing.get().second == "pending"
        }
        jdbc
            .sql(
                """
                INSERT INTO admin_billing_actions(operation_id,workspace_id,actor_user_id,action,target_id,
                    reason,before_state)
                SELECT :id,:workspace,:actor,:action,:target,:reason,
                    jsonb_build_object('subscription_status',s.status,'cycle_ends_at',s.cycle_ends_at,
                        'card_state',b.state)
                FROM workspaces w LEFT JOIN workspace_subscriptions s ON s.workspace_id=w.id
                    LEFT JOIN toss_billing_sessions b ON b.workspace_id=w.id WHERE w.id=:workspace
                """.trimIndent(),
            ).param(
                "id",
                operation,
            ).param(
                "workspace",
                workspace,
            ).param("actor", actor)
            .param("action", action)
            .param("target", target)
            .param("reason", reason)
            .update()
        return true
    }

    override fun pendingActions(): List<AdminBillingAction> =
        jdbc
            .sql(
                "SELECT * FROM admin_billing_actions WHERE status='pending' AND workspace_id IS NOT NULL " +
                    "ORDER BY created_at LIMIT 100",
            ).query { rs, _ ->
                AdminBillingAction(
                    rs.getObject("workspace_id", UUID::class.java),
                    rs.getObject("actor_user_id", UUID::class.java) ?: UUID(0, 0),
                    rs.getObject("operation_id", UUID::class.java),
                    rs.getString("action"),
                    rs.getString("reason"),
                    rs.getObject("target_id", UUID::class.java),
                )
            }.list()

    override fun finishAction(id: UUID) {
        jdbc
            .sql(
                """
                UPDATE admin_billing_actions a SET status='completed',updated_at=now(),after_state=(
                    SELECT jsonb_build_object('subscription_status',s.status,'cycle_ends_at',s.cycle_ends_at,
                        'card_state',b.state)
                    FROM workspaces w LEFT JOIN workspace_subscriptions s ON s.workspace_id=w.id
                        LEFT JOIN toss_billing_sessions b ON b.workspace_id=w.id WHERE w.id=a.workspace_id)
                WHERE operation_id=:id AND status='pending'
                """.trimIndent(),
            ).param("id", id)
            .update()
    }

    override fun actions(workspace: UUID): List<AdminBillingActionView> =
        jdbc
            .sql("SELECT * FROM admin_billing_actions WHERE workspace_id=:workspace ORDER BY created_at DESC LIMIT 100")
            .param("workspace", workspace)
            .query { rs, _ ->
                AdminBillingActionView(
                    rs.getObject("operation_id", UUID::class.java),
                    rs.getString("action"),
                    rs.getString("status"),
                    rs.getString("reason"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    private fun operation(rs: ResultSet): AdminRefundOperation =
        AdminRefundOperation(
            AdminRefundCommand(
                rs.getObject("operation_id", UUID::class.java),
                rs.getObject("workspace_id", UUID::class.java) ?: UUID(0, 0),
                rs.getObject("payment_id", UUID::class.java),
                rs.getObject("actor_user_id", UUID::class.java) ?: UUID(0, 0),
                rs.getInt("amount"),
                rs.getBigDecimal("recovery_credits").setScale(1),
                rs.getBoolean("stop_renewal"),
                rs.getString("reason"),
                rs.getLong("expected_revision"),
            ),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )
}

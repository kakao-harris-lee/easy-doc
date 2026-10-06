package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminNotificationCommand
import kr.easydoc.application.admin.AdminOperationsQuery
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.infrastructure.subscription.PaymentProperties
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Administrator-only metadata projections. No document bodies, SMTP contents or provider responses. */
@Component
@Profile("!migrate")
@Suppress("TooManyFunctions", "LargeClass")
open class JdbcAdminOperationsQuery(
    private val jdbc: JdbcClient,
    private val payment: PaymentProperties,
) : AdminOperationsQuery {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun operations(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        listOf("kind", "state", "environment").forEach { key ->
            filters[key]?.takeIf(String::isNotBlank)?.let {
                if (key == "environment" && it == "unknown") {
                    conditions += "environment IS NULL"
                } else {
                    conditions += "$key=:$key"
                    params[key] = it
                }
            }
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val from = "FROM ($QUEUE_SQL) q LEFT JOIN workspaces w ON w.id=q.workspace_id"
        val countFrom = "FROM ($QUEUE_SQL) q$where"
        val total =
            jdbc
                .sql("SELECT count(*) $countFrom")
                .params(params)
                .query(Long::class.java)
                .single()
        val counts =
            jdbc
                .sql("SELECT kind,count(*) AS count $countFrom GROUP BY kind")
                .params(params)
                .query { rs, _ -> rs.getString("kind") to rs.getLong("count") }
                .list()
                .toMap()
        val items =
            jdbc
                .sql(
                    """
                    SELECT q.*,w.name AS workspace_name $from$where ORDER BY severity DESC,created_at,id
                    LIMIT :size OFFSET :offset
                    """.trimIndent(),
                ).params(params)
                .param("size", size)
                .param("offset", (page.toLong() - 1) * size)
                .query {
                    rs,
                    _,
                    ->
                    row(rs)
                }.list()
        return mapOf("items" to items, "total" to total, "page" to page, "size" to size, "counts" to counts)
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun notifications(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        listOf("state", "environment", "event_type").forEach { key ->
            filters[key]?.takeIf(String::isNotBlank)?.let {
                if (key == "environment" && it == "unknown") {
                    conditions += "environment IS NULL"
                } else {
                    conditions += "$key=:$key"
                    params[key] = it
                }
            }
        }
        filters["id"]?.let {
            val id = it.toLongOrNull() ?: throw InvalidInputException("메일 번호를 확인하세요")
            conditions += "id=:id"
            params["id"] = id
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val total =
            jdbc
                .sql(
                    "SELECT count(*) FROM billing_notifications$where",
                ).params(params)
                .query(Long::class.java)
                .single()
        val ids =
            jdbc
                .sql(
                    """
                    SELECT id FROM billing_notifications$where ORDER BY created_at DESC,id DESC LIMIT :size
                    OFFSET :offset
                    """.trimIndent(),
                ).params(
                    params,
                ).param("size", size)
                .param("offset", (page.toLong() - 1) * size)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        return mapOf("items" to ids.map(::notification), "total" to total, "page" to page, "size" to size)
    }

    @Transactional
    @Suppress("LongMethod") // Durable UUID replay and transition commit atomically under one row lock.
    override fun notificationAction(
        id: Long,
        actor: UUID,
        command: AdminNotificationCommand,
        retry: Boolean,
    ): Map<String, Any?> {
        val action = validateCommand(command, retry)
        // Serialize UUID reuse even across different notifications, then lock the target row.
        jdbc
            .sql(
                "SELECT pg_advisory_xact_lock(hashtextextended(:id,0))",
            ).param("id", command.operationId.toString())
            .query {
                _,
                _,
                ->
                true
            }.single()
        val current =
            jdbc
                .sql("SELECT state,environment,revision,resolution FROM billing_notifications WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query { rs, _ -> row(rs) }
                .optional()
                .orElseThrow { NotFoundException("메일 알림을 찾을 수 없습니다") }
        val old =
            jdbc
                .sql(
                    """
                    SELECT notification_id,actor_user_id,action,reason,expected_revision FROM
                    admin_notification_actions WHERE operation_id=:id
                    """.trimIndent(),
                ).param("id", command.operationId)
                .query { rs, _ -> row(rs) }
                .optional()
                .orElse(null)
        if (old != null) {
            val expected =
                mapOf(
                    "notification_id" to id,
                    "actor_user_id" to actor,
                    "action" to action,
                    "reason" to command.reason,
                    "expected_revision" to command.expectedRevision,
                )
            if (old != expected) throw ConflictException("같은 요청 번호로 다른 작업을 요청할 수 없습니다")
            return notification(id)
        }
        validateTransition(current, command, retry)
        jdbc
            .sql(
                "INSERT INTO admin_notification_actions(operation_id,notification_id,actor_user_id,action,reason," +
                    "expected_revision) VALUES (:operation,:id,:actor,:action,:reason,:revision)",
            ).param("operation", command.operationId)
            .param("id", id)
            .param("actor", actor)
            .param("action", action)
            .param("reason", command.reason)
            .param("revision", command.expectedRevision)
            .update()
        val newState =
            if (retry) {
                "pending"
            } else if (action == "delivered") {
                "sent"
            } else {
                current["state"]
            }
        jdbc
            .sql(
                "UPDATE billing_notifications SET state=:state,resolution=:resolution,revision=revision+1 WHERE id=:id",
            ).param("state", newState)
            .param("resolution", if (retry) null else action)
            .param("id", id)
            .update()
        return notification(id)
    }

    private fun validateCommand(
        command: AdminNotificationCommand,
        retry: Boolean,
    ): String {
        if (command.reason.isBlank() || command.reason.length > MAX_REASON_LENGTH || command.expectedRevision < 0) {
            throw InvalidInputException("처리 사유와 확인 버전을 확인하세요")
        }
        val action = if (retry) "retry" else command.resolution
        if (action !in setOf("retry", "delivered", "not_delivered") || (retry && command.resolution != null)) {
            throw InvalidInputException("전달 확인 결과를 확인하세요")
        }
        return checkNotNull(action)
    }

    @Suppress("ThrowsCount") // Different rejection reasons are actionable for operators.
    private fun validateTransition(
        current: Map<String, Any?>,
        command: AdminNotificationCommand,
        retry: Boolean,
    ) {
        if (current["revision"] != command.expectedRevision) throw ConflictException("상태가 변경되었습니다. 다시 조회하세요")
        val state = current["state"]
        if (retry) {
            if (current["environment"] != payment.provider || payment.provider !in setOf("toss_test", "toss_live")) {
                throw ConflictException("현재 발송 환경과 일치하는 메일만 재발송할 수 있습니다")
            }
            if (state != "failed" && !(state == "manual_review" && current["resolution"] == "not_delivered")) {
                throw ConflictException("확정 실패 또는 미전달 확인 기록이 있어야 재발송할 수 있습니다")
            }
        } else if (state !in setOf("manual_review", "failed")) {
            throw ConflictException("현재 메일 상태에서는 전달 확인을 기록할 수 없습니다")
        }
    }

    private fun notification(id: Long): Map<String, Any?> {
        val item =
            jdbc
                .sql(
                    """
                    SELECT n.id,n.workspace_id,n.event_type,n.state,n.environment,n.revision,n.resolution,
                        n.created_at,n.attempted_at,n.sent_at,w.name AS workspace_name,u.email AS recipient_email,
                        (SELECT a.failure_code FROM billing_notification_attempts a
                            WHERE a.notification_id=n.id ORDER BY a.id DESC LIMIT 1) AS failure_code
                    FROM billing_notifications n JOIN workspaces w ON w.id=n.workspace_id
                    JOIN users u ON u.id=w.user_id WHERE n.id=:id
                    """.trimIndent(),
                ).param("id", id)
                .query { rs, _ -> row(rs) }
                .single()
                .toMutableMap()
        item["attempts"] =
            jdbc
                .sql(
                    """
                    SELECT id,state,started_at,finished_at,failure_code FROM billing_notification_attempts WHERE
                    notification_id=:id ORDER BY id
                    """.trimIndent(),
                ).param("id", id)
                .query { rs, _ -> row(rs) }
                .list()
        item["resolutions"] =
            jdbc
                .sql(
                    """
                    SELECT operation_id,actor_user_id,action,reason,created_at FROM
                    admin_notification_actions WHERE notification_id=:id ORDER BY created_at,operation_id
                    """.trimIndent(),
                ).param("id", id)
                .query { rs, _ -> row(rs) }
                .list()
        item["retry_allowed"] =
            item["environment"] == payment.provider && payment.provider in setOf("toss_test", "toss_live") &&
            (item["state"] == "failed" || (item["state"] == "manual_review" && item["resolution"] == "not_delivered"))
        item["retry_blocked_reason"] = if (item["retry_allowed"] == true) null else "환경 일치와 확정 실패 또는 미전달 확인이 필요합니다"
        item["resolve_allowed"] = item["state"] in setOf("failed", "manual_review")
        return item
    }

    override fun request(
        workspace: UUID,
        operation: UUID,
    ): Map<String, Any?> =
        jdbc
            .sql(
                """
                SELECT operation_id,'refund' AS kind,status,created_at,updated_at FROM admin_billing_operations WHERE workspace_id=:workspace AND operation_id=:id
                UNION ALL SELECT operation_id,action AS kind,status,created_at,updated_at FROM admin_billing_actions WHERE workspace_id=:workspace AND operation_id=:id
                UNION ALL SELECT operation_id,'credit','completed',created_at,created_at FROM admin_credit_adjustments WHERE workspace_id=:workspace AND operation_id=:id
                """.trimIndent(),
            ).param("workspace", workspace)
            .param("id", operation)
            .query { rs, _ -> row(rs) }
            .list()
            .let { matches ->
                // Legacy UUID namespaces are per action. Never guess when different kinds reused one UUID.
                if (matches.size > 1) throw ConflictException("같은 요청 번호가 여러 조치에 사용되었습니다. 이력을 확인하세요")
                matches.singleOrNull() ?: throw NotFoundException("요청이 아직 기록되지 않았습니다")
            }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun billingState(workspace: UUID): Map<String, Any?> {
        val state =
            jdbc
                .sql(
                    """
                    SELECT greatest(coalesce(s.admin_revision,0),coalesce(b.admin_revision,0),
                        coalesce((SELECT max(o.admin_revision) FROM toss_billing_orders o WHERE o.workspace_id=w.id),0)) AS revision,
                        s.status AS subscription_status,b.state AS card_state,
                        b.cleanup_pending AS deletion_pending
                    FROM workspaces w LEFT JOIN workspace_credit_accounts a ON a.workspace_id=w.id
                    LEFT JOIN workspace_subscriptions s ON s.workspace_id=w.id
                    LEFT JOIN toss_billing_sessions b ON b.workspace_id=w.id WHERE w.id=:workspace
                    """.trimIndent(),
                ).param("workspace", workspace)
                .query { rs, _ -> row(rs) }
                .optional()
                .orElseThrow { NotFoundException("작업공간을 찾을 수 없습니다") }

        fun action(
            name: String,
            allowed: Boolean,
            reason: String,
        ): Map<String, Any?> =
            mapOf(
                "action" to name,
                "allowed" to allowed,
                "reason" to if (allowed) null else reason,
            )
        val audit =
            jdbc
                .sql(
                    """
                    SELECT operation_id,actor_user_id,action,target_id,reason,status,created_at,updated_at,
                        before_state->>'subscription_status' AS before_subscription_status,
                        after_state->>'subscription_status' AS after_subscription_status,
                        before_state->>'card_state' AS before_card_state,after_state->>'card_state' AS after_card_state,
                        NULL::text AS before_balance,NULL::text AS after_balance
                    FROM admin_billing_actions WHERE workspace_id=:workspace
                    UNION ALL SELECT operation_id,actor_user_id,'refund',payment_id,reason,status,created_at,updated_at,
                        NULL,NULL,NULL,NULL,before_state->>'balance',after_state->>'balance'
                    FROM admin_billing_operations WHERE workspace_id=:workspace
                    UNION ALL SELECT operation_id,actor_user_id,'credit',operation_id,note,'completed',created_at,created_at,
                        NULL,NULL,NULL,NULL,expected_balance::text,(expected_balance+credits)::text
                    FROM admin_credit_adjustments WHERE workspace_id=:workspace
                    ORDER BY created_at DESC,operation_id DESC LIMIT 100
                    """.trimIndent(),
                ).param("workspace", workspace)
                .query { rs, _ -> row(rs) }
                .list()
        return mapOf(
            "revision" to state["revision"],
            "card_state" to state["card_state"],
            "deletion_pending" to (state["deletion_pending"] == true || state["card_state"] == "revoking"),
            "allowed_actions" to
                listOf(
                    action(
                        "stop_renewal",
                        state["subscription_status"] in setOf("active", "past_due"),
                        "갱신 가능한 구독이 없습니다",
                    ),
                    action(
                        "retry_card_deletion",
                        state["card_state"] == "revoking" || state["deletion_pending"] == true,
                        "삭제 대기 카드가 없습니다",
                    ),
                ),
            "audit" to audit,
        )
    }

    override fun errors(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?> {
        val where = StringBuilder("WHERE c.status='failed'")
        val params = mutableMapOf<String, Any?>()
        filters["failure_code"]?.takeIf(String::isNotBlank)?.let {
            where.append(" AND c.failure_code=:code")
            params["code"] =
                it
        }
        listOf("from", "to").forEach { key ->
            filters[key]?.takeIf(String::isNotBlank)?.let {
                val instant =
                    try {
                        Instant.parse(it)
                    } catch (
                        _: java.time.format.DateTimeParseException,
                    ) {
                        throw InvalidInputException("조회 기간을 확인하세요")
                    }
                where.append(if (key == "from") " AND c.created_at>=:from" else " AND c.created_at<:to")
                params[key] = instant.atOffset(java.time.ZoneOffset.UTC)
            }
        }
        val total =
            jdbc
                .sql("SELECT count(*) FROM conversions c JOIN documents d ON d.id=c.document_id $where")
                .params(params)
                .query(Long::class.java)
                .single()
        val items =
            jdbc
                .sql(
                    """
                    SELECT c.id AS conversion_id,d.workspace_id,c.failure_code,c.created_at
                    FROM conversions c JOIN documents d ON d.id=c.document_id $where
                    ORDER BY c.created_at DESC,c.id DESC LIMIT :size OFFSET :offset
                    """.trimIndent(),
                ).params(params)
                .param("size", size)
                .param("offset", (page.toLong() - 1) * size)
                .query {
                    rs,
                    _,
                    ->
                    row(rs)
                }.list()
        return mapOf("items" to items, "total" to total, "page" to page, "size" to size)
    }

    private fun row(rs: ResultSet): Map<String, Any?> =
        (1..rs.metaData.columnCount).associate { i ->
            rs.metaData.getColumnLabel(i) to
                when (val value = rs.getObject(i)) {
                    is java.sql.Timestamp -> value.toInstant().toString()
                    else -> value
                }
        }

    private companion object {
        const val MAX_REASON_LENGTH = 200
        val QUEUE_SQL =
            """
            SELECT id::text,'payment'::text AS kind,status AS state,environment,workspace_id,created_at,
                2 AS severity,'결제 재조회'::text AS next_action FROM toss_billing_orders
                WHERE kind<>'refund' AND status IN ('pending','processing','manual_review','suspend_pending') AND created_at<now()-interval '1 hour'
            UNION ALL SELECT operation_id::text,'refund',a.status,o.environment,a.workspace_id,a.created_at,1,'환불 처리 확인'
                FROM admin_billing_operations a LEFT JOIN toss_billing_orders o ON o.id=a.operation_id WHERE a.status='pending'
            UNION ALL SELECT workspace_id::text,'card_deletion',state,environment,workspace_id,deletion_pending_since,1,'카드 삭제 재처리'
                FROM toss_billing_sessions WHERE state='revoking' OR cleanup_pending
            UNION ALL SELECT id::text,'notification',state,environment,workspace_id,created_at,2,'메일 전달 확인'
                FROM billing_notifications WHERE state IN ('failed','manual_review') AND resolution IS DISTINCT FROM 'delivered'
            UNION ALL SELECT id::text,'invoice',status,NULL,workspace_id,requested_at,1,'발급 처리 결과 기록'
                FROM invoice_requests WHERE status='requested'
            """.trimIndent()
    }
}

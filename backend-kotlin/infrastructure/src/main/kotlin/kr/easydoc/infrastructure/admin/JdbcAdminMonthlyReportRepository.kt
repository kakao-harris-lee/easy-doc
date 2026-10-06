package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminCreditLedgerItem
import kr.easydoc.application.admin.AdminCreditLedgerPage
import kr.easydoc.application.admin.AdminCurrentCredits
import kr.easydoc.application.admin.AdminMonthPeriod
import kr.easydoc.application.admin.AdminMonthlyCompleteness
import kr.easydoc.application.admin.AdminMonthlyCredits
import kr.easydoc.application.admin.AdminMonthlyPayments
import kr.easydoc.application.admin.AdminMonthlyReportRepository
import kr.easydoc.application.admin.AdminMonthlySummary
import kr.easydoc.application.admin.AdminMonthlyUsage
import kr.easydoc.application.admin.AdminPaymentEventItem
import kr.easydoc.application.admin.AdminPaymentEventPage
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Administrator-only accounting reads. All totals in one repeatable-read snapshot. */
@Suppress("TooManyFunctions", "LongMethod", "MagicNumber")
class JdbcAdminMonthlyReportRepository(
    private val jdbc: JdbcClient,
    manager: PlatformTransactionManager,
) : AdminMonthlyReportRepository {
    private val snapshot =
        TransactionTemplate(manager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    override fun summary(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        timezone: String,
        now: Instant,
    ): AdminMonthlySummary = snapshot.execute { summaryInSnapshot(workspaceId, period, timezone, now) }

    private fun summaryInSnapshot(
        id: UUID,
        period: AdminMonthPeriod,
        timezone: String,
        now: Instant,
    ): AdminMonthlySummary {
        requireWorkspace(id)
        val current =
            jdbc
                .sql(
                    """
            SELECT a.*, s.status AS subscription_status FROM workspace_credit_accounts a
            LEFT JOIN workspace_subscriptions s ON s.workspace_id = a.workspace_id WHERE a.workspace_id = :id
        """,
                ).param("id", id)
                .query { rs, _ ->
                    AdminCurrentCredits(
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("reserved"),
                        rs.getBigDecimal("balance") - rs.getBigDecimal("reserved"),
                        rs.getBigDecimal("allowance"),
                        rs.instant("cycle_started_at"),
                        rs.instant("cycle_ends_at"),
                        rs.getString("subscription_status"),
                        now,
                        rs.getLong("revision"),
                    )
                }.optional()
                .orElseThrow { NotFoundException("크레딧 계정을 찾을 수 없습니다") }
        val totals =
            rangeQuery(CREDIT_TOTALS, id, period)
                .query { rs, _ ->
                    CreditTotals(
                        rs.getBigDecimal("opening"),
                        rs.getBigDecimal("closing"),
                        rs.getBigDecimal("reserved"),
                        rs.getBigDecimal("granted"),
                        rs.getBigDecimal("consumed"),
                        rs.getBigDecimal("expired"),
                        rs.getBigDecimal("adjustment"),
                        rs.getBigDecimal("cycle_net"),
                        rs.getInt("legacy_cycles"),
                        rs.getBigDecimal("all_balance"),
                        rs.getBigDecimal("all_reserved"),
                    )
                }.single()
        val credits =
            AdminMonthlyCredits(
                totals.opening,
                totals.granted,
                totals.consumed,
                totals.expired,
                totals.adjustment,
                totals.cycleNet,
                totals.closing,
                totals.reserved,
                totals.closing - totals.reserved,
                totals.legacyCycles,
            )
        val reasons =
            rangeQuery(
                """
            SELECT reason, -sum(balance_delta) AS credits FROM credit_transactions
            WHERE workspace_id=:id AND created_at>=:from AND created_at<:until AND kind='consume' GROUP BY reason
        """,
                id,
                period,
            ).query { rs, _ -> rs.getString("reason") to rs.getBigDecimal("credits") }.list().toMap()
        val usage =
            rangeQuery(
                """
            SELECT count(DISTINCT document_id) FILTER (WHERE outcome='completed' AND purpose IN ('convert','repair','reconvert')) AS documents,
              coalesce(sum(input_tokens) FILTER (WHERE outcome='completed'),0) AS input_tokens, coalesce(sum(output_tokens) FILTER (WHERE outcome='completed'),0) AS output_tokens,
              sum(estimated_cost_usd) FILTER (WHERE outcome='completed') AS known_cost, count(*) FILTER (WHERE estimated_cost_usd IS NULL) AS unknown_cost
            FROM llm_calls WHERE workspace_id=:id AND called_at>=:from AND called_at<:until
        """,
                id,
                period,
            ).query { rs, _ ->
                AdminMonthlyUsage(
                    rs.getInt("documents"),
                    rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"),
                    rs.getBigDecimal("known_cost"),
                    rs.getInt("unknown_cost"),
                    reasons,
                    BigDecimal.ZERO,
                )
            }.single()
        val legacyEstimate =
            if (reasons.isEmpty()) {
                rangeQuery(
                    """
            SELECT coalesce(sum(ceil(document_char_count::numeric/1000)),0) FROM (
              SELECT DISTINCT ON(document_id) document_char_count FROM llm_calls
              WHERE workspace_id=:id AND called_at>=:from AND called_at<:until AND outcome='completed'
              AND purpose IN ('convert','repair','reconvert') ORDER BY document_id) d
        """,
                    id,
                    period,
                ).query(BigDecimal::class.java).single()
            } else {
                BigDecimal.ZERO
            }
        val payments = paymentTotals(id, period)
        val matches =
            totals.allBalance.compareTo(current.balance) == 0 && totals.allReserved.compareTo(current.reserved) == 0
        val equation =
            (
                credits.opening + credits.granted - credits.consumed - credits.expired + credits.adjustment +
                    credits.cycleNet
            ).compareTo(credits.closing) == 0
        val missingPaymentLinks =
            rangeQuery(
                """
            SELECT count(*) FROM subscription_payment_events e
            WHERE e.workspace_id=:id AND e.kind='payment' AND NOT e.historical
              AND e.occurred_at>=:from AND e.occurred_at<:until
              AND NOT EXISTS (SELECT 1 FROM credit_transactions c WHERE c.workspace_id=e.workspace_id AND c.payment_id=e.payment_id)
        """,
                id,
                period,
            ).query(Int::class.java).single()
        val warnings =
            buildList {
                if (missingPaymentLinks > 0) add("결제 ${missingPaymentLinks}건의 크레딧 연결을 확인해야 합니다.")
                if (!matches) add("원장 합계와 현재 계정이 일치하지 않습니다. 과거 잔액 확인이 필요합니다.")
                if (!equation) add("월별 증감 합계 확인이 필요합니다.")
                if (credits.legacyCycleCount > 0) add("과거 주기 변경은 지급·소멸 분해 없이 순증감으로 표시합니다.")
                if (legacyEstimate.signum() > 0) add("소비 원장이 없는 사용량은 추정치이며 실제 사용 합계에 포함하지 않습니다.")
                if (payments.unknownDatePaidKrw + payments.unknownDateRefundKrw + payments.testUnknownDatePaidKrw +
                    payments.testUnknownDateRefundKrw >
                    0
                ) {
                    add("발생 월을 확인할 수 없는 과거 결제·환불은 월 합계에서 제외했습니다.")
                }
            }
        return AdminMonthlySummary(
            id,
            period.month,
            timezone,
            period.current,
            current,
            credits,
            usage.copy(estimatedLegacyCredits = legacyEstimate),
            payments,
            AdminMonthlyCompleteness(matches, equation, warnings),
        )
    }

    private fun paymentTotals(
        id: UUID,
        period: AdminMonthPeriod,
    ): AdminMonthlyPayments =
        rangeQuery(
            """
        SELECT
          coalesce(sum(amount_krw) FILTER (WHERE NOT is_test AND kind='payment' AND occurred_at>=:from AND occurred_at<:until),0) AS paid,
          coalesce(sum(amount_krw) FILTER (WHERE NOT is_test AND kind='refund' AND occurred_at>=:from AND occurred_at<:until),0) AS refunded,
          coalesce(sum(amount_krw) FILTER (WHERE is_test AND kind='payment' AND occurred_at>=:from AND occurred_at<:until),0) AS test_paid,
          coalesce(sum(amount_krw) FILTER (WHERE is_test AND kind='refund' AND occurred_at>=:from AND occurred_at<:until),0) AS test_refunded,
          coalesce(sum(amount_krw) FILTER (WHERE NOT is_test AND kind='payment' AND occurred_at IS NULL),0) AS unknown_paid,
          coalesce(sum(amount_krw) FILTER (WHERE NOT is_test AND kind='refund' AND occurred_at IS NULL),0) AS unknown_refund,
          coalesce(sum(amount_krw) FILTER (WHERE is_test AND kind='payment' AND occurred_at IS NULL),0) AS test_unknown_paid,
          coalesce(sum(amount_krw) FILTER (WHERE is_test AND kind='refund' AND occurred_at IS NULL),0) AS test_unknown_refund
        FROM subscription_payment_events WHERE workspace_id=:id
    """,
            id,
            period,
        ).query { rs, _ ->
            AdminMonthlyPayments(
                rs.getLong("paid"),
                rs.getLong("refunded"),
                rs.getLong("paid") - rs.getLong("refunded"),
                rs.getLong("test_paid"),
                rs.getLong("test_refunded"),
                rs.getLong("unknown_refund"),
                rs.getLong("unknown_paid"),
                rs.getLong("test_unknown_paid"),
                rs.getLong("test_unknown_refund"),
            )
        }.single()

    override fun transactions(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        kind: String?,
        page: Int,
        size: Int,
    ): AdminCreditLedgerPage =
        snapshot.execute {
            requireWorkspace(workspaceId)
            val where =
                "WHERE workspace_id=:id AND created_at>=:from AND created_at<:until " +
                    "AND (:kind::text IS NULL OR kind=:kind)"
            val total =
                rangeQuery(
                    "SELECT count(*) FROM credit_transactions $where",
                    workspaceId,
                    period,
                ).param("kind", kind).query(Int::class.java).single()
            val items =
                rangeQuery(
                    "SELECT * FROM credit_transactions $where " +
                        "ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset",
                    workspaceId,
                    period,
                ).param("kind", kind)
                    .param("size", size)
                    .param("offset", (page.toLong() - 1) * size)
                    .query { rs, _ ->
                        AdminCreditLedgerItem(
                            rs.uuid("id")!!,
                            rs.getString("kind"),
                            rs.getString("reason"),
                            rs.getBigDecimal("balance_delta"),
                            rs.getBigDecimal("reserved_delta"),
                            rs.getString("note"),
                            rs.uuid("actor_user_id"),
                            rs.uuid("payment_id"),
                            rs.instant("created_at")!!,
                            rs.getBigDecimal("granted_amount"),
                            rs.getBigDecimal("expired_amount"),
                            rs.getBigDecimal("adjustment_amount"),
                            rs.instant("cycle_started_at_before"),
                            rs.instant("cycle_ends_at_before"),
                            rs.instant("cycle_started_at_after"),
                            rs.instant("cycle_ends_at_after"),
                        )
                    }.list()
            AdminCreditLedgerPage(items, page, size, total)
        }

    override fun payments(
        workspaceId: UUID,
        period: AdminMonthPeriod,
        page: Int,
        size: Int,
    ): AdminPaymentEventPage =
        snapshot.execute {
            val ownerId = requireWorkspace(workspaceId)
            val where = "WHERE ((occurred_at>=:from AND occurred_at<:until) OR occurred_at IS NULL)"
            val total =
                rangeQuery(
                    "$PAYMENT_ROWS SELECT count(*) FROM payment_rows $where",
                    workspaceId,
                    period,
                ).param("ownerId", ownerId).query(Int::class.java).single()
            val items =
                rangeQuery(
                    """
            $PAYMENT_ROWS SELECT * FROM payment_rows $where
            ORDER BY occurred_at DESC NULLS LAST,id DESC LIMIT :size OFFSET :offset
        """,
                    workspaceId,
                    period,
                ).param("ownerId", ownerId)
                    .param("size", size)
                    .param("offset", (page.toLong() - 1) * size)
                    .query { rs, _ ->
                        AdminPaymentEventItem(
                            rs.uuid("id")!!,
                            rs.uuid("payment_id")!!,
                            rs.getString("kind"),
                            rs.getLong("amount_krw"),
                            rs.getBoolean("is_test"),
                            rs.instant("occurred_at"),
                            rs.instant("original_created_at")!!,
                            rs.getString("plan_id"),
                            rs.getString("status"),
                            rs.uuid("credit_transaction_id"),
                            rs.getLong("amount"),
                            rs.getLong("refunded_amount"),
                            rs.getString("provider"),
                        )
                    }.list()
            AdminPaymentEventPage(items, page, size, total)
        }

    private fun requireWorkspace(id: UUID): UUID =
        jdbc
            .sql("SELECT user_id FROM workspaces WHERE id=:id")
            .param("id", id)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { NotFoundException("작업공간을 찾을 수 없습니다") }

    private fun rangeQuery(
        sql: String,
        id: UUID,
        period: AdminMonthPeriod,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(
                sql,
            ).param(
                "id",
                id,
            ).param("from", period.from.atOffset(ZoneOffset.UTC))
            .param("until", period.until.atOffset(ZoneOffset.UTC))

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private data class CreditTotals(
        val opening: BigDecimal,
        val closing: BigDecimal,
        val reserved: BigDecimal,
        val granted: BigDecimal,
        val consumed: BigDecimal,
        val expired: BigDecimal,
        val adjustment: BigDecimal,
        val cycleNet: BigDecimal,
        val legacyCycles: Int,
        val allBalance: BigDecimal,
        val allReserved: BigDecimal,
    )

    private companion object {
        val PAYMENT_ROWS =
            """
            WITH payment_rows AS (
              SELECT e.id,e.payment_id,e.kind,e.amount_krw,e.is_test,e.occurred_at,
                p.created_at AS original_created_at,p.plan_id,p.status,p.amount,p.refunded_amount,p.provider,
                (SELECT c.id FROM credit_transactions c WHERE c.workspace_id=e.workspace_id AND c.payment_id=e.payment_id
                 ORDER BY c.created_at,c.id LIMIT 1) AS credit_transaction_id
              FROM subscription_payment_events e JOIN subscription_payments p ON p.workspace_id=e.workspace_id AND p.id=e.payment_id
              WHERE e.workspace_id=:id
              UNION ALL
              SELECT p.id,p.id,'attempt',p.amount,p.provider IN ('stub','toss_test'),p.created_at,p.created_at,p.plan_id,p.status,
                p.amount,p.refunded_amount,p.provider,NULL::uuid
              FROM subscription_payments p WHERE p.workspace_id=:id AND p.status='failed'
              UNION ALL
              SELECT o.id,coalesce(o.original_id,o.id),CASE WHEN o.kind='refund' THEN 'refund_attempt' ELSE 'attempt' END,
                o.amount,o.environment='toss_test',o.created_at,coalesce(p.created_at,o.created_at),o.plan_id,o.status,
                coalesce(p.amount,o.amount),coalesce(p.refunded_amount,0),o.environment,NULL::uuid
              FROM toss_billing_orders o JOIN workspaces w ON w.id=o.workspace_id AND w.user_id=:ownerId
              LEFT JOIN subscription_payments p ON p.workspace_id=o.workspace_id AND p.id=o.original_id
              WHERE o.workspace_id=:id AND (o.status IN ('pending','scheduled','processing','manual_review','suspend_pending') OR (o.kind='refund' AND o.status='failed'))
            )
            """.trimIndent()

        val CREDIT_TOTALS =
            """
            SELECT coalesce(sum(balance_delta) FILTER (WHERE created_at<:from),0) AS opening,
              coalesce(sum(balance_delta) FILTER (WHERE created_at<:until),0) AS closing,
              coalesce(sum(reserved_delta) FILTER (WHERE created_at<:until),0) AS reserved,
              coalesce(sum(CASE WHEN kind='grant' THEN balance_delta WHEN kind IN ('cycle_set','cycle_reset') THEN coalesce(granted_amount,0) ELSE 0 END) FILTER (WHERE created_at>=:from AND created_at<:until),0) AS granted,
              coalesce(-sum(balance_delta) FILTER (WHERE kind='consume' AND created_at>=:from AND created_at<:until),0) AS consumed,
              coalesce(sum(expired_amount) FILTER (WHERE kind IN ('cycle_set','cycle_reset') AND created_at>=:from AND created_at<:until),0) AS expired,
              coalesce(sum(CASE WHEN kind='adjust' THEN balance_delta WHEN kind IN ('cycle_set','cycle_reset') THEN coalesce(adjustment_amount,0) ELSE 0 END) FILTER (WHERE created_at>=:from AND created_at<:until),0) AS adjustment,
              coalesce(sum(balance_delta) FILTER (WHERE kind IN ('cycle_set','cycle_reset') AND granted_amount IS NULL AND created_at>=:from AND created_at<:until),0) AS cycle_net,
              count(*) FILTER (WHERE kind IN ('cycle_set','cycle_reset') AND granted_amount IS NULL AND created_at>=:from AND created_at<:until) AS legacy_cycles,
              coalesce(sum(balance_delta),0) AS all_balance, coalesce(sum(reserved_delta),0) AS all_reserved
            FROM credit_transactions WHERE workspace_id=:id
            """.trimIndent()
    }
}

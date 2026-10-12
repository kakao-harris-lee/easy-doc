package kr.easydoc.infrastructure.subscription

import kr.easydoc.core.exceptions.ConflictException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Called after workspace locks are taken, before any cascade can erase pending payment recovery data. */
object BillingDeletionGuard {
    fun check(
        jdbc: JdbcClient,
        owner: UUID,
        workspace: UUID? = null,
    ) {
        val ids =
            jdbc
                .sql(
                    "SELECT id FROM workspaces WHERE user_id=:owner " +
                        "AND (CAST(:workspace AS uuid) IS NULL OR id=:workspace) FOR UPDATE",
                ).param("owner", owner)
                .param("workspace", workspace, java.sql.Types.OTHER)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        ids.forEach { id ->
            val count =
                jdbc
                    .sql(
                        """
                        SELECT (SELECT count(*) FROM toss_billing_sessions
                                  WHERE workspace_id=:id AND environment='toss_live'
                                    AND (state NOT IN ('needs_card','revoked') OR cleanup_pending)) +
                            (SELECT count(*) FROM toss_billing_orders
                                  WHERE workspace_id=:id AND environment='toss_live' AND status IN
                              ('pending','processing','manual_review','suspend_pending','scheduled')) +
                            (SELECT count(*) FROM workspace_subscriptions WHERE workspace_id=:id
                              AND provider IN ('toss_test','toss_live') AND status='active') +
                            -- Workspace deletion has no legal-archive step.  Preserve every
                            -- historical live charge/refund until the account deletion path
                            -- archives it; account deletion itself passes NULL here and archives
                            -- the rows before deleting the live graph.
                            (SELECT count(*) FROM subscription_payments
                                  WHERE workspace_id=:id AND provider='toss_live'
                                    AND status IN ('paid','partially_refunded','refunded')
                                    AND CAST(:workspace AS uuid) IS NOT NULL) +
                            (SELECT count(*) FROM toss_billing_orders
                                  WHERE workspace_id=:id AND environment='toss_live'
                                    AND kind IN ('charge','renewal') AND status='paid'
                                    AND CAST(:workspace AS uuid) IS NOT NULL) +
                            (SELECT count(*) FROM admin_billing_operations WHERE workspace_id=:id AND status='pending') +
                            (SELECT count(*) FROM admin_billing_actions WHERE workspace_id=:id AND status='pending')
                        """.trimIndent(),
                    ).param("id", id)
                    .param("workspace", workspace, java.sql.Types.OTHER)
                    .query(Int::class.java)
                    .single()
            if (count > 0) {
                throw ConflictException(
                    if (workspace != null) {
                        "법정 결제 기록이 있는 작업 공간은 직접 삭제할 수 없습니다. 계정 탈퇴에서 보존 원장을 생성하세요"
                    } else {
                        "구독 갱신을 중단하고 카드 연결 해제와 결제 처리가 끝난 후 삭제하세요"
                    },
                )
            }
        }
    }
}

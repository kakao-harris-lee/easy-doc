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
                        SELECT (SELECT count(*) FROM toss_billing_sessions WHERE workspace_id=:id AND state<>'revoked') +
                            (SELECT count(*) FROM toss_billing_orders WHERE workspace_id=:id AND status IN
                              ('pending','processing','manual_review')) +
                            (SELECT count(*) FROM workspace_subscriptions WHERE workspace_id=:id AND provider='toss_test'
                              AND status='active')
                        """.trimIndent(),
                    ).param("id", id)
                    .query(Int::class.java)
                    .single()
            if (count > 0) throw ConflictException("구독 갱신을 중단하고 카드 연결 해제와 결제 처리가 끝난 후 삭제하세요")
        }
    }
}

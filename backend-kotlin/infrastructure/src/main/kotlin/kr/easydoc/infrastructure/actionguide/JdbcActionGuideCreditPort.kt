package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideCreditPort
import kr.easydoc.application.actionguide.ActionGuideCreditReservation
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.credit.Credits
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** action_guide job id로 예약과 terminal 정산을 각각 한 번만 남긴다. */
class JdbcActionGuideCreditPort(
    private val jdbc: JdbcClient,
    private val enforced: Boolean,
) : ActionGuideCreditPort {
    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): Int =
        jdbc
            .sql(
                """
                SELECT account.balance - account.reserved
                FROM workspace_credit_accounts account
                JOIN workspaces workspace ON workspace.id = account.workspace_id
                WHERE account.workspace_id = :workspaceId AND workspace.user_id = :ownerId
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getInt(1) }
            .optional()
            .orElse(0)

    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): ActionGuideCreditReservation {
        val updated =
            jdbc
                .sql(RESERVE_SQL)
                .param("workspaceId", workspaceId)
                .param("ownerId", ownerId)
                .param("amount", amount.amount)
                .param("enforced", enforced)
                .update()
        if (updated == 0) return ActionGuideCreditReservation.Insufficient(available(ownerId, workspaceId))
        insertTransaction(
            workspaceId,
            ownerId,
            documentId,
            jobId,
            kind = "reserve",
            balanceDelta = 0,
            reservedDelta = amount.amount,
        )
        return ActionGuideCreditReservation.Reserved(available(ownerId, workspaceId))
    }

    override fun consume(job: StoredActionGuideJob) {
        settle(job, kind = "consume", balanceDelta = -job.reservedCredits)
    }

    override fun release(job: StoredActionGuideJob) {
        settle(job, kind = "release", balanceDelta = 0)
    }

    private fun settle(
        job: StoredActionGuideJob,
        kind: String,
        balanceDelta: Int,
    ) {
        val updated =
            jdbc
                .sql(SETTLE_SQL)
                .param("workspaceId", job.workspaceId)
                .param("ownerId", job.ownerId)
                .param("amount", job.reservedCredits)
                .param("balanceDelta", balanceDelta)
                .update()
        check(updated == 1) { "행동 안내문 예약 계정을 정산할 수 없습니다" }
        insertTransaction(
            job.workspaceId,
            job.ownerId,
            job.documentId,
            job.jobId,
            kind,
            balanceDelta,
            -job.reservedCredits,
        )
    }

    @Suppress("LongParameterList")
    private fun insertTransaction(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        jobId: UUID,
        kind: String,
        balanceDelta: Int,
        reservedDelta: Int,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                     reserved_delta, reason, action_guide_job_id)
                VALUES (:id, :workspaceId, :ownerId, :documentId, :kind, :balanceDelta,
                        :reservedDelta, 'action_guide', :jobId)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("documentId", documentId)
            .param("kind", kind)
            .param("balanceDelta", balanceDelta)
            .param("reservedDelta", reservedDelta)
            .param("jobId", jobId)
            .update()
    }

    private companion object {
        val RESERVE_SQL =
            """
            UPDATE workspace_credit_accounts account
            SET reserved = reserved + :amount, updated_at = now()
            WHERE workspace_id = :workspaceId
              AND EXISTS (
                  SELECT 1 FROM workspaces workspace
                  WHERE workspace.id = account.workspace_id AND workspace.user_id = :ownerId
              )
              AND (:enforced = false OR balance - reserved >= :amount)
            """.trimIndent()

        val SETTLE_SQL =
            """
            UPDATE workspace_credit_accounts account
            SET balance = balance + :balanceDelta,
                reserved = reserved - :amount,
                updated_at = now()
            WHERE workspace_id = :workspaceId
              AND reserved >= :amount
              AND EXISTS (
                  SELECT 1 FROM workspaces workspace
                  WHERE workspace.id = account.workspace_id AND workspace.user_id = :ownerId
              )
            """.trimIndent()
    }
}

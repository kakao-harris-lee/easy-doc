package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.IllustrationGenerationCreditPort
import kr.easydoc.application.illustration.generation.IllustrationGenerationCreditReservation
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.credit.Credits
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.util.UUID

class JdbcIllustrationGenerationCreditPort(
    private val jdbc: JdbcClient,
    private val enforced: Boolean,
) : IllustrationGenerationCreditPort {
    override fun available(
        ownerId: UUID,
        workspaceId: UUID,
    ): BigDecimal =
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
            .query { rs, _ -> rs.getBigDecimal(1) }
            .optional()
            .orElse(BigDecimal.ZERO)

    @Suppress("ReturnCount")
    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationGenerationCreditReservation {
        if (amount.amount.signum() == 0) {
            return IllustrationGenerationCreditReservation.Reserved(available(ownerId, workspaceId))
        }
        val updated =
            jdbc
                .sql(RESERVE_SQL)
                .param("workspaceId", workspaceId)
                .param("ownerId", ownerId)
                .param("amount", amount.amount)
                .param("enforced", enforced)
                .update()
        if (updated == 0) {
            return IllustrationGenerationCreditReservation.Insufficient(available(ownerId, workspaceId))
        }
        insertTransaction(workspaceId, ownerId, documentId, jobId, "reserve", BigDecimal.ZERO, amount.amount)
        return IllustrationGenerationCreditReservation.Reserved(available(ownerId, workspaceId))
    }

    override fun consume(job: StoredIllustrationGenerationJob) {
        settle(job, "consume", -job.reservedCredits)
    }

    override fun release(job: StoredIllustrationGenerationJob) {
        settle(job, "release", BigDecimal.ZERO)
    }

    private fun settle(
        job: StoredIllustrationGenerationJob,
        kind: String,
        balanceDelta: BigDecimal,
    ) {
        if (job.reservedCredits.signum() == 0) return
        val updated =
            jdbc
                .sql(SETTLE_SQL)
                .param("workspaceId", job.workspaceId)
                .param("ownerId", job.ownerId)
                .param("amount", job.reservedCredits)
                .param("balanceDelta", balanceDelta)
                .update()
        check(updated == 1) { "그림 생성 예약 계정을 정산할 수 없습니다" }
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
        balanceDelta: BigDecimal,
        reservedDelta: BigDecimal,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                     reserved_delta, reason, illustration_generation_job_id)
                VALUES (:id, :workspaceId, :ownerId, :documentId, :kind, :balanceDelta,
                        :reservedDelta, 'illustration_generation', :jobId)
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
              AND EXISTS (SELECT 1 FROM workspaces workspace
                          WHERE workspace.id = account.workspace_id AND workspace.user_id = :ownerId)
              AND ((:enforced = false AND refund_reserved = 0) OR balance - reserved >= :amount)
            """.trimIndent()

        val SETTLE_SQL =
            """
            UPDATE workspace_credit_accounts account
            SET balance = balance + :balanceDelta, reserved = reserved - :amount, updated_at = now()
            WHERE workspace_id = :workspaceId AND reserved >= :amount
              AND EXISTS (SELECT 1 FROM workspaces workspace
                          WHERE workspace.id = account.workspace_id AND workspace.user_id = :ownerId)
            """.trimIndent()
    }
}

package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionCreditPort
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionCreditReservation
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.credit.Credits
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.util.UUID

/**
 * illustration_suggestion job id 로 예약과 terminal 정산을 각각 한 번만 남긴다(V35 부분 UNIQUE).
 *
 * **예약량이 0이면 계정도 원장도 건드리지 않는다**(명세 §3). 0을 그대로 흘려보내면
 * `reserved ± 0` UPDATE 와 금액 0짜리 거래 행이 남아, 나중에 원장을 읽는 쪽이 「이 작업은
 * 과금 대상이었는데 0으로 정산됐다」와 「애초에 과금 대상이 아니었다」를 구분하지 못한다.
 */
class JdbcIllustrationSuggestionCreditPort(
    private val jdbc: JdbcClient,
    private val enforced: Boolean,
) : IllustrationSuggestionCreditPort {
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

    // 무과금·잔액 부족·정상 예약은 서로 다른 결과라 갈래마다 끊는다.
    @Suppress("ReturnCount")
    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID,
        amount: Credits,
    ): IllustrationSuggestionCreditReservation {
        if (amount.amount.signum() == 0) {
            return IllustrationSuggestionCreditReservation.Reserved(available(ownerId, workspaceId))
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
            return IllustrationSuggestionCreditReservation.Insufficient(available(ownerId, workspaceId))
        }
        insertTransaction(
            workspaceId,
            ownerId,
            documentId,
            jobId,
            kind = "reserve",
            balanceDelta = BigDecimal.ZERO,
            reservedDelta = amount.amount,
        )
        return IllustrationSuggestionCreditReservation.Reserved(available(ownerId, workspaceId))
    }

    override fun consume(job: StoredIllustrationSuggestionJob) {
        settle(job, kind = "consume", balanceDelta = -job.reservedCredits)
    }

    override fun release(job: StoredIllustrationSuggestionJob) {
        settle(job, kind = "release", balanceDelta = BigDecimal.ZERO)
    }

    private fun settle(
        job: StoredIllustrationSuggestionJob,
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
        check(updated == 1) { "그림 제안 예약 계정을 정산할 수 없습니다" }
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
                     reserved_delta, reason, illustration_suggestion_job_id)
                VALUES (:id, :workspaceId, :ownerId, :documentId, :kind, :balanceDelta,
                        :reservedDelta, 'illustration_suggestion', :jobId)
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

package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideLlmCallLedger
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerJob
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerSql
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerWriter
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/** provider 시작 전 in_progress를 쓰고 동일 행을 terminal outcome으로 바꾼다. */
class JdbcActionGuideLlmCallLedger(private val jdbc: JdbcClient) : ActionGuideLlmCallLedger {
    private val writer =
        JdbcFeatureLlmCallLedgerWriter(
            jdbc = jdbc,
            sql =
                JdbcFeatureLlmCallLedgerSql(
                    start = START_SQL,
                    complete = COMPLETE_SQL,
                    markOutcomeUnknown = MARK_OUTCOME_UNKNOWN_SQL,
                ),
            completionFailureMessage = "행동 안내문 LLM 호출 원장을 완료할 수 없습니다",
            unknownFailureMessage = "행동 안내문 LLM 호출 원장을 불명확 상태로 바꿀 수 없습니다",
        )

    override fun start(
        job: StoredActionGuideJob,
        executionId: UUID,
        startedAt: Instant,
    ) {
        val charCount =
            jdbc
                .sql("SELECT char_count FROM documents WHERE id = :documentId AND user_id = :ownerId")
                .param("documentId", job.documentId)
                .param("ownerId", job.ownerId)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        writer.start(job.toLedgerJob(), executionId, startedAt, charCount)
    }

    override fun complete(
        job: StoredActionGuideJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) = writer.complete(job.jobId, executionId, record)

    override fun markOutcomeUnknown(
        job: StoredActionGuideJob,
        executionId: UUID,
        recoveredAt: Instant,
    ) {
        writer.markOutcomeUnknown(job.jobId, executionId)
        @Suppress("UNUSED_VARIABLE")
        val ignoredRecoveryTime = recoveredAt
    }

    private companion object {
        val START_SQL: String =
            """
            INSERT INTO llm_calls
                (id, conversion_id, document_id, workspace_id, user_id, purpose,
                 provider, model, input_tokens, output_tokens, latency_ms,
                 estimated_cost_usd, pricing_input_usd_per_mtok, pricing_output_usd_per_mtok,
                 char_count, document_char_count, called_at, outcome, failure_class,
                 action_guide_job_id)
            VALUES
                (:id, :conversionId, :documentId, :workspaceId, :userId, 'action_guide',
                 NULL, NULL, 0, 0, NULL, NULL, NULL, NULL,
                 :charCount, :charCount, :calledAt, 'in_progress', NULL, :jobId)
            """.trimIndent()

        val COMPLETE_SQL: String =
            """
            UPDATE llm_calls
            SET provider = :provider, model = :model,
                input_tokens = :inputTokens, output_tokens = :outputTokens,
                latency_ms = :latencyMs, estimated_cost_usd = :estimatedCost,
                pricing_input_usd_per_mtok = :inputPrice,
                pricing_output_usd_per_mtok = :outputPrice,
                char_count = :charCount, called_at = :calledAt,
                outcome = :outcome, failure_class = :failureClass
            WHERE id = :id AND action_guide_job_id = :jobId AND outcome = 'in_progress'
            """.trimIndent()

        val MARK_OUTCOME_UNKNOWN_SQL: String =
            """
            UPDATE llm_calls
            SET outcome = 'outcome_unknown', failure_class = NULL
            WHERE id = :id AND action_guide_job_id = :jobId AND outcome = 'in_progress'
            """.trimIndent()
    }
}

private fun StoredActionGuideJob.toLedgerJob(): JdbcFeatureLlmCallLedgerJob =
    JdbcFeatureLlmCallLedgerJob(
        jobId = jobId,
        conversionId = conversionId,
        documentId = documentId,
        workspaceId = workspaceId,
        ownerId = ownerId,
    )

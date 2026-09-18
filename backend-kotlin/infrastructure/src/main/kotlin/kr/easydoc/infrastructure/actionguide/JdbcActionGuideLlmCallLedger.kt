package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideLlmCallLedger
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.llm.LlmCallRecord
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** provider 시작 전 in_progress를 쓰고 동일 행을 terminal outcome으로 바꾼다. */
class JdbcActionGuideLlmCallLedger(private val jdbc: JdbcClient) : ActionGuideLlmCallLedger {
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
        jdbc
            .sql(START_SQL)
            .param("id", executionId)
            .param("jobId", job.jobId)
            .param("conversionId", job.conversionId)
            .param("documentId", job.documentId)
            .param("workspaceId", job.workspaceId)
            .param("userId", job.ownerId)
            .param("charCount", charCount)
            .param("calledAt", utc(startedAt))
            .update()
    }

    override fun complete(
        job: StoredActionGuideJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) {
        val updated =
            jdbc
                .sql(COMPLETE_SQL)
                .param("id", executionId)
                .param("jobId", job.jobId)
                .param("provider", record.provider)
                .param("model", record.model)
                .param("inputTokens", record.inputTokens)
                .param("outputTokens", record.outputTokens)
                .param("latencyMs", record.latencyMs)
                .param("estimatedCost", record.estimatedCostUsd)
                .param("inputPrice", record.pricingInputUsdPerMtok)
                .param("outputPrice", record.pricingOutputUsdPerMtok)
                .param("charCount", record.charCount)
                .param("calledAt", utc(record.calledAt))
                .param("outcome", record.outcome.wireName)
                .param("failureClass", record.failureClass?.take(FAILURE_CLASS_MAX_LENGTH))
                .update()
        check(updated == 1) { "행동 안내문 LLM 호출 원장을 완료할 수 없습니다" }
    }

    override fun markOutcomeUnknown(
        job: StoredActionGuideJob,
        executionId: UUID,
        recoveredAt: Instant,
    ) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE llm_calls
                    SET outcome = 'outcome_unknown', failure_class = NULL
                    WHERE id = :id AND action_guide_job_id = :jobId AND outcome = 'in_progress'
                    """.trimIndent(),
                ).param("id", executionId)
                .param("jobId", job.jobId)
                .update()
        check(updated == 1) { "행동 안내문 LLM 호출 원장을 불명확 상태로 바꿀 수 없습니다" }
        @Suppress("UNUSED_VARIABLE")
        val ignoredRecoveryTime = recoveredAt
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val FAILURE_CLASS_MAX_LENGTH: Int = 64

        val START_SQL =
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

        val COMPLETE_SQL =
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
    }
}

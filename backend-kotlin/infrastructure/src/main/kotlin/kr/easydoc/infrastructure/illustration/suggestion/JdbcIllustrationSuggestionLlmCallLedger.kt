package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionLlmCallLedger
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerJob
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerSql
import kr.easydoc.infrastructure.llm.JdbcFeatureLlmCallLedgerWriter
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/** provider 시작 전 in_progress 를 쓰고 동일 행을 terminal outcome 으로 바꾼다(V35). */
class JdbcIllustrationSuggestionLlmCallLedger(private val jdbc: JdbcClient) : IllustrationSuggestionLlmCallLedger {
    private val writer =
        JdbcFeatureLlmCallLedgerWriter(
            jdbc = jdbc,
            sql =
                JdbcFeatureLlmCallLedgerSql(
                    start = START_SQL,
                    complete = COMPLETE_SQL,
                    markOutcomeUnknown = MARK_OUTCOME_UNKNOWN_SQL,
                ),
            completionFailureMessage = "그림 제안 LLM 호출 원장을 완료할 수 없습니다",
            unknownFailureMessage = "그림 제안 LLM 호출 원장을 불명확 상태로 바꿀 수 없습니다",
        )

    override fun start(
        job: StoredIllustrationSuggestionJob,
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
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
        record: LlmCallRecord,
    ) = writer.complete(job.jobId, executionId, record)

    override fun markOutcomeUnknown(
        job: StoredIllustrationSuggestionJob,
        executionId: UUID,
    ) = writer.markOutcomeUnknown(job.jobId, executionId)

    private companion object {
        val START_SQL: String =
            """
            INSERT INTO llm_calls
                (id, conversion_id, document_id, workspace_id, user_id, purpose,
                 provider, model, input_tokens, output_tokens, latency_ms,
                 estimated_cost_usd, pricing_input_usd_per_mtok, pricing_output_usd_per_mtok,
                 char_count, document_char_count, called_at, outcome, failure_class,
                 illustration_suggestion_job_id)
            VALUES
                (:id, :conversionId, :documentId, :workspaceId, :userId, 'illustration_suggestion',
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
            WHERE id = :id AND illustration_suggestion_job_id = :jobId AND outcome = 'in_progress'
            """.trimIndent()

        val MARK_OUTCOME_UNKNOWN_SQL: String =
            """
            UPDATE llm_calls
            SET outcome = 'outcome_unknown', failure_class = NULL
            WHERE id = :id AND illustration_suggestion_job_id = :jobId AND outcome = 'in_progress'
            """.trimIndent()
    }
}

private fun StoredIllustrationSuggestionJob.toLedgerJob(): JdbcFeatureLlmCallLedgerJob =
    JdbcFeatureLlmCallLedgerJob(
        jobId = jobId,
        conversionId = conversionId,
        documentId = documentId,
        workspaceId = workspaceId,
        ownerId = ownerId,
    )

package kr.easydoc.infrastructure.llm

import kr.easydoc.core.llm.LlmCallRecord
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 두 기능별 원장 adapter가 공유하는 JDBC 값 매핑과 결과 전이 writer. */
internal class JdbcFeatureLlmCallLedgerWriter(
    private val jdbc: JdbcClient,
    private val sql: JdbcFeatureLlmCallLedgerSql,
    private val completionFailureMessage: String,
    private val unknownFailureMessage: String,
) {
    fun start(
        job: JdbcFeatureLlmCallLedgerJob,
        executionId: UUID,
        startedAt: Instant,
        charCount: Int,
    ) {
        jdbc
            .sql(sql.start)
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

    fun complete(
        jobId: UUID,
        executionId: UUID,
        record: LlmCallRecord,
    ) {
        val updated =
            jdbc
                .sql(sql.complete)
                .param("id", executionId)
                .param("jobId", jobId)
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
        check(updated == 1) { completionFailureMessage }
    }

    fun markOutcomeUnknown(
        jobId: UUID,
        executionId: UUID,
    ) {
        val updated =
            jdbc
                .sql(sql.markOutcomeUnknown)
                .param("id", executionId)
                .param("jobId", jobId)
                .update()
        check(updated == 1) { unknownFailureMessage }
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val FAILURE_CLASS_MAX_LENGTH: Int = 64
    }
}

internal data class JdbcFeatureLlmCallLedgerSql(
    val start: String,
    val complete: String,
    val markOutcomeUnknown: String,
)

internal data class JdbcFeatureLlmCallLedgerJob(
    val jobId: UUID,
    val conversionId: UUID,
    val documentId: UUID,
    val workspaceId: UUID,
    val ownerId: UUID,
)

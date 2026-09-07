package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.LlmCallEntry
import kr.easydoc.application.conversion.LlmCallLedger
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `llm_calls` 원장 어댑터 — 여러 행을 **한 INSERT 문**(값 그룹을 늘린 다중 VALUES)으로
 * 한 번에 쓴다. 변환 1건이 최대 2행(1차·조건부 보정)을 완료 저장과 같은 트랜잭션에서
 * 쓰므로(`ProcessConversionJob.finishSuccess`), 왕복을 나누면 그 사이에 절반만 커밋되는
 * 창이 생긴다 — 이 문 하나가 원자적이다.
 *
 * **이 표는 [kr.easydoc.core.crypto.EncryptedField] 밖이다.** `llm_calls` 가 담는 열은
 * 숫자와 이름(provider·model 식별자)뿐이라 암호화 대상이 아니다(V12 머리주석) — 그래서
 * `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`(둘 다
 * [kr.easydoc.core.crypto.EncryptedField] 가 아는 표만 훑는다) 인구조사 대상도 아니다.
 */
class JdbcLlmCallLedger(private val jdbc: JdbcClient) : LlmCallLedger {
    override fun append(entries: List<LlmCallEntry>) {
        if (entries.isEmpty()) return
        val values = entries.indices.joinToString(",\n") { rowPlaceholders(it) }
        var spec = jdbc.sql("$INSERT_PREFIX\n$values")
        entries.forEachIndexed { index, entry -> spec = bind(spec, index, entry) }
        spec.update()
    }

    private fun rowPlaceholders(index: Int): String =
        "(:id$index, :conversionId$index, :documentId$index, :workspaceId$index, :userId$index, " +
            ":purpose$index, :provider$index, :model$index, :inputTokens$index, :outputTokens$index, " +
            ":latencyMs$index, :estimatedCostUsd$index, :pricingInput$index, :pricingOutput$index, " +
            ":charCount$index, :calledAt$index)"

    private fun bind(
        spec: JdbcClient.StatementSpec,
        index: Int,
        entry: LlmCallEntry,
    ): JdbcClient.StatementSpec =
        spec
            .param("id$index", UUID.randomUUID())
            .param("conversionId$index", entry.conversionId)
            .param("documentId$index", entry.documentId)
            .param("workspaceId$index", entry.workspaceId)
            .param("userId$index", entry.userId)
            .param("purpose$index", entry.record.purpose.wireName)
            .param("provider$index", entry.record.provider)
            .param("model$index", entry.record.model)
            .param("inputTokens$index", entry.record.inputTokens)
            .param("outputTokens$index", entry.record.outputTokens)
            .param("latencyMs$index", entry.record.latencyMs)
            .param("estimatedCostUsd$index", entry.record.estimatedCostUsd)
            .param("pricingInput$index", entry.record.pricingInputUsdPerMtok)
            .param("pricingOutput$index", entry.record.pricingOutputUsdPerMtok)
            .param("charCount$index", entry.record.charCount)
            // 읽는 쪽 관례(`rs.getObject(..., OffsetDateTime::class.java).toInstant()`)와
            // 짝을 맞춘다 — 이 표의 유일한 쓰기 경로라 `java.sql.Timestamp` 대신 이 표현으로
            // 통일해도 다른 어댑터와 부딪히지 않는다.
            .param("calledAt$index", OffsetDateTime.ofInstant(entry.calledAt, ZoneOffset.UTC))

    private companion object {
        val INSERT_PREFIX =
            """
            INSERT INTO llm_calls (
                id, conversion_id, document_id, workspace_id, user_id,
                purpose, provider, model, input_tokens, output_tokens,
                latency_ms, estimated_cost_usd, pricing_input_usd_per_mtok, pricing_output_usd_per_mtok,
                char_count, called_at
            ) VALUES
            """.trimIndent()
    }
}

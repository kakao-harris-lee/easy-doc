package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionSuccessWrite
import kr.easydoc.application.conversion.ConversionUsage
import kr.easydoc.application.conversion.ConversionWorkItem
import kr.easydoc.application.conversion.ConversionWorkStore
import kr.easydoc.application.conversion.LlmAttribution
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.ConversionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** worker 가 `conversions`·`documents` 를 읽고 결과를 쓴다. */
class JdbcConversionWorkStore(private val jdbc: JdbcClient) : ConversionWorkStore {
    override fun loadForProcessing(conversionId: UUID): ConversionWorkItem? =
        jdbc
            .sql(
                """
                SELECT c.id, c.document_id, c.status, c.credits_reserved,
                       d.source_text_encrypted, d.encryption_scheme, d.key_version,
                       d.workspace_id, d.user_id, d.char_count, d.source_unit_kinds
                FROM conversions c
                JOIN documents d ON d.id = c.document_id
                WHERE c.id = :id
                FOR NO KEY UPDATE OF c, d
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ ->
                val documentId = rs.getObject("document_id", UUID::class.java)
                ConversionWorkItem(
                    conversionId = rs.getObject("id", UUID::class.java),
                    documentId = documentId,
                    status = ConversionStatus.ofWireName(rs.getString("status")),
                    sourceText =
                        EncryptedContent(
                            bytes = rs.getBytes("source_text_encrypted"),
                            scheme = rs.getString("encryption_scheme"),
                            keyVersion = rs.getInt("key_version"),
                        ),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    charCount = rs.getInt("char_count"),
                    // null 은 이 조각 이전에 만든 문서이거나(계획 §1.2) 저장된 값이 손상됐다는
                    // 뜻이다(리뷰 BLOCK 1) — 두 경우 모두 ProcessConversionJob 이
                    // SourceStructure.allBody 로 접어서 쓴다.
                    structure = rs.getString("source_unit_kinds")?.let { decodeStructureOrNull(it, documentId) },
                    creditsReserved = rs.getInt("credits_reserved"),
                )
            }.optional()
            .orElse(null)

    override fun markProcessing(conversionId: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = :processing, updated_at = now()
                WHERE id = :id AND (status = :pending OR status = :processing)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("processing", ConversionStatus.PROCESSING.wireName)
            .param("pending", ConversionStatus.PENDING.wireName)
            .update() > 0

    override fun saveSuccess(
        conversionId: UUID,
        write: ConversionSuccessWrite,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = :done,
                    easy_text_encrypted = :easyText,
                    encryption_scheme = :scheme,
                    key_version = :keyVersion,
                    model = :model,
                    provider_name = :providerName,
                    input_tokens = :inputTokens,
                    output_tokens = :outputTokens,
                    failure_code = NULL,
                    updated_at = now()
                WHERE id = :id AND (status = :pending OR status = :processing)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("done", ConversionStatus.DONE.wireName)
            .param("pending", ConversionStatus.PENDING.wireName)
            .param("processing", ConversionStatus.PROCESSING.wireName)
            .param("easyText", write.easyText.bytes)
            .param("scheme", write.easyText.scheme)
            .param("keyVersion", write.easyText.keyVersion)
            .param("model", write.attribution.model)
            .param("providerName", write.attribution.providerName)
            .param("inputTokens", write.usage.inputTokens)
            .param("outputTokens", write.usage.outputTokens)
            .update() > 0

    override fun saveFailure(
        conversionId: UUID,
        failureCode: String,
        usage: ConversionUsage,
        attribution: LlmAttribution,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = :failed,
                    failure_code = :failureCode,
                    provider_name = :providerName,
                    model = :model,
                    input_tokens = :inputTokens,
                    output_tokens = :outputTokens,
                    updated_at = now()
                WHERE id = :id AND (status = :pending OR status = :processing)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("failed", ConversionStatus.FAILED.wireName)
            .param("pending", ConversionStatus.PENDING.wireName)
            .param("processing", ConversionStatus.PROCESSING.wireName)
            .param("failureCode", failureCode.take(FAILURE_CODE_MAX_LENGTH))
            .param("providerName", attribution.providerName)
            .param("model", attribution.model)
            .param("inputTokens", usage.inputTokens)
            .param("outputTokens", usage.outputTokens)
            .update() > 0

    /** 리뷰 HIGH-1 — CAS 로 이 변환의 예약을 한 번만 정산 대상으로 넘긴다. */
    override fun settleCreditsReserved(
        conversionId: UUID,
        expectedAmount: Int,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET credits_reserved = 0
                WHERE id = :id AND credits_reserved = :expectedAmount
                """.trimIndent(),
            ).param("id", conversionId)
            .param("expectedAmount", expectedAmount)
            .update() > 0

    override fun revertToPending(conversionId: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = :pending, updated_at = now()
                WHERE id = :id AND status = :processing
                """.trimIndent(),
            ).param("id", conversionId)
            .param("pending", ConversionStatus.PENDING.wireName)
            .param("processing", ConversionStatus.PROCESSING.wireName)
            .update() > 0

    private companion object {
        /** 계약 `ConversionResponse.failure_code.maxLength`. */
        const val FAILURE_CODE_MAX_LENGTH: Int = 64
    }
}

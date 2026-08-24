package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionSuccessWrite
import kr.easydoc.application.conversion.ConversionUsage
import kr.easydoc.application.conversion.ConversionWorkItem
import kr.easydoc.application.conversion.ConversionWorkStore
import kr.easydoc.application.conversion.LlmAttribution
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.ConversionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** worker 가 `conversions`·`documents` 를 읽고 결과를 쓴다. */
class JdbcConversionWorkStore(private val jdbc: JdbcClient) : ConversionWorkStore {
    private val json = JsonMapper.builder().build()

    override fun loadForProcessing(conversionId: UUID): ConversionWorkItem? =
        jdbc
            .sql(
                """
                SELECT c.id, c.document_id, c.status,
                       d.source_text_encrypted, d.encryption_scheme, d.key_version
                FROM conversions c
                JOIN documents d ON d.id = c.document_id
                WHERE c.id = :id
                FOR NO KEY UPDATE OF c, d
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ ->
                ConversionWorkItem(
                    conversionId = rs.getObject("id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    status = ConversionStatus.ofWireName(rs.getString("status")),
                    sourceText =
                        EncryptedContent(
                            bytes = rs.getBytes("source_text_encrypted"),
                            scheme = rs.getString("encryption_scheme"),
                            keyVersion = rs.getInt("key_version"),
                        ),
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
                    masked_items_encrypted = :maskedItems,
                    missing_placeholders = CAST(:placeholders AS jsonb),
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
            .param("maskedItems", write.maskedItems.bytes)
            .param("placeholders", labelsJson(write.missingPlaceholders))
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

    private fun labelsJson(labels: List<String>): String {
        val array = json.createArrayNode()
        labels.forEach { array.add(it) }
        return json.writeValueAsString(array)
    }

    private companion object {
        /** 계약 `ConversionResponse.failure_code.maxLength`. */
        const val FAILURE_CODE_MAX_LENGTH: Int = 64
    }
}

package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import org.springframework.jdbc.core.simple.JdbcClient

/** 원문과 현재 저장 본문을 같은 content_revision 에서 읽는다. 평문은 로그에 남기지 않는다. */
class IllustrationSuggestionGenerationInput(
    val sourceText: String,
    val savedBody: String,
) {
    override fun toString(): String =
        "IllustrationSuggestionGenerationInput(source=${sourceText.length}자, body=${savedBody.length}자)"
}

fun interface IllustrationSuggestionInputSource {
    fun load(job: StoredIllustrationSuggestionJob): IllustrationSuggestionGenerationInput?
}

/** provider 시작 뒤에도 소유권·보존 기간·본문 버전을 다시 확인하는 조회 전용 어댑터. */
class JdbcIllustrationSuggestionInputSource(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : IllustrationSuggestionInputSource {
    override fun load(job: StoredIllustrationSuggestionJob): IllustrationSuggestionGenerationInput? =
        jdbc
            .sql(INPUT_SQL)
            .param("conversionId", job.conversionId)
            .param("documentId", job.documentId)
            .param("ownerId", job.ownerId)
            .param("revision", job.basedOnContentRevision)
            .query { rs, _ ->
                val source =
                    EncryptedContent(
                        rs.getBytes("source_text_encrypted"),
                        rs.getString("source_scheme"),
                        rs.getInt("source_key_version"),
                    )
                val edited = rs.getBytes("edited_text_encrypted")
                val body = edited ?: rs.getBytes("easy_text_encrypted")
                checkNotNull(body) { "완료된 변환에 저장 본문이 없습니다" }
                val bodyField =
                    if (edited != null) EncryptedField.CONVERSION_EDITED_TEXT else EncryptedField.CONVERSION_EASY_TEXT
                IllustrationSuggestionGenerationInput(
                    sourceText =
                        cipher.decrypt(source, job.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value,
                    savedBody =
                        cipher
                            .decrypt(
                                EncryptedContent(body, rs.getString("body_scheme"), rs.getInt("body_key_version")),
                                job.conversionId,
                                bodyField,
                            ).value,
                )
            }.optional()
            .orElse(null)

    private companion object {
        val INPUT_SQL =
            """
            SELECT d.source_text_encrypted, d.encryption_scheme AS source_scheme,
                   d.key_version AS source_key_version,
                   c.easy_text_encrypted, c.edited_text_encrypted,
                   c.encryption_scheme AS body_scheme, c.key_version AS body_key_version
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :conversionId AND d.id = :documentId AND d.user_id = :ownerId
              AND d.retention_expires_at > now() AND c.status = 'done'
              AND c.content_revision = :revision
            """.trimIndent()
    }
}

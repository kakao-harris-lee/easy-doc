package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `conversions` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` + `V3`·`V4` 가 정한다. */
class JdbcConversionRepository(private val jdbc: JdbcClient) : ConversionRepository {
    override fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion =
        try {
            jdbc
                .sql(INSERT_PENDING_SQL)
                .param("id", id)
                .param("documentId", documentId)
                .param("status", ConversionStatus.PENDING.wireName)
                .param("scheme", scheme)
                .param("keyVersion", keyVersion)
                .query { rs, _ -> toConversion(rs) }
                .single()
        } catch (failure: DataIntegrityViolationException) {
            DocumentStorageLog.constraintViolation("conversions", failure)
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }

    /**
     * 암호문 세 열과 봉투를 읽고 **행을 잠근다**(`FOR NO KEY UPDATE`).
     *
     * 잠금 모드를 고른 사유는 [JdbcDocumentRepository.lockSourceText] KDoc 에 있다 —
     * 여기서는 `conversion_jobs.conversion_id` 가 이 행을 참조하므로, `FOR UPDATE` 로 잡으면
     * 회전이 도는 동안 작업 등록의 외래 키 검사까지 멈춘다.
     */
    override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? =
        jdbc
            .sql(
                """
                SELECT id, easy_text_encrypted, masked_items_encrypted, edited_text_encrypted,
                       encryption_scheme, key_version
                FROM conversions WHERE id = :id
                FOR NO KEY UPDATE
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ -> toEnvelope(rs) }
            .optional()
            .orElse(null)

    /** 세 열과 봉투 두 값을 **한 UPDATE 로** 바꾼다. */
    override fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE conversions
                SET easy_text_encrypted = :easyText,
                    masked_items_encrypted = :maskedItems,
                    edited_text_encrypted = :editedText,
                    encryption_scheme = :scheme,
                    key_version = :keyVersion
                WHERE id = :id
                  AND encryption_scheme = :expectedScheme
                  AND key_version = :expectedKeyVersion
                  AND easy_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEasyText AS bytea)
                  AND masked_items_encrypted IS NOT DISTINCT FROM CAST(:expectedMaskedItems AS bytea)
                  AND edited_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEditedText AS bytea)
                """.trimIndent(),
            ).param("easyText", ciphertexts.easyText?.bytes)
            .param("maskedItems", ciphertexts.maskedItems?.bytes)
            .param("editedText", ciphertexts.editedText?.bytes)
            .param("scheme", scheme)
            .param("keyVersion", keyVersion)
            .param("id", expected.conversionId)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedEasyText", expected.ciphertexts.easyText?.bytes)
            .param("expectedMaskedItems", expected.ciphertexts.maskedItems?.bytes)
            .param("expectedEditedText", expected.ciphertexts.editedText?.bytes)
            .update() > 0

    private fun toConversion(rs: ResultSet): Conversion =
        Conversion(
            id = rs.getObject("id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            status = ConversionStatus.ofWireName(rs.getString("status")),
            failureCode = rs.getString("failure_code"),
            // timestamptz 를 OffsetDateTime 으로 받는다. `getTimestamp` 는 JVM 기본 시간대를
            // 끼워 넣어 서버 시간대에 따라 값이 달라진다.
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun toEnvelope(rs: ResultSet): ConversionEnvelope {
        val scheme = rs.getString("encryption_scheme")
        val keyVersion = rs.getInt("key_version")
        return ConversionEnvelope(
            conversionId = rs.getObject("id", UUID::class.java),
            scheme = scheme,
            keyVersion = keyVersion,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = sealedOrNull(rs, "easy_text_encrypted", scheme, keyVersion),
                    maskedItems = sealedOrNull(rs, "masked_items_encrypted", scheme, keyVersion),
                    editedText = sealedOrNull(rs, "edited_text_encrypted", scheme, keyVersion),
                ),
        )
    }

    /** 열 하나를 봉투와 묶어 읽는다. NULL 이면 `null` — **빈 바이트 배열로 바꾸지 않는다.** */
    private fun sealedOrNull(
        rs: ResultSet,
        column: String,
        scheme: String,
        keyVersion: Int,
    ): EncryptedContent? = rs.getBytes(column)?.let { EncryptedContent(it, scheme, keyVersion) }

    private companion object {
        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"

        val INSERT_PENDING_SQL =
            """
            INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version)
            VALUES (:id, :documentId, :status, :scheme, :keyVersion)
            RETURNING id, document_id, status, failure_code, created_at, updated_at
            """.trimIndent()
    }
}

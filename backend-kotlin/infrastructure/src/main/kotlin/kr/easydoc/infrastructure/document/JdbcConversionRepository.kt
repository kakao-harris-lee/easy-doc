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

/**
 * `conversions` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` + `V3`·`V4` 가 정한다.
 *
 * ## 대기 중 변환도 **봉투 두 값을 적는다**
 *
 * 암호문 세 열이 전부 NULL 인 채로 태어나지만 `encryption_scheme`·`key_version` 은 적힌다.
 * `V3` 주석의 판단 그대로다 — *"암호문이 아직 없는 행도 앞으로 쓸 세대를 적어 두는 편이,
 * 나중에 NULL 을 해석하는 규칙을 만드는 것보다 낫다."* 그리고 `V3` 가 DEFAULT 를 없앴으므로
 * **빠뜨리면 NOT NULL 위반으로 즉시 실패한다.**
 *
 * `missing_placeholders` 는 DEFAULT `'[]'` 를 그대로 쓴다. 봉투 두 값과 달리 이 기본값은
 * 데이터에 대해 **참**이기 때문이다 — 아직 변환하지 않은 행에 유실된 자리표시자는 없다.
 *
 * ## 예외 처리는 [JdbcDocumentRepository] 와 같은 규약이다
 *
 * 원인 체인을 끊고 로그에는 SQLSTATE 만 남긴다 — 변환 행의 제약 위반 `DETAIL` 에는
 * **암호문 세 열이 통째로** 실린다.
 */
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

    override fun loadEnvelope(conversionId: UUID): ConversionEnvelope? =
        jdbc
            .sql(
                """
                SELECT id, easy_text_encrypted, masked_items_encrypted, edited_text_encrypted,
                       encryption_scheme, key_version
                FROM conversions WHERE id = :id
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ -> toEnvelope(rs) }
            .optional()
            .orElse(null)

    /**
     * 세 열과 봉투 두 값을 **한 UPDATE 로** 바꾼다.
     *
     * 열별 갱신 메서드를 두지 않는 것이 이 설계의 요점이다 — 두 문장으로 나누면 "세대는 v2
     * 인데 한 열은 v1 암호문" 인 중간 상태가 생기고 그 행은 영원히 열리지 않는다. 그 성질을
     * 재는 것은 문장 수 계측이다(`CountingDataSource`).
     *
     * `updated_at` 을 건드리지 않는다 — 재암호화는 내용의 변경이 아니다. 사유는 포트 KDoc.
     */
    override fun rewriteEnvelope(
        conversionId: UUID,
        expectedKeyVersion: Int,
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
                WHERE id = :id AND key_version = :expectedKeyVersion
                """.trimIndent(),
            ).param("easyText", ciphertexts.easyText?.bytes)
            .param("maskedItems", ciphertexts.maskedItems?.bytes)
            .param("editedText", ciphertexts.editedText?.bytes)
            .param("scheme", scheme)
            .param("keyVersion", keyVersion)
            .param("id", conversionId)
            .param("expectedKeyVersion", expectedKeyVersion)
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

    /**
     * 열 하나를 봉투와 묶어 읽는다. NULL 이면 `null` — **빈 바이트 배열로 바꾸지 않는다.**
     *
     * 「값이 없다」와 「빈 값이다」를 섞으면 회전이 없던 내용을 지어내게 된다.
     */
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

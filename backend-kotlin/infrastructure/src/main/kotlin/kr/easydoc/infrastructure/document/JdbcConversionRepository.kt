package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `conversions` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` + `V3`·`V4` 가 정한다. */
class JdbcConversionRepository(private val jdbc: JdbcClient) : ConversionRepository {
    /**
     * `missing_placeholders` 의 `jsonb` 를 읽는 데만 쓴다. [MaskedItemCodec] 의 것과 인스턴스를
     * 공유하지 않는 이유는 [placeholderLabels] KDoc — 저쪽은 **봉인 대상**의 코덱이다.
     */
    private val json = JsonMapper.builder().build()

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

    /** **내** 변환 한 건을 읽는다 — 조인과 소유 술어가 **한 문장** 안에 있다. */
    override fun findOwnedResult(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredConversion? =
        jdbc
            .sql(FIND_OWNED_SQL)
            .param("id", conversionId)
            .param("ownerId", ownerId)
            .query { rs, _ -> toStored(rs) }
            .optional()
            .orElse(null)

    /** 암호문 세 열과 봉투를 읽고 **행을 잠근다**(`FOR NO KEY UPDATE`). */
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

    /** 조회 한 행을 [StoredConversion] 으로 옮긴다. **복호화하지 않는다** — 그것은 유스케이스다. */
    private fun toStored(rs: ResultSet): StoredConversion {
        val scheme = rs.getString("encryption_scheme")
        val keyVersion = rs.getInt("key_version")
        return StoredConversion(
            id = rs.getObject("id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            status = ConversionStatus.ofWireName(rs.getString("status")),
            ciphertexts =
                ConversionCiphertexts(
                    easyText = sealedOrNull(rs, "easy_text_encrypted", scheme, keyVersion),
                    maskedItems = sealedOrNull(rs, "masked_items_encrypted", scheme, keyVersion),
                    editedText = sealedOrNull(rs, "edited_text_encrypted", scheme, keyVersion),
                ),
            reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
            missingPlaceholders = placeholderLabels(rs.getString("missing_placeholders")),
            model = rs.getString("model"),
            providerName = rs.getString("provider_name"),
            inputTokens = rs.getObject("input_tokens", Int::class.javaObjectType),
            outputTokens = rs.getObject("output_tokens", Int::class.javaObjectType),
            failureCode = rs.getString("failure_code"),
        )
    }

    /** `missing_placeholders` 의 `jsonb` 값을 라벨 목록으로 읽는다. */
    private fun placeholderLabels(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val root =
            try {
                json.readTree(raw)
            } catch (exc: JacksonException) {
                throw malformedPlaceholders(exc::class.java.simpleName)
            }
        // `JsonNode.values()` 를 쓰는 이유는 [MaskedItemCodec.decode] 와 같다 — Jackson 3 의
        // `JsonNode.map(Function)` 이 Kotlin 의 `Iterable.map` 을 가린다.
        //
        // 판정을 **한 자리에 모은다.** 갈래마다 `throw` 를 쓰면 detekt `ThrowsCount` 가 울리고,
        // 그 규칙이 옳게 가리키는 것은 「실패 경로가 흩어져 있다」다 — 사유 토큰만 다르므로
        // 하나로 접는 편이 읽기도 낫다.
        val reason =
            when {
                !root.isArray -> "not-an-array"
                root.values().any { !it.isString } -> "element-not-a-string"
                else -> null
            }
        if (reason != null) throw malformedPlaceholders(reason)
        return root.values().map { it.stringValue("") }
    }

    private fun malformedPlaceholders(reason: String): StorageException {
        DocumentStorageLog.malformedStoredValue(MISSING_PLACEHOLDERS_COLUMN, reason)
        return StorageException(UNREADABLE_RESULT_MESSAGE)
    }

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

        /**
         * 저장된 값이 우리 형식이 아닐 때의 문구. 계약 `InternalError` 의 다른 갈래이고
         * [MaskedItemCodec] 이 쓰는 것과 **같은 문자열**이다 — 두 값 모두 「저장된 변환
         * 결과를 읽지 못했다」는 같은 사건이므로 사용자에게 갈리는 안내를 주지 않는다.
         */
        const val UNREADABLE_RESULT_MESSAGE = "저장된 변환 결과를 읽을 수 없습니다"

        private const val MISSING_PLACEHOLDERS_COLUMN = "conversions.missing_placeholders"

        /** 조회 질의. **소유 술어가 조인 위에 있다.** */
        val FIND_OWNED_SQL =
            """
            SELECT c.id, d.id AS document_id, c.status,
                   c.easy_text_encrypted, c.masked_items_encrypted, c.edited_text_encrypted,
                   c.encryption_scheme, c.key_version,
                   c.reviewed_at, c.missing_placeholders,
                   c.model, c.provider_name, c.input_tokens, c.output_tokens, c.failure_code
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :id AND d.user_id = :ownerId
            """.trimIndent()

        val INSERT_PENDING_SQL =
            """
            INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version)
            VALUES (:id, :documentId, :status, :scheme, :keyVersion)
            RETURNING id, document_id, status, failure_code, created_at, updated_at
            """.trimIndent()
    }
}

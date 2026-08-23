package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.LockedConversion
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
                .query { rs, _ -> ConversionRows.toConversion(rs) }
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
            .query { rs, _ -> ConversionRows.toStored(rs) }
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
            .query { rs, _ -> ConversionRows.toEnvelope(rs) }
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

    /** **내** 변환을 읽고 잠근다. 소유 술어가 조인 위에 있다. */
    override fun lockOwnedForReview(
        ownerId: UUID,
        conversionId: UUID,
    ): LockedConversion? =
        jdbc
            .sql(LOCK_OWNED_FOR_REVIEW_SQL)
            .param("id", conversionId)
            .param("ownerId", ownerId)
            .query { rs, _ -> ConversionRows.toLocked(rs) }
            .optional()
            .orElse(null)

    /** 검수본·검수 시각·봉투를 **한 UPDATE 로**. 검수 시각은 **DB 시계**다. */
    override fun saveReview(
        ownerId: UUID,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
        updated: ConversionEnvelope,
    ): Boolean =
        jdbc
            .sql(SAVE_REVIEW_SQL)
            .param("easyText", updated.ciphertexts.easyText?.bytes)
            .param("maskedItems", updated.ciphertexts.maskedItems?.bytes)
            .param("editedText", updated.ciphertexts.editedText?.bytes)
            .param("scheme", updated.scheme)
            .param("keyVersion", updated.keyVersion)
            .param("id", expected.conversionId)
            .param("ownerId", ownerId)
            .param("requiredStatus", requiredStatus.wireName)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedEasyText", expected.ciphertexts.easyText?.bytes)
            .param("expectedMaskedItems", expected.ciphertexts.maskedItems?.bytes)
            .param("expectedEditedText", expected.ciphertexts.editedText?.bytes)
            .update() > 0

    private companion object {
        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"

        /** 검수 저장이 잠그는 질의. `OF c` 로 **변환 행만** 잠근다. */
        val LOCK_OWNED_FOR_REVIEW_SQL =
            """
            SELECT c.id, c.status, c.easy_text_encrypted, c.masked_items_encrypted, c.edited_text_encrypted,
                   c.encryption_scheme, c.key_version
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :id AND d.user_id = :ownerId
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        /**
         * 검수 저장 UPDATE. 봉투를 암호문과 **같은 문장에서** SET 한다. `WHERE` 의 상태·암호문
         * 조건은 잠금 아래에서 잉여로 보이지만, 잠금이 서지 않은 상태를 **0행으로** 드러내는
         * fail-closed 카나리다.
         */
        val SAVE_REVIEW_SQL =
            """
            UPDATE conversions
            SET easy_text_encrypted = :easyText,
                masked_items_encrypted = :maskedItems,
                edited_text_encrypted = :editedText,
                encryption_scheme = :scheme,
                key_version = :keyVersion,
                reviewed_at = now()
            WHERE id = :id
              AND document_id IN (SELECT id FROM documents WHERE user_id = :ownerId)
              AND status = :requiredStatus
              AND encryption_scheme = :expectedScheme
              AND key_version = :expectedKeyVersion
              AND easy_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEasyText AS bytea)
              AND masked_items_encrypted IS NOT DISTINCT FROM CAST(:expectedMaskedItems AS bytea)
              AND edited_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEditedText AS bytea)
            """.trimIndent()

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

/** `conversions` 행 → 도메인 타입 매핑. 접근과 매핑을 가른다. */
private object ConversionRows {
    /** `missing_placeholders` 를 읽을 때만 쓴다. 봉인 대상의 코덱과 인스턴스를 공유하지 않는다. */
    private val json = JsonMapper.builder().build()

    fun toConversion(rs: ResultSet): Conversion =
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
    fun toStored(rs: ResultSet): StoredConversion {
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

    /** 잠근 행 — 상태와 봉투. */
    fun toLocked(rs: ResultSet): LockedConversion =
        LockedConversion(ConversionStatus.ofWireName(rs.getString("status")), toEnvelope(rs))

    fun toEnvelope(rs: ResultSet): ConversionEnvelope {
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

    private const val MISSING_PLACEHOLDERS_COLUMN = "conversions.missing_placeholders"

    /** 저장된 값이 우리 형식이 아닐 때의 문구. [MaskedItemCodec] 이 쓰는 것과 **같은 문자열**이다. */
    private const val UNREADABLE_RESULT_MESSAGE = "저장된 변환 결과를 읽을 수 없습니다"
}

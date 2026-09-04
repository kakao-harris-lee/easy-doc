package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.LockedConversion
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.application.document.StoredExport
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `conversions` 테이블 접근. 스키마는 `V1__initial_schema.sql` 이 정한다. */
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

    /** **내** 변환과 문서 제목을 한 문장으로 읽는다. 소유 술어는 [findOwnedResult] 와 같다. */
    override fun findOwnedExport(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredExport? =
        jdbc
            .sql(FIND_OWNED_SQL)
            .param("id", conversionId)
            .param("ownerId", ownerId)
            .query { rs, _ -> StoredExport(ConversionRows.toStored(rs), rs.getString("title")) }
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

    /** 키 회전 배치의 후보. `id > :after` 로 커서를 넘긴다 — [ConversionRepository.idsOlderThan] KDoc. */
    override fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM conversions
                WHERE key_version < :keyVersion AND id > :after
                ORDER BY id ASC
                LIMIT :limit
                """.trimIndent(),
            ).param("keyVersion", keyVersion)
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

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

        /**
         * 검수 저장이 잠그는 질의. `OF c` 로 **변환 행만** 잠근다.
         *
         * **보존 기간 술어가 소유 술어와 같은 자리에 있다** — 만료된 문서의 변환은 여기서
         * 「없음」이 되고, 유스케이스가 그것을 404 로 옮긴다. 쓰기 경로라 특히 중요하다:
         * 파기 대상 문서에 새 검수본을 쓰게 두면 **다음 배치가 방금 쓴 내용을 지운다.**
         * 사유와 여집합 관계는 [FIND_OWNED_SQL] 에 적었다.
         */
        val LOCK_OWNED_FOR_REVIEW_SQL =
            """
            SELECT c.id, c.status, c.easy_text_encrypted, c.masked_items_encrypted, c.edited_text_encrypted,
                   c.encryption_scheme, c.key_version
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :id AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        /**
         * 검수 저장 UPDATE. 봉투를 암호문과 **같은 문장에서** SET 한다. `WHERE` 의 상태·암호문
         * 조건은 잠금 아래에서 잉여로 보이지만, 잠금이 서지 않은 상태를 **0행으로** 드러내는
         * fail-closed 카나리다.
         *
         * **보존 기간 술어도 소유 술어와 같은 자리에 함께 든다** — 같은 규칙이다(소유 술어가
         * 쓰기 문장 자신에도 걸리는 것과 같은 사유). 잠금 질의가 이미 걸렀으므로 이 조건이
         * 여기서 거짓이 되는 일은 없다: PostgreSQL 의 `now()` 는 `transaction_timestamp()` 라
         * **한 트랜잭션 안에서 고정**이고, 잠금과 이 UPDATE 는 같은 트랜잭션에 있다
         * (`ConversionReviewService.save`). 그래서 이 술어는 거짓 0행(=500)을 만들지 않고
         * 카나리로만 선다.
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
              AND document_id IN (
                  SELECT id FROM documents
                  WHERE user_id = :ownerId AND retention_expires_at > now()
              )
              AND status = :requiredStatus
              AND encryption_scheme = :expectedScheme
              AND key_version = :expectedKeyVersion
              AND easy_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEasyText AS bytea)
              AND masked_items_encrypted IS NOT DISTINCT FROM CAST(:expectedMaskedItems AS bytea)
              AND edited_text_encrypted IS NOT DISTINCT FROM CAST(:expectedEditedText AS bytea)
            """.trimIndent()

        /**
         * 조회 질의. **소유 술어가 조인 위에 있다.**
         *
         * `has_stored_original` 은 `EXISTS` 로 묻는다 — 원본은 최대 10MB 인데 조회가 알아야
         * 하는 것은 「있는가」 하나뿐이다. 조인으로 끌어오면 bytea 열이 결과 집합에 들어오고,
         * `LEFT JOIN` 으로 열 하나만 골라도 계획이 그 행을 집으러 간다. `EXISTS` 는
         * `document_originals` 의 기본 키를 한 번 찍고 끝난다(`V3__document_originals.sql`
         * 이 인덱스를 따로 두지 않은 사유와 같은 자리).
         *
         * 피드백은 **왼쪽 조인**이다 — 의견을 내지 않은 변환이 훨씬 많고, 그때 행이 사라지면
         * 조회가 404 가 된다. 봉인된 자유 의견 열은 **고르지 않는다**: 계약이 내보내는 것은
         * `submitted_at` 하나뿐이라 여기서 암호문을 끌어올 이유가 없다.
         * `f.user_id = d.user_id` 는 잉여로 보이지만 fail-closed 다 — 피드백 행의 제출자와
         * 문서 소유자가 갈린 행은 「없음」으로 접고, 그 시각이 남의 제출 사실을 드러내는
         * 경로가 되지 않게 한다.
         *
         * **보존 기간 술어가 소유 술어와 같은 자리에 있다.** `d.retention_expires_at > now()` 는
         * `JdbcExpiredDocumentPurge` 의 `retention_expires_at <= now()` 와 **정확한 여집합**이다
         * (`JdbcDocumentRepository.findOwnedSource` 와 같은 형태). 파기는 매일 03:00 배치 한
         * 번이라(`RetentionPurgeScheduler`) 만료와 파기 사이의 창이 **최대 24시간**이고, 이
         * 응답은 그 창에서 `masked_items[].original` 로 **가려졌던 실제 주민등록번호·카드번호를
         * 평문으로** 돌려준다(계약 `MaskedItemResponse`). 노출 크기는 원문 조회보다 작아도
         * **범주는 같다.**
         *
         * 이 질의를 조회와 내보내기가 **함께 쓴다** — 그래서 두 오퍼레이션이 한 술어로 닫힌다.
         * 만료가 「없음」·「타인」과 같은 갈래로 접히는 것도 의도다(존재 은폐).
         *
         * **목록(`JdbcDocumentRepository.listSql`)에는 걸지 않는다.** 사용자 결정이다 —
         * 목록은 제목만 싣고, 문서가 파기됐다는 사실을 사용자가 알아차리는 자리가 목록이다.
         * 거기서까지 소리 없이 사라지면 사용자는 이유를 알 수 없다
         * (`docs/kotlin-redevelopment-backlog.md` §1.1).
         */
        val FIND_OWNED_SQL =
            """
            SELECT c.id, d.id AS document_id, d.title, d.source_format, c.status,
                   EXISTS (
                       SELECT 1 FROM document_originals o WHERE o.document_id = d.id
                   ) AS has_stored_original,
                   c.easy_text_encrypted, c.masked_items_encrypted, c.edited_text_encrypted,
                   c.encryption_scheme, c.key_version,
                   c.reviewed_at, f.submitted_at AS feedback_submitted_at, c.missing_placeholders,
                   c.model, c.provider_name, c.input_tokens, c.output_tokens, c.failure_code
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            LEFT JOIN conversion_feedback f
                   ON f.conversion_id = c.id AND f.user_id = d.user_id
            WHERE c.id = :id AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
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
            sourceFormat = SourceFormat.ofWireName(rs.getString("source_format")),
            hasStoredOriginal = rs.getBoolean("has_stored_original"),
            ciphertexts =
                ConversionCiphertexts(
                    easyText = sealedOrNull(rs, "easy_text_encrypted", scheme, keyVersion),
                    maskedItems = sealedOrNull(rs, "masked_items_encrypted", scheme, keyVersion),
                    editedText = sealedOrNull(rs, "edited_text_encrypted", scheme, keyVersion),
                ),
            reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
            feedbackSubmittedAt = rs.getObject("feedback_submitted_at", OffsetDateTime::class.java)?.toInstant(),
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

package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.StoredSourceText
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `documents` 테이블 접근. 스키마는 `V1__initial_schema.sql` 이 정한다. */
class JdbcDocumentRepository(private val jdbc: JdbcClient) : DocumentRepository {
    override fun insert(
        ownerId: UUID,
        draft: DocumentDraft,
        sourceText: EncryptedContent,
    ): Document =
        try {
            jdbc
                .sql(INSERT_SQL)
                .param("id", draft.id)
                .param("ownerId", ownerId)
                .param("workspaceId", draft.workspaceId)
                .param("title", draft.title)
                .param("sourceFormat", draft.sourceFormat.wireName)
                .param("sourceText", sourceText.bytes)
                .param("scheme", sourceText.scheme)
                .param("keyVersion", sourceText.keyVersion)
                .param("charCount", draft.charCount)
                .query { rs, _ -> toDocument(rs) }
                .single()
        } catch (failure: DataIntegrityViolationException) {
            throw storageFailure(failure)
        }

    override fun listOwned(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing> {
        val statement =
            jdbc
                .sql(listSql(filterWorkspace = workspaceId != null))
                .param("ownerId", ownerId)
                .param("limit", limit)
                .param("offset", offset)
        if (workspaceId != null) statement.param("workspaceId", workspaceId)
        return statement.query { rs, _ -> toListing(rs) }.list()
    }

    /**
     * 내 문서의 원문을 읽는다. **소유 조건이 같은 문장 안에 있다** — 읽고 나서 비교하지 않는다.
     *
     * **보존 기간 술어도 같은 문장 안에 있다.** `retention_expires_at > now()` 는
     * `JdbcExpiredDocumentPurge` 의 `retention_expires_at <= now()` 와 **정확한 여집합**이고,
     * 그렇게 둔 것이 요점이다 — 파기는 워커 배치가 실제로 지울 때 일어나므로 만료 시각과 다음
     * 배치 사이에 창이 열린다. 그 창에서 이 오퍼레이션은 문서 **전문을 마스킹 전 상태로**
     * 돌려주므로(계약 `x-private-response-headers.applies_to`), 「30일 뒤 자동 삭제」
     * (master-plan §3.2)가 가장 날것인 데이터에서 먼저 깨진다. 두 술어가 여집합이 아니게
     * 되는 순간 그 창이 다시 생기거나(겹침) 이미 지운 행을 찾게 된다(틈).
     *
     * 만료를 「없음」과 같은 갈래로 접는 것도 의도다 — 있는지 없는지 구분되지 않아야 한다
     * (소유자 은폐와 같은 코드).
     *
     * 잠그지 않는다: 사용자 요청 경로이고 이 값은 문서 등록 시점에 확정돼 바뀌지 않는다
     * (바꾸는 것은 회전뿐이고, 그쪽은 [lockSourceText] 가 잠근다).
     */
    override fun findOwnedSource(
        ownerId: UUID,
        documentId: UUID,
    ): StoredSourceText? =
        jdbc
            .sql(
                """
                SELECT id, source_format, char_count,
                       source_text_encrypted, encryption_scheme, key_version
                FROM documents
                WHERE id = :id AND user_id = :ownerId
                  AND retention_expires_at > now()
                """.trimIndent(),
            ).param("id", documentId)
            .param("ownerId", ownerId)
            .query { rs, _ -> toSourceText(rs) }
            .optional()
            .orElse(null)

    /** 원문 암호문과 봉투를 읽고 **행을 잠근다**(`FOR NO KEY UPDATE`). */
    override fun lockSourceText(documentId: UUID): EncryptedContent? =
        jdbc
            .sql(
                """
                SELECT source_text_encrypted, encryption_scheme, key_version
                FROM documents WHERE id = :id
                FOR NO KEY UPDATE
                """.trimIndent(),
            ).param("id", documentId)
            .query { rs, _ ->
                EncryptedContent(
                    bytes = rs.getBytes("source_text_encrypted"),
                    scheme = rs.getString("encryption_scheme"),
                    keyVersion = rs.getInt("key_version"),
                )
            }.optional()
            .orElse(null)

    /** 봉투와 암호문을 한 UPDATE 로 바꾼다. 조건은 **잠근 채 읽은 행 그 자체**다. */
    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        sourceText: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE documents
                SET source_text_encrypted = :sourceText,
                    encryption_scheme = :scheme,
                    key_version = :keyVersion
                WHERE id = :id
                  AND encryption_scheme = :expectedScheme
                  AND key_version = :expectedKeyVersion
                  AND source_text_encrypted IS NOT DISTINCT FROM CAST(:expectedSourceText AS bytea)
                """.trimIndent(),
            ).param("sourceText", sourceText.bytes)
            .param("scheme", sourceText.scheme)
            .param("keyVersion", sourceText.keyVersion)
            .param("id", documentId)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedSourceText", expected.bytes)
            .update() > 0

    /** 내 문서를 지운다. **소유 조건이 같은 문장 안에 있다.** */
    override fun deleteOwned(
        ownerId: UUID,
        documentId: UUID,
    ): Boolean =
        jdbc
            .sql("DELETE FROM documents WHERE id = :id AND user_id = :ownerId")
            .param("id", documentId)
            .param("ownerId", ownerId)
            .update() > 0

    private fun storageFailure(failure: DataIntegrityViolationException): StorageException {
        // 예외 **메시지**를 로그에 넣지 않는다 — 그 안에 실패한 행 전체가 들어 있다.
        DocumentStorageLog.constraintViolation("documents", failure)
        return StorageException(STORAGE_FAILURE_MESSAGE)
    }

    private fun toDocument(rs: ResultSet): Document =
        Document(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            sourceFormat = SourceFormat.ofWireName(rs.getString("source_format")),
            charCount = rs.getInt("char_count"),
            // timestamptz 를 OffsetDateTime 으로 받는다. `getTimestamp` 는 JVM 기본 시간대를
            // 끼워 넣어 서버 시간대에 따라 값이 달라진다.
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            retentionExpiresAt = rs.getObject("retention_expires_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun toSourceText(rs: ResultSet): StoredSourceText =
        StoredSourceText(
            documentId = rs.getObject("id", UUID::class.java),
            sourceFormat = SourceFormat.ofWireName(rs.getString("source_format")),
            charCount = rs.getInt("char_count"),
            sourceText =
                EncryptedContent(
                    bytes = rs.getBytes("source_text_encrypted"),
                    scheme = rs.getString("encryption_scheme"),
                    keyVersion = rs.getInt("key_version"),
                ),
        )

    private fun toListing(rs: ResultSet): DocumentListing {
        val conversionId = rs.getObject("conversion_id", UUID::class.java)
        val status = rs.getString("conversion_status")
        return DocumentListing(
            document = toDocument(rs),
            conversionId = conversionId,
            status = status?.let { ConversionStatus.ofWireName(it) },
            reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
            feedbackSubmittedAt = rs.getObject("feedback_submitted_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private companion object {
        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"

        val INSERT_SQL =
            """
            INSERT INTO documents
                (id, user_id, workspace_id, title, source_format, source_text_encrypted,
                 encryption_scheme, key_version, char_count)
            VALUES (:id, :ownerId, :workspaceId, :title, :sourceFormat, :sourceText,
                    :scheme, :keyVersion, :charCount)
            RETURNING id, title, source_format, char_count, created_at, retention_expires_at
            """.trimIndent()

        /**
         * 목록 질의. 작업 공간 필터 유무로 **두 형태**가 있다.
         *
         * 피드백도 최신 변환과 같은 규칙으로 **왼쪽 조인**한다 — 의견을 내지 않은 문서가
         * 목록에서 사라지면 안 된다. 봉인된 자유 의견 열은 고르지 않고 `submitted_at` 하나만
         * 든다(계약 `DocumentListItem.feedback_submitted_at`). `f.user_id = d.user_id` 는
         * `JdbcConversionRepository.FIND_OWNED_SQL` 과 같은 fail-closed 술어다.
         */
        fun listSql(filterWorkspace: Boolean): String =
            """
            SELECT d.id, d.title, d.source_format, d.char_count,
                   d.created_at, d.retention_expires_at,
                   c.id AS conversion_id, c.status AS conversion_status, c.reviewed_at,
                   f.submitted_at AS feedback_submitted_at
            FROM documents d
            LEFT JOIN LATERAL (
                SELECT k.id, k.status, k.reviewed_at
                FROM conversions k
                WHERE k.document_id = d.id
                ORDER BY k.created_at DESC, k.id DESC
                LIMIT 1
            ) c ON true
            LEFT JOIN conversion_feedback f
                   ON f.conversion_id = c.id AND f.user_id = d.user_id
            WHERE d.user_id = :ownerId
            ${if (filterWorkspace) "AND d.workspace_id = :workspaceId" else ""}
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent()
    }
}

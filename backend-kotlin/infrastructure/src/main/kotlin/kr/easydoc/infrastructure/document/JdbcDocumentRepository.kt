package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.DocumentRepository
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

/**
 * `documents` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` + `V3`·`V4` 가 정한다.
 *
 * ## 봉투 두 값을 **INSERT 문에 명시한다**
 *
 * `V3__encryption_scheme_aead.sql` 이 `encryption_scheme`·`key_version` 의 DEFAULT 를
 * 없앴다. 그래서 컬럼을 빠뜨린 INSERT 는 NOT NULL 위반으로 **즉시 시끄럽게** 실패한다 —
 * 그것이 `V3` 의 설계 의도다(DEFAULT 는 데이터에 대해 **거짓인** 값을 조용히 채운다).
 * 값은 [EncryptedContent] 가 암호문과 함께 들고 온 것이라 "암호문은 v2 인데 컬럼은 v1" 이
 * 되지 않는다.
 *
 * ## 소유 조건이 SQL 안에 있다
 *
 * 읽기 질의가 `WHERE ... AND user_id = :ownerId` 를 든다. 읽은 뒤에 Kotlin 에서 소유자를
 * 비교하지 않는 이유는 `JdbcWorkspaceRepository` 와 같다 — 비교를 잊으면 조용히 남의
 * 자원을 내주고, 잊지 않아도 **남의 자원일 때만 행을 읽으므로 그 차이가 응답 시간에 남는다.**
 *
 * ## 원본 DB 예외를 올리지 않고, 메시지를 로그에도 넣지 않는다
 *
 * PostgreSQL 은 제약 위반의 `DETAIL` 에 **실패한 행 전체**를 담는다 — 여기서는 문서 제목과
 * **원문 암호문**이 그 문자열에 실린다. 그래서 원인 체인을 끊고, 로그에도
 * **SQLSTATE 다섯 글자만** 남긴다(계획 §9.1 · I-4). SQLSTATE 로 갈래를 나누지도 않는다 —
 * 여기서 터질 수 있는 제약은 전부 코드·스키마 버그라 사용자에게 다르게 안내할 것이 없다.
 */
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

    override fun loadSourceText(documentId: UUID): EncryptedContent? =
        jdbc
            .sql(
                """
                SELECT source_text_encrypted, encryption_scheme, key_version
                FROM documents WHERE id = :id
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

    override fun rewriteEnvelope(
        documentId: UUID,
        expectedKeyVersion: Int,
        sourceText: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE documents
                SET source_text_encrypted = :sourceText,
                    encryption_scheme = :scheme,
                    key_version = :keyVersion
                WHERE id = :id AND key_version = :expectedKeyVersion
                """.trimIndent(),
            ).param("sourceText", sourceText.bytes)
            .param("scheme", sourceText.scheme)
            .param("keyVersion", sourceText.keyVersion)
            .param("id", documentId)
            .param("expectedKeyVersion", expectedKeyVersion)
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

    private fun toListing(rs: ResultSet): DocumentListing {
        val conversionId = rs.getObject("conversion_id", UUID::class.java)
        val status = rs.getString("conversion_status")
        return DocumentListing(
            document = toDocument(rs),
            conversionId = conversionId,
            status = status?.let { ConversionStatus.ofWireName(it) },
            reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
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
         * 조각을 문자열로 이어 붙이지만 주입면이 없다 — 붙이는 값이 사용자 입력이 아니라
         * **컴파일 시점 상수 두 개 중 하나**이고, 식별자·값은 전부 이름 붙은 파라미터다.
         * `:workspaceId::uuid IS NULL` 같은 한 형태로 합치는 갈래를 고르지 않은 이유는
         * 널 파라미터에 JDBC 타입 힌트가 필요해지고, 그 힌트가 빠지면 드라이버마다 다르게
         * 실패하기 때문이다.
         *
         * ## 최신 변환 한 건을 `LEFT JOIN LATERAL` 로 붙인다
         *
         * 문서마다 변환이 여럿일 수 있고(재시도) 목록이 원하는 것은 **최신 하나**다.
         * 상관 서브쿼리를 컬럼마다 쓰면 세 번 돌고, 일반 `LEFT JOIN` 은 행을 부풀린다.
         *
         * ## 정렬이 `created_at DESC, id DESC` 인 이유
         *
         * `created_at` 은 트랜잭션 시각이라 같은 트랜잭션에서 만든 행끼리 동률이 난다.
         * 동률의 순서가 정해지지 않으면 **페이지 경계에서 같은 문서가 두 번 보이거나 아예
         * 빠진다** — `id` 가 뜻 있는 순서를 주지는 않지만 흔들리지 않게는 한다.
         */
        fun listSql(filterWorkspace: Boolean): String =
            """
            SELECT d.id, d.title, d.source_format, d.char_count,
                   d.created_at, d.retention_expires_at,
                   c.id AS conversion_id, c.status AS conversion_status, c.reviewed_at
            FROM documents d
            LEFT JOIN LATERAL (
                SELECT k.id, k.status, k.reviewed_at
                FROM conversions k
                WHERE k.document_id = d.id
                ORDER BY k.created_at DESC, k.id DESC
                LIMIT 1
            ) c ON true
            WHERE d.user_id = :ownerId
            ${if (filterWorkspace) "AND d.workspace_id = :workspaceId" else ""}
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent()
    }
}

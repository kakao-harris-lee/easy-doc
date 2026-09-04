package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentOriginalRepository
import kr.easydoc.application.document.StoredOriginal
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** `document_originals` 테이블 접근. 스키마는 `V3__document_originals.sql` 이 정한다. */
class JdbcDocumentOriginalRepository(private val jdbc: JdbcClient) : DocumentOriginalRepository {
    /**
     * 소유 술어가 **쓰기 문장 자신에** 걸린다 — `VALUES` 가 아니라 `documents` 를 훑는
     * `INSERT ... SELECT` 인 이유다. 0행은 「그 문서가 없거나 내 것이 아니다」이고, 업로드
     * 경로에서는 방금 만든 행이 보이지 않았다는 뜻이라 **정상 분기가 아니다.**
     */
    override fun insert(
        ownerId: UUID,
        documentId: UUID,
        original: StoredOriginal,
    ) {
        val inserted =
            try {
                jdbc
                    .sql(INSERT_SQL)
                    .param("documentId", documentId)
                    .param("ownerId", ownerId)
                    .param("fileBytes", original.bytes.bytes)
                    .param("scheme", original.bytes.scheme)
                    .param("keyVersion", original.bytes.keyVersion)
                    .param("byteSize", original.byteSize)
                    .update()
            } catch (failure: DataIntegrityViolationException) {
                throw storageFailure(failure)
            }
        // 조용히 0행으로 끝나면 「문서는 남았는데 원본은 없다」가 커밋된다 — 트랜잭션을 끊는다.
        if (inserted == 0) throw StorageException(STORAGE_FAILURE_MESSAGE)
    }

    /**
     * 소유 술어가 **읽기 문장 자신에** 걸린다 — 조인으로 `documents.user_id` 를 함께 본다.
     * 애플리케이션에서 두 번 질의해 비교하면 그 사이가 갈릴 수 있고, 무엇보다 「없다」와
     * 「남의 것」이 서로 다른 코드 경로가 되어 존재가 새어 나간다.
     */
    override fun findOwned(
        ownerId: UUID,
        documentId: UUID,
    ): StoredOriginal? =
        jdbc
            .sql(FIND_OWNED_SQL)
            .param("documentId", documentId)
            .param("ownerId", ownerId)
            .query { rs, _ ->
                StoredOriginal(
                    bytes =
                        EncryptedContent(
                            bytes = rs.getBytes("file_bytes_encrypted"),
                            scheme = rs.getString("encryption_scheme"),
                            keyVersion = rs.getInt("key_version"),
                        ),
                    byteSize = rs.getInt("byte_size"),
                )
            }.optional()
            .orElse(null)

    /**
     * 원본 암호문과 봉투를 읽고 **행을 잠근다**(`FOR NO KEY UPDATE`).
     *
     * `byte_size` 를 읽지 않는다 — 회전은 봉투만 바꾸고 평문 크기는 그대로다. 읽어 봐야
     * 쓰기 조건에도 쓰이지 않는 값이다.
     */
    override fun lockOriginal(documentId: UUID): EncryptedContent? =
        jdbc
            .sql(
                """
                SELECT file_bytes_encrypted, encryption_scheme, key_version
                FROM document_originals WHERE document_id = :documentId
                FOR NO KEY UPDATE
                """.trimIndent(),
            ).param("documentId", documentId)
            .query { rs, _ ->
                EncryptedContent(
                    bytes = rs.getBytes("file_bytes_encrypted"),
                    scheme = rs.getString("encryption_scheme"),
                    keyVersion = rs.getInt("key_version"),
                )
            }.optional()
            .orElse(null)

    /**
     * 봉투와 암호문을 한 UPDATE 로 바꾼다. 조건은 **잠근 채 읽은 행 그 자체**다 —
     * `JdbcDocumentRepository.rewriteEnvelope` 와 같은 형태다.
     *
     * `byte_size` 는 건드리지 않는다. 회전은 평문을 바꾸지 않으므로 크기도 바뀌지 않고,
     * 여기서 다시 쓰면 그 값이 암호문 길이로 조용히 갈릴 자리가 생긴다.
     */
    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        original: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(REWRITE_SQL)
            .param("fileBytes", original.bytes)
            .param("scheme", original.scheme)
            .param("keyVersion", original.keyVersion)
            .param("documentId", documentId)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedFileBytes", expected.bytes)
            .update() > 0

    /** 키 회전 배치의 후보. `document_id > :after` 로 커서를 넘긴다 — [DocumentOriginalRepository.documentIdsOlderThan] KDoc. */
    override fun documentIdsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT document_id FROM document_originals
                WHERE key_version < :keyVersion AND document_id > :after
                ORDER BY document_id ASC
                LIMIT :limit
                """.trimIndent(),
            ).param("keyVersion", keyVersion)
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("document_id", UUID::class.java) }
            .list()

    private fun storageFailure(failure: DataIntegrityViolationException): StorageException {
        // 예외 **메시지**를 로그에 넣지 않는다 — 그 안에 실패한 행 전체가 들어 있다.
        // 원본 표에서는 그것이 곧 파일 바이트다.
        DocumentStorageLog.constraintViolation("document_originals", failure)
        return StorageException(STORAGE_FAILURE_MESSAGE)
    }

    private companion object {
        /** 저장소가 만든 고정 문자열. `JdbcDocumentRepository` 와 같은 값이다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"

        val INSERT_SQL =
            """
            INSERT INTO document_originals
                (document_id, file_bytes_encrypted, encryption_scheme, key_version, byte_size)
            SELECT d.id, :fileBytes, :scheme, :keyVersion, :byteSize
            FROM documents d
            WHERE d.id = :documentId AND d.user_id = :ownerId
            """.trimIndent()

        val FIND_OWNED_SQL =
            """
            SELECT o.file_bytes_encrypted, o.encryption_scheme, o.key_version, o.byte_size
            FROM document_originals o
            INNER JOIN documents d ON d.id = o.document_id
            WHERE o.document_id = :documentId AND d.user_id = :ownerId
            """.trimIndent()

        val REWRITE_SQL =
            """
            UPDATE document_originals
            SET file_bytes_encrypted = :fileBytes,
                encryption_scheme = :scheme,
                key_version = :keyVersion
            WHERE document_id = :documentId
              AND encryption_scheme = :expectedScheme
              AND key_version = :expectedKeyVersion
              AND file_bytes_encrypted IS NOT DISTINCT FROM CAST(:expectedFileBytes AS bytea)
            """.trimIndent()
    }
}

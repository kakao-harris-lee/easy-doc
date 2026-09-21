package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentTableStructureRepository
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.StorageException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** 문서의 R4 표 구조 payload 저장소. 평문은 호출자가 봉인한 뒤에만 이 어댑터로 들어온다. */
class JdbcDocumentTableStructureRepository(private val jdbc: JdbcClient) : DocumentTableStructureRepository {
    override fun insert(
        ownerId: UUID,
        documentId: UUID,
        payload: EncryptedContent,
    ) {
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO document_table_structures
                        (document_id, payload_encrypted, encryption_scheme, key_version)
                    SELECT :documentId, :payload, :scheme, :keyVersion
                    WHERE EXISTS (
                        SELECT 1 FROM documents d
                        WHERE d.id = :documentId AND d.user_id = :ownerId AND d.retention_expires_at > now()
                    )
                    """.trimIndent(),
                ).param("documentId", documentId)
                .param("ownerId", ownerId)
                .param("payload", payload.bytes)
                .param("scheme", payload.scheme)
                .param("keyVersion", payload.keyVersion)
                .update()
        if (inserted != 1) throw StorageException("표 구조를 저장하지 못했습니다")
    }
}

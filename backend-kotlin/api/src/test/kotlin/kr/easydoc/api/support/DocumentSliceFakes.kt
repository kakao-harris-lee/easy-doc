package kr.easydoc.api.support

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.application.document.WorkspaceLookup
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** `@WebMvcTest` 슬라이스가 쓰는 문서 경로 대역. */
class InMemoryDocumentRepository : DocumentRepository {
    private class Row(
        val ownerId: UUID,
        val document: Document,
        val workspaceId: UUID,
        var sourceText: EncryptedContent,
    )

    private val rows = mutableListOf<Row>()

    override fun insert(
        ownerId: UUID,
        draft: DocumentDraft,
        sourceText: EncryptedContent,
    ): Document {
        val createdAt = Instant.EPOCH.plusSeconds(rows.size.toLong())
        val document =
            Document(
                id = draft.id,
                title = draft.title,
                sourceFormat = draft.sourceFormat,
                charCount = draft.charCount,
                createdAt = createdAt,
                retentionExpiresAt = createdAt.plus(RETENTION_DAYS, ChronoUnit.DAYS),
            )
        rows += Row(ownerId, document, draft.workspaceId, sourceText)
        return document
    }

    override fun listOwned(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing> =
        rows
            .filter { it.ownerId == ownerId && (workspaceId == null || it.workspaceId == workspaceId) }
            .sortedWith(compareByDescending<Row> { it.document.createdAt }.thenByDescending { it.document.id })
            .drop(offset)
            .take(limit)
            .map { DocumentListing(it.document, conversionId = null, status = null, reviewedAt = null) }

    override fun lockSourceText(documentId: UUID): EncryptedContent? =
        rows.firstOrNull { it.document.id == documentId }?.sourceText

    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        sourceText: EncryptedContent,
    ): Boolean {
        val row = rows.firstOrNull { it.document.id == documentId && it.sourceText == expected }
        row?.sourceText = sourceText
        return row != null
    }

    /** 소유 조건을 실물과 같은 축으로 본다 — 두 조건이 한 판정에 함께 든다. */
    override fun deleteOwned(
        ownerId: UUID,
        documentId: UUID,
    ): Boolean = rows.removeIf { it.ownerId == ownerId && it.document.id == documentId }

    private companion object {
        /** 계약 `x-input-limits.retention_days`. 슬라이스는 이 값을 단언하지 않는다 — 실물 DB 가 잰다. */
        const val RETENTION_DAYS = 30L
    }
}

/** `conversions` 대역. 대기 상태 한 건을 만드는 것까지가 이 슬라이스의 범위다. */
class InMemoryConversionRepository : ConversionRepository {
    private val rows = mutableMapOf<UUID, ConversionEnvelope>()

    override fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion {
        rows[id] =
            ConversionEnvelope(
                conversionId = id,
                scheme = scheme,
                keyVersion = keyVersion,
                ciphertexts =
                    ConversionCiphertexts(
                        easyText = null,
                        maskedItems = null,
                        editedText = null,
                    ),
            )
        return Conversion(
            id = id,
            documentId = documentId,
            status = ConversionStatus.PENDING,
            failureCode = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }

    override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = rows[conversionId]

    override fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean {
        // 읽어 온 그 행이 그대로일 때만 쓴다(동일성 비교). 실물은 봉투 두 값 + 암호문 세 열을
        // SQL 조건으로 건다 — 여기서는 같은 성질을 참조 동일성으로 흉내 낸다.
        val unchanged = rows[expected.conversionId] === expected
        if (unchanged) {
            rows[expected.conversionId] = ConversionEnvelope(expected.conversionId, scheme, keyVersion, ciphertexts)
        }
        return unchanged
    }
}

/** 등록된 작업을 세는 대역. 슬라이스에서 재는 것은 "불렸는가"뿐이다. */
class RecordingConversionQueue : ConversionQueue {
    val enqueued: MutableList<UUID> = mutableListOf()

    override fun enqueue(conversionId: UUID) {
        enqueued += conversionId
    }
}

/** 작업 공간 읽기 대역 — 실물처럼 [InMemoryWorkspaceRepository] 를 소유 조건과 함께 본다. */
class InMemoryWorkspaceLookup(private val workspaces: InMemoryWorkspaceRepository) : WorkspaceLookup {
    override fun findOwnedId(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID? =
        workspaces
            .listOwned(ownerId)
            .firstOrNull { it.workspace.id == workspaceId }
            ?.workspace
            ?.id

    override fun findDefaultId(ownerId: UUID): UUID? =
        workspaces
            .listOwned(ownerId)
            .firstOrNull()
            ?.workspace
            ?.id
}

/** 암호화를 흉내만 낸다 — AES 를 슬라이스에서 돌리지 않는다. */
class StubContentCipher : ContentCipher {
    override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1
    override val writeKeyVersion: Int = 1

    override fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent =
        EncryptedContent(
            bytes =
                plain.value
                    .toByteArray(Charsets.UTF_8)
                    .map { (it.toInt() xor MASK).toByte() }
                    .toByteArray(),
            scheme = writeScheme,
            keyVersion = writeKeyVersion,
        )

    override fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody = PlainBody(String(content.bytes.map { (it.toInt() xor MASK).toByte() }.toByteArray(), Charsets.UTF_8))

    private companion object {
        /** 대칭 변환 하나. 암호가 아니다 — 평문과 바이트가 같아지는 상태만 막는다. */
        const val MASK = 0x5A
    }
}

/**
 * 파일 추출 대역.
 *
 * 확장자 판별은 **실물 규칙**([SourceFormat.ofUploadFilename])을 그대로 쓴다 — 그 판정이
 * 계약 `x-input-limits.supported_upload_formats` 와 묶여 있고, 대역이 자기 규칙을 만들면
 * 슬라이스가 계약과 무관한 것을 재게 된다. 파서만 흉내 낸다: 바이트를 UTF-8 로 읽는다.
 */
class StubDocumentTextExtractor : DocumentTextExtractor {
    override fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument {
        val format =
            SourceFormat.ofUploadFilename(filename)
                ?: throw UnsupportedFormatException(
                    "지원 형식: ${SourceFormat.UPLOAD_FORMATS.joinToString(", ") { it.wireName }}",
                )
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) throw DocumentExtractionException("문서에서 텍스트를 찾을 수 없습니다")
        return ExtractedDocument(format, text)
    }
}

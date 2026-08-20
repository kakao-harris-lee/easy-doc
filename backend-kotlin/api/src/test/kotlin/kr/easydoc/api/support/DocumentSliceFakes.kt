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

/**
 * `@WebMvcTest` 슬라이스가 쓰는 **문서 경로 대역**.
 *
 * ## 가짜인 것은 저장소·암호·파서뿐이다
 *
 * `DocumentService` 는 **실물**이다(`AuthSliceBeans` 의 `WorkspaceService` 와 같은 규칙).
 * 그래야 계약이 못박은 검사 순서 — 크기 → 추출 → 길이 → 소유권 → 저장 — 를 이 슬라이스가
 * 실제로 밟는다. 유스케이스까지 대역으로 두면 재는 것이 계약이 아니라 "내가 만든 대역이
 * 계약처럼 답하는가"가 된다.
 *
 * ## 여기서 재지 **않는** 것
 *
 * 실제 암호화·트랜잭션 원자성·소유 조건이 SQL `WHERE` 안에 있는지·FK CASCADE 는 전부
 * 실 PostgreSQL 이 필요하다 — `DocumentEndpointReachTest` 와 `infrastructure` 의 저장소
 * 테스트가 맡는다. 다만 **소유 판정 자체**([InMemoryWorkspaceLookup])는 실물과 같은 축으로
 * 둔다. 그러지 않으면 슬라이스가 「구현이 소유자를 안 봐도 초록」이 된다.
 */
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
        // 실물에서 DB `DEFAULT` 가 채우는 두 값을 여기서 만든다. 순서가 정해져야 목록
        // 정렬(`created_at DESC, id DESC`)을 잴 수 있으므로 1초씩 벌린다.
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
        // 낙관적 조건 — **읽어 온 암호문 그대로**여야 한다. 실물은 이것을 SQL `WHERE` 로 건다.
        val row = rows.firstOrNull { it.document.id == documentId && it.sourceText == expected }
        row?.sourceText = sourceText
        return row != null
    }

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

/**
 * 작업 공간 읽기 대역 — 실물처럼 **[InMemoryWorkspaceRepository] 를 소유 조건과 함께** 본다.
 *
 * 소유 판정을 흉내만 내면(예: 언제나 찾음) DC-16·DC-17 이 재는 것이 사라진다. 여기서는
 * 저장소를 그대로 읽되 소유자 조건을 함께 건다.
 */
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

/**
 * 암호화를 흉내만 낸다 — **AES 를 슬라이스에서 돌리지 않는다.**
 *
 * 봉인의 정확성(round-trip·변조 거부·nonce 재사용 금지·AAD 결속)은 `migration-safety-gate`
 * I-7 의 대상이고 `infrastructure` 의 `AesGcmContentCipher` 테스트가 잰다. 여기서 재는 것은
 * **HTTP 계약**이다.
 *
 * 그래도 **평문을 그대로 바이트로 두지 않는다** — 그러면 이 대역을 쓰는 테스트에서
 * "암호문이 평문과 같다"가 참이 되고, 그 상태를 실수로 실측 근거로 삼을 수 있다.
 */
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
        /** 대칭 변환 하나. **암호가 아니다** — 평문과 바이트가 같아지는 상태만 막는다. */
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

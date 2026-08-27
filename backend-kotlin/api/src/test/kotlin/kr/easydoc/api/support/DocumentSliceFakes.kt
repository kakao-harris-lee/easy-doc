package kr.easydoc.api.support

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionFeedbackRepository
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.DocumentOriginalRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.application.document.LockedConversion
import kr.easydoc.application.document.LockedFeedbackComment
import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.application.document.OriginalDocument
import kr.easydoc.application.document.OriginalStructureReflector
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.application.document.StoredExport
import kr.easydoc.application.document.StoredFeedback
import kr.easydoc.application.document.StoredOriginal
import kr.easydoc.application.document.StoredSourceText
import kr.easydoc.application.document.WorkspaceLookup
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
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
            .map {
                DocumentListing(
                    it.document,
                    conversionId = null,
                    status = null,
                    reviewedAt = null,
                    feedbackSubmittedAt = null,
                )
            }

    /**
     * 소유 조건을 실물과 같은 축으로 본다 — 두 조건이 한 판정에 함께 든다.
     *
     * **보존 기간은 여기서 보지 않는다.** 이 대역의 시계는 [Instant.EPOCH] 에서 출발하므로
     * 모든 행이 실시간 기준으로는 이미 만료다 — 만료 술어를 흉내 내면 슬라이스의 모든 문서가
     * 404 가 되고, 그것은 실물의 동작이 아니다. 보존 기간은 위 [RETENTION_DAYS] 주석의 규약
     * 그대로 **실물 DB 가 잰다**(`DocumentSourceReachTest` 의 만료 경계 케이스).
     */
    override fun findOwnedSource(
        ownerId: UUID,
        documentId: UUID,
    ): StoredSourceText? =
        rows
            .firstOrNull { it.ownerId == ownerId && it.document.id == documentId }
            ?.let {
                StoredSourceText(
                    documentId = it.document.id,
                    sourceFormat = it.document.sourceFormat,
                    charCount = it.document.charCount,
                    sourceText = it.sourceText,
                )
            }

    override fun lockSourceText(documentId: UUID): EncryptedContent? =
        rows.firstOrNull { it.document.id == documentId }?.sourceText

    /** 그 문서의 소유자. 없으면 `null`. */
    fun ownerOf(documentId: UUID): UUID? = rows.firstOrNull { it.document.id == documentId }?.ownerId

    /** 그 문서의 제목. 없으면 `null`. */
    fun titleOf(documentId: UUID): String? = rows.firstOrNull { it.document.id == documentId }?.document?.title

    /** 그 문서의 원본 형식. 없으면 `null` — 실물에서는 조회 조인이 같은 값을 함께 읽는다. */
    fun sourceFormatOf(documentId: UUID): SourceFormat? =
        rows.firstOrNull { it.document.id == documentId }?.document?.sourceFormat

    /**
     * 저장된 제목을 테스트가 직접 바꾼다. 제품 경로의 [resolveTitle] 을 우회해
     * 내보내기 파일명 정제를 재기 위한 자리이다.
     */
    fun rewriteTitle(
        documentId: UUID,
        title: String,
    ) {
        val index = rows.indexOfFirst { it.document.id == documentId }
        check(index >= 0) { "제목을 바꿀 문서가 없다" }
        val row = rows[index]
        val document = row.document
        rows[index] =
            Row(
                row.ownerId,
                Document(
                    document.id,
                    title,
                    document.sourceFormat,
                    document.charCount,
                    document.createdAt,
                    document.retentionExpiresAt,
                ),
                row.workspaceId,
                row.sourceText,
            )
    }

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

/**
 * `conversions` 대역.
 *
 * 원본 저장소를 함께 받는 것이 실물과 같은 모양이다 — `JdbcConversionRepository` 의 조회는
 * `document_originals` 의 행 유무를 **같은 한 문장**에서 `EXISTS` 로 읽는다. 대역이 그것을
 * 모르면 서식 유지 판정이 슬라이스에서만 다른 값이 된다.
 */
class InMemoryConversionRepository(
    private val documents: InMemoryDocumentRepository,
    private val originals: InMemoryDocumentOriginalRepository,
) : ConversionRepository {
    /** `data class` 인 사유는 `StoredConversion` KDoc 과 같다 — 필드 수와 detekt 문턱. */
    private data class Row(
        val documentId: UUID,
        var envelope: ConversionEnvelope,
        var status: ConversionStatus,
        var reviewedAt: Instant?,
        /** 실물에서는 `conversion_feedback` 을 왼쪽 조인해 읽는 값이다. 낸 적이 없으면 `null`. */
        var feedbackSubmittedAt: Instant?,
        var missingPlaceholders: List<String>,
        var model: String?,
        var providerName: String?,
        var inputTokens: Int?,
        var outputTokens: Int?,
        var failureCode: String?,
    )

    private val rows = mutableMapOf<UUID, Row>()

    override fun insertPending(
        id: UUID,
        documentId: UUID,
        scheme: String,
        keyVersion: Int,
    ): Conversion {
        rows[id] =
            Row(
                documentId = documentId,
                envelope =
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
                    ),
                status = ConversionStatus.PENDING,
                reviewedAt = null,
                feedbackSubmittedAt = null,
                missingPlaceholders = emptyList(),
                model = null,
                providerName = null,
                inputTokens = null,
                outputTokens = null,
                failureCode = null,
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

    /** 소유 판정을 문서 저장소에 묻는다. 없거나 남의 것이면 `null` — 두 경우를 구분하지 않는다. */
    override fun findOwnedResult(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredConversion? =
        rows[conversionId]
            ?.takeIf { documents.ownerOf(it.documentId) == ownerId }
            ?.let { row ->
                StoredConversion(
                    id = conversionId,
                    documentId = row.documentId,
                    status = row.status,
                    sourceFormat =
                        documents.sourceFormatOf(row.documentId)
                            ?: error("변환은 있는데 그 문서가 없다 — 대역의 두 표가 갈렸다"),
                    hasStoredOriginal = originals.byteSizeOf(row.documentId) != null,
                    ciphertexts = row.envelope.ciphertexts,
                    reviewedAt = row.reviewedAt,
                    feedbackSubmittedAt = row.feedbackSubmittedAt,
                    missingPlaceholders = row.missingPlaceholders,
                    model = row.model,
                    providerName = row.providerName,
                    inputTokens = row.inputTokens,
                    outputTokens = row.outputTokens,
                    failureCode = row.failureCode,
                )
            }

    override fun findOwnedExport(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredExport? =
        findOwnedResult(ownerId, conversionId)?.let { stored ->
            documents.titleOf(stored.documentId)?.let { StoredExport(stored, it) }
        }

    override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = rows[conversionId]?.envelope

    override fun rewriteEnvelope(
        expected: ConversionEnvelope,
        scheme: String,
        keyVersion: Int,
        ciphertexts: ConversionCiphertexts,
    ): Boolean {
        val row = rows[expected.conversionId] ?: return false
        val unchanged = row.envelope === expected
        if (unchanged) {
            row.envelope = ConversionEnvelope(expected.conversionId, scheme, keyVersion, ciphertexts)
        }
        return unchanged
    }

    /** 소유 판정은 [findOwnedResult] 와 같은 자리에 묻는다. 대역이라 잠금은 없다. */
    override fun lockOwnedForReview(
        ownerId: UUID,
        conversionId: UUID,
    ): LockedConversion? =
        rows[conversionId]
            ?.takeIf { documents.ownerOf(it.documentId) == ownerId }
            ?.let { LockedConversion(it.status, it.envelope) }

    override fun saveReview(
        ownerId: UUID,
        expected: ConversionEnvelope,
        requiredStatus: ConversionStatus,
        updated: ConversionEnvelope,
    ): Boolean {
        // 실물의 `WHERE` 조건을 대역에서도 판정한다 — 상태·봉투가 어긋나면 0행이다.
        val row = rows[expected.conversionId]?.takeIf { it.status == requiredStatus && it.envelope === expected }
        row?.envelope = updated
        row?.reviewedAt = Instant.EPOCH.plusSeconds(1)
        return row != null
    }

    /**
     * 피드백을 낸 흔적을 남긴다 — 실물에서는 `conversion_feedback` 의 행 하나이고, 조회는
     * 그것을 왼쪽 조인해 읽는다. **검수 시각을 건드리지 않는다**: 두 값이 다른 사실이라는
     * 것이 계약 `feedback_submitted_at` 의 요지다.
     */
    fun recordFeedback(
        conversionId: UUID,
        submittedAt: Instant,
    ) {
        rows.getValue(conversionId).feedbackSubmittedAt = submittedAt
    }

    /** 변환 한 건을 완료 상태로 만든다. 실물에서는 Phase 5 워커의 UPDATE 다. */
    @Suppress("LongParameterList")
    fun complete(
        conversionId: UUID,
        ciphertexts: ConversionCiphertexts,
        missingPlaceholders: List<String>,
        model: String,
        providerName: String,
        inputTokens: Int,
        outputTokens: Int,
    ) {
        val row = rows.getValue(conversionId)
        row.envelope = ConversionEnvelope(conversionId, row.envelope.scheme, row.envelope.keyVersion, ciphertexts)
        row.status = ConversionStatus.DONE
        row.missingPlaceholders = missingPlaceholders
        row.model = model
        row.providerName = providerName
        row.inputTokens = inputTokens
        row.outputTokens = outputTokens
    }
}

/**
 * 파일럿 피드백 저장소 대역. 실물과 같은 성질 하나를 지킨다 — **변환당 행이 하나다.**
 * 재제출이 행을 늘리면 게이트 ① 판정의 분모가 부풀고, 그 오염은 집계 시점에 되돌릴 수 없다.
 */
class InMemoryConversionFeedbackRepository : ConversionFeedbackRepository {
    private val rows = mutableMapOf<UUID, Pair<UUID, StoredFeedback>>()

    /** 저장된 행. 테스트가 「하나뿐인가」를 보는 자리다. */
    val stored: Map<UUID, Pair<UUID, StoredFeedback>> get() = rows

    override fun upsert(
        ownerId: UUID,
        feedback: StoredFeedback,
    ): Instant {
        rows[feedback.conversionId] = ownerId to feedback
        // 실물은 DB 시계다. 대역은 호출 순서만 구분하면 되므로 단조 증가 값을 준다.
        return Instant.EPOCH.plusSeconds(rows.size.toLong())
    }

    /**
     * 회전 팔은 슬라이스에 없다 — HTTP 경로가 키 회전을 부르지 않는다. 부르면 이 대역이
     * 그 사실로 끊긴다(조용히 `null` 을 돌려주면 회전이 「행이 없다」로 통과한다).
     */
    override fun lockComment(conversionId: UUID): LockedFeedbackComment = error(ROTATION_PORT_MESSAGE)

    override fun rewriteComment(
        conversionId: UUID,
        expected: EncryptedContent,
        comment: EncryptedContent,
    ): Boolean = error(ROTATION_PORT_MESSAGE)

    private companion object {
        const val ROTATION_PORT_MESSAGE = "HTTP 슬라이스가 회전 포트를 부르면 안 된다"
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

/**
 * 업로드 원본 저장 대역. 조회의 서식 유지 판정과 내보내기가 [findOwned] 로 이 행을 읽는다 —
 * 소유 술어를 실물과 같은 자리에 두는 것이 슬라이스에서도 중요한 이유다.
 */
class InMemoryDocumentOriginalRepository(private val documents: InMemoryDocumentRepository) :
    DocumentOriginalRepository {
    private val rows = mutableMapOf<UUID, StoredOriginal>()

    override fun insert(
        ownerId: UUID,
        documentId: UUID,
        original: StoredOriginal,
    ) {
        // 실물처럼 소유 술어가 쓰기 자신에 걸린다 — 남의 문서면 0행이고, 그것은 결함이다.
        check(documents.ownerOf(documentId) == ownerId) { "남의 문서에 원본을 붙였다" }
        rows[documentId] = original
    }

    /** 소유 술어를 실물과 같은 자리에 둔다 — 남의 문서면 「없다」와 같은 값이다. */
    override fun findOwned(
        ownerId: UUID,
        documentId: UUID,
    ): StoredOriginal? = if (documents.ownerOf(documentId) == ownerId) rows[documentId] else null

    override fun lockOriginal(documentId: UUID): EncryptedContent? = rows[documentId]?.bytes

    override fun rewriteEnvelope(
        documentId: UUID,
        expected: EncryptedContent,
        original: EncryptedContent,
    ): Boolean = error("슬라이스가 회전을 돌리지 않는다")

    /** 그 문서에 원본이 남았는가 — 붙여넣기 팔이 행을 만들지 않는 것을 재는 자리다. */
    fun byteSizeOf(documentId: UUID): Int? = rows[documentId]?.byteSize
}

/** 암호화를 흉내만 낸다 — AES 를 슬라이스에서 돌리지 않는다. */
class StubContentCipher : ContentCipher {
    override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1
    override val writeKeyVersion: Int = 1

    /** 바이트 짝만 구현한다 — 문자열 짝은 [ContentCipher] 의 기본 구현을 탄다. */
    override fun encryptBytes(
        plain: PlainBytes,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent =
        EncryptedContent(
            bytes = masked(plain.value),
            scheme = writeScheme,
            keyVersion = writeKeyVersion,
        )

    override fun decryptBytes(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBytes = PlainBytes(masked(content.bytes))

    private companion object {
        /** 대칭 변환 하나. 암호가 아니다 — 평문과 바이트가 같아지는 상태만 막는다. */
        const val MASK = 0x5A

        fun masked(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor MASK).toByte() }
    }
}

/** 마스킹 대응표 읽기 대역. */
class StubMaskedItemReader : MaskedItemReader {
    override fun decode(body: PlainBody): List<MaskedItemView> =
        body.value
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(SEPARATOR, limit = FIELD_COUNT)
                require(parts.size == FIELD_COUNT) { "대역 형식이 아니다 — 필드 ${parts.size}개" }
                MaskedItemView(
                    category = MaskCategory.entries.first { it.label == parts[0] },
                    placeholder = parts[1],
                    original = Secret(parts[2]),
                )
            }.toList()

    companion object {
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 3

        /** 테스트가 이 대역이 읽을 평문을 만든다. 암호화는 호출자가 [ContentCipher] 로 한다. */
        fun encodeForStub(items: List<MaskedItemView>): PlainBody =
            PlainBody(
                items.joinToString("\n") { item ->
                    listOf(item.category.label, item.placeholder, item.original.reveal()).joinToString(SEPARATOR)
                },
            )
    }
}

/** 파일 추출 대역. */
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

/**
 * 슬라이스의 원본 반영 대역.
 *
 * 슬라이스가 심는 원본은 진짜 DOCX 가 아니라 바이트 몇 개다. 여기서 POI 를 돌릴 수 없고
 * 돌릴 이유도 없다 — HTTP 팔이 지는 책임은 「어떤 갈래에서 판정이 서고 어떤 갈래가 오류가
 * 되는가」이고, 원본을 실제로 고쳐 쓰는 일은 `PackagedOriginalReflectorTest` 가 fixture 로
 * 잰다. [outcome] · [openable] 로 그 갈래를 슬라이스가 고른다.
 */
class SliceOriginalReflector : OriginalStructureReflector {
    /** 판정 결과. `null` 이면 「원본을 열 수 없다」다. */
    var outcome: ReflectionOutcome? = ReflectionOutcome(0, 0, 0, 0)

    /** 내보내기가 원본을 열 수 있는가. `false` 면 500 갈래다. */
    var openable: Boolean = true

    override fun outline(
        original: OriginalDocument,
        body: String,
    ): ReflectionOutcome? = outcome

    override fun reflect(
        original: OriginalDocument,
        title: String,
        body: String,
    ): ExportFile? =
        ExportFormat
            .ofSource(original.format)
            ?.takeIf { openable }
            ?.let { format -> exportFileOf(title, format, body.toByteArray(Charsets.UTF_8)) }
}

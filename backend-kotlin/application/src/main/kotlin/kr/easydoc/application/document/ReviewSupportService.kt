package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.easyread.ReviewAnalysis
import kr.easydoc.core.easyread.ReviewCoverage
import kr.easydoc.core.easyread.ReviewCoverageLimit
import kr.easydoc.core.easyread.ReviewItem
import kr.easydoc.core.easyread.ReviewItemKind
import kr.easydoc.core.easyread.ReviewItemState
import kr.easydoc.core.easyread.SourceAnchor
import kr.easydoc.core.easyread.analyzeReviewSupport
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class StoredReviewAssessment(
    val assessmentId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val analyzerVersion: String,
    val reviewRevision: Long,
    val payload: EncryptedContent,
)

interface ReviewAssessmentRepository {
    fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredReviewAssessment?

    fun findExact(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        analyzerVersion: String,
    ): StoredReviewAssessment?

    fun lockOwned(
        ownerId: UUID,
        conversionId: UUID,
        assessmentId: UUID,
    ): StoredReviewAssessment?

    fun insert(
        ownerId: UUID,
        assessment: StoredReviewAssessment,
    ): Boolean

    fun update(
        ownerId: UUID,
        assessmentId: UUID,
        expectedReviewRevision: Long,
        payload: EncryptedContent,
        updatedReviewRevision: Long,
    ): Boolean

    fun lockEnvelope(assessmentId: UUID): StoredReviewAssessment?

    fun rewriteEnvelope(
        expected: StoredReviewAssessment,
        payload: EncryptedContent,
    ): Boolean

    fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID>
}

data class ReviewAssessmentView(
    val assessmentId: UUID,
    val contentRevision: Long,
    val analyzerVersion: String,
    val reviewRevision: Long,
    val coverage: ReviewCoverage,
    val limitedReasons: List<ReviewCoverageLimit>,
    val items: List<ReviewItem>,
)

enum class ReviewSupportStatus(val wireName: String) { NOT_GENERATED("not_generated"), READY("ready"), STALE("stale") }

data class ReviewSupportView(
    val status: ReviewSupportStatus,
    val assessment: ReviewAssessmentView?,
)

/**
 * R1 규칙 분석과 확인 상태 저장. LLM을 호출하지 않는다.
 *
 * 생성자 협력자는 암호화된 스냅샷을 소유·보존·트랜잭션 경계 안에서 다루는 한 유스케이스의
 * 포트들이다. 함수 수 역시 조회·생성·상태 갱신과 그 봉인 도우미라 이 클래스 범위에서만 억제한다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ReviewSupportService(
    private val enabled: Boolean,
    private val conversions: ConversionRepository,
    private val documents: DocumentRepository,
    private val assessments: ReviewAssessmentRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun get(
        ownerId: UUID,
        conversionId: UUID,
    ): ReviewSupportView {
        requireEnabled()
        val (conversion, stored) =
            transaction.inTransaction {
                val conversion =
                    conversions.findOwnedResult(ownerId, conversionId)
                        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
                conversion to assessments.findLatestOwned(ownerId, conversionId)
            }
        if (stored == null) return ReviewSupportView(ReviewSupportStatus.NOT_GENERATED, null)
        val current =
            conversion.status == ConversionStatus.DONE &&
                stored.contentRevision == conversion.contentRevision &&
                stored.analyzerVersion == ANALYZER_VERSION
        return ReviewSupportView(if (current) ReviewSupportStatus.READY else ReviewSupportStatus.STALE, open(stored))
    }

    @Suppress("ThrowsCount") // 소유·완료·CAS·저장 경합을 계약의 서로 다른 실패로 유지한다.
    fun analyze(
        ownerId: UUID,
        conversionId: UUID,
        expectedContentRevision: Long,
    ): ReviewSupportView {
        requireEnabled()
        requireContentRevision(expectedContentRevision)
        val stored =
            transaction.inTransaction {
                val locked =
                    conversions.lockOwnedForReview(ownerId, conversionId)
                        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
                if (locked.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
                if (locked.contentRevision != expectedContentRevision) {
                    throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
                }
                assessments.findExact(ownerId, conversionId, expectedContentRevision, ANALYZER_VERSION)?.let {
                    return@inTransaction it
                }
                val source =
                    documents.findOwnedSource(ownerId, documentIdOf(ownerId, conversionId))
                        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
                val sourceText =
                    cipher.decrypt(
                        source.sourceText,
                        source.documentId,
                        EncryptedField.DOCUMENT_SOURCE_TEXT,
                    )
                val body = currentBody(locked) ?: throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
                val analysis = analyzeReviewSupport(sourceText.value, body.value)
                val id = UUID.randomUUID()
                val payload = seal(id, analysis)
                val candidate =
                    StoredReviewAssessment(id, conversionId, expectedContentRevision, ANALYZER_VERSION, 0, payload)
                if (assessments.insert(ownerId, candidate)) {
                    candidate
                } else {
                    assessments.findExact(ownerId, conversionId, expectedContentRevision, ANALYZER_VERSION)
                        ?: throw StorageException(REVIEW_SUPPORT_STORAGE_MESSAGE)
                }
            }
        return ReviewSupportView(ReviewSupportStatus.READY, open(stored))
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ThrowsCount")
    fun updateItem(
        ownerId: UUID,
        conversionId: UUID,
        itemId: UUID,
        assessmentId: UUID,
        expectedContentRevision: Long,
        expectedReviewRevision: Long,
        state: ReviewItemState,
        reason: String?,
    ): ReviewSupportView {
        requireEnabled()
        requireContentRevision(expectedContentRevision)
        if (expectedReviewRevision < 0 || expectedReviewRevision > MAX_SAFE_REVISION) {
            throw InvalidInputException(REVIEW_REVISION_INVALID_MESSAGE)
        }
        val normalizedReason = normalizeReason(state, reason)
        val updated =
            transaction.inTransaction {
                val locked =
                    conversions.lockOwnedForReview(ownerId, conversionId)
                        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
                if (locked.status != ConversionStatus.DONE || locked.contentRevision != expectedContentRevision) {
                    throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
                }
                val stored =
                    assessments.lockOwned(ownerId, conversionId, assessmentId)
                        ?: throw NotFoundException(REVIEW_ITEM_NOT_FOUND_MESSAGE)
                if (stored.contentRevision != expectedContentRevision || stored.analyzerVersion != ANALYZER_VERSION) {
                    throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
                }
                if (stored.reviewRevision != expectedReviewRevision) {
                    throw ConflictException(REVIEW_REVISION_CONFLICT_MESSAGE)
                }
                val analysis = openAnalysis(stored)
                val itemIndex = analysis.items.indexOfFirst { it.itemId == itemId }
                if (itemIndex < 0) throw NotFoundException(REVIEW_ITEM_NOT_FOUND_MESSAGE)
                val current = analysis.items[itemIndex]
                if (current.state == state && current.reason == normalizedReason) return@inTransaction stored
                val marked =
                    current.copy(
                        state = state,
                        reason = normalizedReason,
                        confirmedBy = if (state == ReviewItemState.NEEDS_REVIEW) null else ownerId,
                        confirmedAt = if (state == ReviewItemState.NEEDS_REVIEW) null else Instant.now(clock),
                    )
                val nextAnalysis = analysis.copy(items = analysis.items.toMutableList().also { it[itemIndex] = marked })
                val nextRevision = stored.reviewRevision + 1
                val nextPayload = seal(stored.assessmentId, nextAnalysis)
                if (!assessments.update(ownerId, assessmentId, stored.reviewRevision, nextPayload, nextRevision)) {
                    throw ConflictException(REVIEW_REVISION_CONFLICT_MESSAGE)
                }
                stored.copy(reviewRevision = nextRevision, payload = nextPayload)
            }
        return ReviewSupportView(ReviewSupportStatus.READY, open(updated))
    }

    private fun documentIdOf(
        ownerId: UUID,
        conversionId: UUID,
    ): UUID =
        conversions.findOwnedResult(ownerId, conversionId)?.documentId
            ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)

    private fun currentBody(locked: LockedConversion): PlainBody? =
        locked.envelope.ciphertexts.editedText
            ?.let { cipher.decrypt(it, locked.envelope.conversionId, EncryptedField.CONVERSION_EDITED_TEXT) }
            ?: locked.envelope.ciphertexts.easyText
                ?.let { cipher.decrypt(it, locked.envelope.conversionId, EncryptedField.CONVERSION_EASY_TEXT) }

    private fun seal(
        id: UUID,
        analysis: ReviewAnalysis,
    ): EncryptedContent =
        cipher.encrypt(PlainBody(ReviewPayloadCodec.encode(analysis)), id, EncryptedField.REVIEW_ASSESSMENT_PAYLOAD)

    private fun openAnalysis(stored: StoredReviewAssessment): ReviewAnalysis =
        ReviewPayloadCodec.decode(
            cipher.decrypt(stored.payload, stored.assessmentId, EncryptedField.REVIEW_ASSESSMENT_PAYLOAD).value,
        )

    private fun open(stored: StoredReviewAssessment): ReviewAssessmentView {
        val value = openAnalysis(stored)
        return ReviewAssessmentView(
            stored.assessmentId,
            stored.contentRevision,
            stored.analyzerVersion,
            stored.reviewRevision,
            value.coverage,
            value.limitedReasons,
            value.items,
        )
    }

    private fun normalizeReason(
        state: ReviewItemState,
        reason: String?,
    ): String? {
        val normalized = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized != null && charCountOf(normalized) > MAX_REASON_CHARS) {
            throw InvalidInputException(REVIEW_REASON_TOO_LONG_MESSAGE)
        }
        if (state == ReviewItemState.NOT_APPLICABLE && normalized == null) {
            throw InvalidInputException(REVIEW_REASON_REQUIRED_MESSAGE)
        }
        return normalized
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private fun requireContentRevision(value: Long) {
        if (value < 1 || value > MAX_SAFE_REVISION) throw InvalidInputException(CONTENT_REVISION_INVALID_MESSAGE)
    }

    companion object {
        const val ANALYZER_VERSION = "fact-preservation-v1"
        const val MAX_REASON_CHARS = 500
    }
}

private object ReviewPayloadCodec {
    fun encode(value: ReviewAnalysis): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(value.coverage.name)
            output.writeInt(value.limitedReasons.size)
            value.limitedReasons.forEach { output.writeUTF(it.name) }
            output.writeInt(value.items.size)
            value.items.forEach { item ->
                output.writeUTF(item.itemId.toString())
                output.writeUTF(item.kind.name)
                output.writeUTF(item.ruleCode)
                output.writeInt(item.sourceAnchors.size)
                item.sourceAnchors.forEach { anchor ->
                    output.writeInt(anchor.sourceUnitIndexes.size)
                    anchor.sourceUnitIndexes.forEach(output::writeInt)
                    output.writeLongText(anchor.quote)
                }
                output.writeInt(item.easyUnitIndexes.size)
                item.easyUnitIndexes.forEach(output::writeInt)
                output.writeUTF(item.state.name)
                output.writeNullable(item.reason)
                output.writeNullable(item.confirmedBy?.toString())
                output.writeNullable(item.confirmedAt?.toString())
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): ReviewAnalysis =
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val coverage = ReviewCoverage.valueOf(input.readUTF())
            val limits = List(input.readInt()) { ReviewCoverageLimit.valueOf(input.readUTF()) }
            val items =
                List(input.readInt()) {
                    val itemId = UUID.fromString(input.readUTF())
                    val kind = ReviewItemKind.valueOf(input.readUTF())
                    val ruleCode = input.readUTF()
                    val anchors =
                        List(input.readInt()) {
                            val indexes = List(input.readInt()) { input.readInt() }
                            SourceAnchor(indexes, input.readLongText())
                        }
                    val easyIndexes = List(input.readInt()) { input.readInt() }
                    val state = ReviewItemState.valueOf(input.readUTF())
                    val reason = input.readNullable()
                    val confirmedBy = input.readNullable()?.let(UUID::fromString)
                    val confirmedAt = input.readNullable()?.let(Instant::parse)
                    ReviewItem(itemId, kind, ruleCode, anchors, easyIndexes, state, reason, confirmedBy, confirmedAt)
                }
            ReviewAnalysis(coverage, limits, items)
        }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    /** `DataOutput.writeUTF`의 65,535바이트 제한을 넘을 수 있는 원문 근거용 길이-prefix UTF-8. */
    private fun DataOutputStream.writeLongText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ANCHOR_BYTES) { "검수 원문 근거가 너무 깁니다" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readLongText(): String {
        val size = readInt()
        require(size in 0..MAX_ANCHOR_BYTES) { "검수 원문 근거 길이가 올바르지 않습니다" }
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private const val MAX_ANCHOR_BYTES = 80_000
}

const val REVIEW_REVISION_CONFLICT_MESSAGE: String = "검수 표시가 바뀌었습니다. 다시 불러와 주세요"
const val REVIEW_ITEM_NOT_FOUND_MESSAGE: String = "검수 항목을 찾을 수 없습니다"
const val REVIEW_REASON_TOO_LONG_MESSAGE: String = "메모는 500자 이하여야 합니다"
const val REVIEW_REASON_REQUIRED_MESSAGE: String = "해당 없음에는 메모가 필요합니다"
const val REVIEW_SUPPORT_STORAGE_MESSAGE: String = "검수 결과를 저장하지 못했습니다"
const val CONTENT_REVISION_INVALID_MESSAGE: String = "본문 버전이 올바르지 않습니다"
const val REVIEW_REVISION_INVALID_MESSAGE: String = "검수 버전이 올바르지 않습니다"

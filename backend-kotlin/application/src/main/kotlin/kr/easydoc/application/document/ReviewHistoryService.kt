package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReviewHistorySnapshotView(
    val status: String,
    val kind: ReviewHistorySnapshotKind?,
    val contentText: String?,
    val artifactJson: String?,
) {
    override fun toString(): String =
        "ReviewHistorySnapshotView(status=$status, kind=${kind?.wireName}, " +
            "contentText=${contentText?.length ?: 0}자, artifactJson=${artifactJson?.length ?: 0}자)"
}

data class ReviewHistoryEventView(
    val eventId: UUID,
    val eventType: ReviewHistoryEventType,
    val createdAt: Instant,
    val actorUserId: UUID,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val itemId: UUID?,
    val assessmentId: UUID?,
    val guideId: UUID?,
    val snapshot: ReviewHistorySnapshotView,
)

data class ReviewHistoryPageView(
    val conversionId: UUID,
    val currentContentRevision: Long,
    val events: List<ReviewHistoryEventView>,
    val nextCursor: String?,
)

private data class ReviewHistoryAppendRequest(
    val ownerId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val itemId: UUID?,
    val assessmentId: UUID?,
    val guideId: UUID?,
    val eventType: ReviewHistoryEventType,
    val snapshot: ReviewHistorySnapshotValue,
)

/** R5's one write path. Its clock is server-owned and its snapshot encoder is fail-closed. */
class DefaultReviewHistoryAppender(
    private val enabled: Boolean,
    private val repository: ReviewHistoryRepository,
    private val cipher: ContentCipher,
    private val clock: Clock = Clock.systemUTC(),
) : ReviewHistoryAppender {
    override fun appendItemEvent(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        assessmentId: UUID,
        itemId: UUID,
        reviewRevision: Long,
        type: ReviewHistoryEventType,
        contentText: String?,
        artifactJson: String?,
    ) {
        require(
            type == ReviewHistoryEventType.ITEM_CONFIRMED ||
                type == ReviewHistoryEventType.ITEM_REOPENED ||
                type == ReviewHistoryEventType.ITEM_NOT_APPLICABLE,
        ) {
            "항목 변경 기록에 맞지 않는 이벤트입니다"
        }
        append(
            ReviewHistoryAppendRequest(
                ownerId = ownerId,
                conversionId = conversionId,
                contentRevision = contentRevision,
                artifactRevision = reviewRevision,
                itemId = itemId,
                assessmentId = assessmentId,
                guideId = null,
                eventType = type,
                snapshot =
                    ReviewHistorySnapshotValue(
                        ReviewHistorySnapshotKind.REVIEW_ASSESSMENT,
                        contentText,
                        artifactJson,
                    ),
            ),
        )
    }

    override fun appendGuideReviewed(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        guideId: UUID,
        guideRevision: Long,
        contentText: String?,
        artifactJson: String?,
    ) {
        append(
            ReviewHistoryAppendRequest(
                ownerId = ownerId,
                conversionId = conversionId,
                contentRevision = contentRevision,
                artifactRevision = guideRevision,
                itemId = null,
                assessmentId = null,
                guideId = guideId,
                eventType = ReviewHistoryEventType.GUIDE_REVIEWED,
                snapshot =
                    ReviewHistorySnapshotValue(
                        ReviewHistorySnapshotKind.ACTION_GUIDE,
                        contentText,
                        artifactJson,
                    ),
            ),
        )
    }

    override fun appendInvalidatedByEdit(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        contentText: String?,
        artifactRevision: Long?,
        guideId: UUID?,
        artifactJson: String?,
    ) {
        // This event is deliberately tied to the old revision: the body in the snapshot is the
        // body that was reviewed. The caller invokes it before the conversion CAS increments the
        // revision, so a new body can never be labelled as an old review.
        append(
            ReviewHistoryAppendRequest(
                ownerId = ownerId,
                conversionId = conversionId,
                contentRevision = contentRevision,
                artifactRevision = artifactRevision,
                itemId = null,
                assessmentId = null,
                guideId = guideId,
                eventType = ReviewHistoryEventType.INVALIDATED_BY_EDIT,
                snapshot =
                    ReviewHistorySnapshotValue(
                        kind = if (artifactJson == null) null else ReviewHistorySnapshotKind.ACTION_GUIDE,
                        contentText = contentText,
                        artifactJson = artifactJson,
                    ),
            ),
        )
    }

    private fun append(request: ReviewHistoryAppendRequest) {
        if (!enabled) return
        require(request.contentRevision in 1..MAX_SAFE_REVISION)
        if (request.artifactRevision != null) require(request.artifactRevision in 0..MAX_SAFE_REVISION)
        val eventId = UUID.randomUUID()
        val encoded = ReviewHistorySnapshotCodec.encode(request.snapshot)
        val snapshotId = encoded?.let { UUID.randomUUID() }
        val sealed =
            if (snapshotId == null) {
                null
            } else {
                cipher.encryptBytes(
                    kr.easydoc.core.crypto
                        .PlainBytes(encoded),
                    snapshotId,
                    kr.easydoc.core.crypto.EncryptedField.REVIEW_HISTORY_SNAPSHOT,
                )
            }
        repository.append(
            ownerId = request.ownerId,
            event =
                ReviewHistoryEventToStore(
                    eventId = eventId,
                    conversionId = request.conversionId,
                    eventType = request.eventType,
                    actorUserId = request.ownerId,
                    createdAt = clock.instant(),
                    contentRevision = request.contentRevision,
                    artifactRevision = request.artifactRevision,
                    itemId = request.itemId,
                    assessmentId = request.assessmentId,
                    guideId = request.guideId,
                    snapshotId = snapshotId,
                ),
            snapshot =
                if (snapshotId == null || sealed == null) {
                    null
                } else {
                    ReviewHistorySnapshotToStore(
                        snapshotId = snapshotId,
                        conversionId = request.conversionId,
                        contentRevision = request.contentRevision,
                        artifactRevision = request.artifactRevision,
                        kind = request.snapshot.kind,
                        payload = sealed,
                    )
                },
        )
    }
}

/** Read and export use the same retention/ownership predicates in [ReviewHistoryRepository]. */
class ReviewHistoryService(
    private val enabled: Boolean,
    private val conversions: ConversionRepository,
    private val repository: ReviewHistoryRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun page(
        ownerId: UUID,
        conversionId: UUID,
        encodedCursor: String?,
        limit: Int,
    ): ReviewHistoryPageView {
        requireEnabled()
        if (limit !in 1..MAX_PAGE_SIZE) throw InvalidInputException(REVIEW_HISTORY_LIMIT_MESSAGE)
        return transaction.inTransaction {
            val conversion =
                conversions.lockOwnedForReview(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val cursor = encodedCursor?.let { ReviewHistoryCursorCodec.decode(it, conversionId) }
            val cutoff = cursor?.cutoff ?: clock.instant()
            val rows = repository.pageOwned(ownerId, conversionId, cutoff, cursor, limit + 1)
            val hasNext = rows.size > limit
            val visible = rows.take(limit).map(::viewOf)
            val next =
                if (!hasNext || visible.isEmpty()) {
                    null
                } else {
                    val last = rows[limit - 1]
                    ReviewHistoryCursorCodec.encode(
                        ReviewHistoryCursor(conversionId, cutoff, last.createdAt, last.eventId),
                    )
                }
            ReviewHistoryPageView(conversionId, conversion.contentRevision, visible, next)
        }
    }

    /** Export holds the conversion row lock through all pages, fixing the cutoff against writes. */
    fun export(
        ownerId: UUID,
        conversionId: UUID,
    ): ByteArray {
        requireEnabled()
        return transaction.inTransaction {
            val locked =
                conversions.lockOwnedForReview(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val cutoff = clock.instant()
            val output = ByteArrayOutputStream(EXPORT_INITIAL_CAPACITY)
            writeLine(output, "검수 기록")
            writeLine(output, "변환: $conversionId")
            writeLine(output, "현재 본문 버전: ${locked.contentRevision}")
            writeLine(output, "조회 기준 시각: $cutoff")
            writeLine(output, "안내: 인증서나 영구 감사 기록이 아닌 사용자 검수 기록입니다.")
            writeLine(output, "")
            appendExportEvents(output, ownerId, conversionId, cutoff, locked.contentRevision)
            output.toByteArray()
        }
    }

    private fun appendExportEvents(
        output: ByteArrayOutputStream,
        ownerId: UUID,
        conversionId: UUID,
        cutoff: Instant,
        currentRevision: Long,
    ) {
        var cursor: ReviewHistoryCursor? = null
        var count = 0
        var hasMore = true
        while (hasMore) {
            val rows = repository.pageOwned(ownerId, conversionId, cutoff, cursor, EXPORT_PAGE_SIZE)
            if (rows.isEmpty()) {
                hasMore = false
            } else {
                rows.forEach { row ->
                    count = appendExportEvent(output, row, currentRevision, count)
                }
                hasMore = rows.size == EXPORT_PAGE_SIZE
                if (hasMore) {
                    val last = rows.last()
                    cursor = ReviewHistoryCursor(conversionId, cutoff, last.createdAt, last.eventId)
                }
            }
        }
    }

    private fun appendExportEvent(
        output: ByteArrayOutputStream,
        row: StoredReviewHistoryEvent,
        currentRevision: Long,
        count: Int,
    ): Int {
        val nextCount = count + 1
        if (nextCount > MAX_EXPORT_EVENTS) throw ConflictException(REVIEW_HISTORY_EXPORT_TOO_LARGE_MESSAGE)
        writeEvent(output, row, currentRevision)
        if (output.size() > MAX_EXPORT_BYTES) {
            throw ConflictException(REVIEW_HISTORY_EXPORT_TOO_LARGE_MESSAGE)
        }
        return nextCount
    }

    private fun viewOf(row: StoredReviewHistoryEvent): ReviewHistoryEventView {
        val snapshot =
            row.snapshot?.let { stored ->
                val opened = ReviewHistorySnapshotCodec.decrypt(stored.payload, stored.snapshotId, cipher)
                ReviewHistorySnapshotView(
                    status = "available",
                    kind = opened.kind,
                    contentText = opened.contentText,
                    artifactJson = opened.artifactJson,
                )
            } ?: ReviewHistorySnapshotView("missing", null, null, null)
        return ReviewHistoryEventView(
            eventId = row.eventId,
            eventType = row.eventType,
            createdAt = row.createdAt,
            actorUserId = row.actorUserId,
            contentRevision = row.contentRevision,
            artifactRevision = row.artifactRevision,
            itemId = row.itemId,
            assessmentId = row.assessmentId,
            guideId = row.guideId,
            snapshot = snapshot,
        )
    }

    private fun writeEvent(
        output: ByteArrayOutputStream,
        row: StoredReviewHistoryEvent,
        currentRevision: Long,
    ) {
        val snapshot = row.snapshot?.let { ReviewHistorySnapshotCodec.decrypt(it.payload, it.snapshotId, cipher) }
        writeLine(output, "[${row.eventType.wireName}]")
        writeLine(output, "시각: ${row.createdAt}")
        writeLine(output, "담당자: ${row.actorUserId}")
        writeLine(output, "본문 버전: ${row.contentRevision}")
        if (row.contentRevision != currentRevision) {
            writeLine(output, "본문 버전 상태: 현재 본문과 다름")
        }
        row.artifactRevision?.let { writeLine(output, "산출물 버전: $it") }
        row.itemId?.let { writeLine(output, "항목: $it") }
        row.assessmentId?.let { writeLine(output, "검수 결과: $it") }
        row.guideId?.let { writeLine(output, "행동 안내문: $it") }
        if (snapshot == null) {
            writeLine(output, "스냅샷: 없음 (보존 기간 만료 또는 크기 제한)")
        } else {
            writeLine(output, "스냅샷: 있음")
            snapshot.contentText?.let {
                writeLine(output, "본문:")
                writeLine(output, it)
            }
            snapshot.artifactJson?.let {
                writeLine(output, "산출물 JSON:")
                writeLine(output, it)
            }
        }
        writeLine(output, "")
    }

    private fun writeLine(
        output: ByteArrayOutputStream,
        line: String,
    ) {
        output.write(line.toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 50
        const val EXPORT_PAGE_SIZE: Int = 100
        const val MAX_EXPORT_EVENTS: Int = 10_000
        const val MAX_EXPORT_BYTES: Int = 5 * 1024 * 1024
        const val EXPORT_INITIAL_CAPACITY: Int = 64 * 1024
        const val REVIEW_HISTORY_LIMIT_MESSAGE: String = "검수 기록 페이지 크기가 올바르지 않습니다"
        const val REVIEW_HISTORY_EXPORT_TOO_LARGE_MESSAGE: String = "검수 기록 내보내기가 허용 크기를 초과했습니다"
    }
}

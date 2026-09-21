package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import java.time.Instant
import java.util.UUID

/** R5 history event names. These values are persisted and are therefore wire-stable. */
enum class ReviewHistoryEventType(val wireName: String) {
    ITEM_CONFIRMED("item_confirmed"),
    ITEM_REOPENED("item_reopened"),
    ITEM_NOT_APPLICABLE("item_not_applicable"),
    GUIDE_REVIEWED("guide_reviewed"),
    INVALIDATED_BY_EDIT("invalidated_by_edit"),
}

enum class ReviewHistorySnapshotKind(val wireName: String) {
    REVIEW_ASSESSMENT("review_assessment"),
    ACTION_GUIDE("action_guide"),
}

/** Plaintext that may be placed in one encrypted history snapshot. */
data class ReviewHistorySnapshotValue(
    val kind: ReviewHistorySnapshotKind?,
    val contentText: String?,
    val artifactJson: String?,
) {
    fun hasContent(): Boolean = contentText != null || artifactJson != null

    override fun toString(): String =
        "ReviewHistorySnapshotValue(kind=${kind?.wireName}, contentText=${contentText?.length ?: 0}자, " +
            "artifactJson=${artifactJson?.length ?: 0}자)"
}

/** Values written by the append-only recorder. [snapshotId] is null for an unavailable snapshot. */
data class ReviewHistoryEventToStore(
    val eventId: UUID,
    val conversionId: UUID,
    val eventType: ReviewHistoryEventType,
    val actorUserId: UUID,
    val createdAt: Instant,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val itemId: UUID?,
    val assessmentId: UUID?,
    val guideId: UUID?,
    val snapshotId: UUID?,
) {
    override fun toString(): String =
        "ReviewHistoryEventToStore($eventId, conversion=$conversionId, type=${eventType.wireName}, " +
            "revision=$contentRevision, snapshot=${snapshotId != null})"
}

data class ReviewHistorySnapshotToStore(
    val snapshotId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val kind: ReviewHistorySnapshotKind?,
    val payload: EncryptedContent,
) {
    override fun toString(): String =
        "ReviewHistorySnapshotToStore($snapshotId, conversion=$conversionId, revision=$contentRevision, $payload)"
}

/** Encrypted row returned by the history store. The application opens it with [ContentCipher]. */
data class StoredReviewHistorySnapshot(
    val snapshotId: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val kind: ReviewHistorySnapshotKind?,
    val payload: EncryptedContent,
) {
    override fun toString(): String =
        "StoredReviewHistorySnapshot($snapshotId, conversion=$conversionId, revision=$contentRevision, $payload)"
}

data class StoredReviewHistoryEvent(
    val eventId: UUID,
    val conversionId: UUID,
    val eventType: ReviewHistoryEventType,
    val actorUserId: UUID,
    val createdAt: Instant,
    val contentRevision: Long,
    val artifactRevision: Long?,
    val itemId: UUID?,
    val assessmentId: UUID?,
    val guideId: UUID?,
    val snapshot: StoredReviewHistorySnapshot?,
) {
    override fun toString(): String =
        "StoredReviewHistoryEvent($eventId, conversion=$conversionId, type=${eventType.wireName}, " +
            "revision=$contentRevision, snapshot=${snapshot != null})"
}

data class ReviewHistoryCursor(
    val conversionId: UUID,
    val cutoff: Instant,
    val createdAt: Instant,
    val eventId: UUID,
)

interface ReviewHistoryRepository {
    /** Inserts the event and optional snapshot, then prunes snapshots outside the 20-version window. */
    fun append(
        ownerId: UUID,
        event: ReviewHistoryEventToStore,
        snapshot: ReviewHistorySnapshotToStore?,
    )

    /** Reads at most [limit] rows after [after], with a fixed upper [cutoff]. */
    fun pageOwned(
        ownerId: UUID,
        conversionId: UUID,
        cutoff: Instant,
        after: ReviewHistoryCursor?,
        limit: Int,
    ): List<StoredReviewHistoryEvent>

    /** Key rotation primitives. The batch owns the transaction boundary. */
    fun lockSnapshot(snapshotId: UUID): StoredReviewHistorySnapshot?

    fun rewriteSnapshotEnvelope(
        expected: StoredReviewHistorySnapshot,
        payload: EncryptedContent,
    ): Boolean

    fun snapshotIdsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID>
}

/** Mutation hooks stay in application so R1/R2 do not depend on the JDBC adapter. */
interface ReviewHistoryAppender {
    @Suppress("LongParameterList") // Explicit event columns keep the append-only port auditable.
    fun appendItemEvent(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        assessmentId: UUID,
        itemId: UUID,
        reviewRevision: Long,
        type: ReviewHistoryEventType,
        contentText: String?,
        artifactJson: String?,
    )

    @Suppress("LongParameterList") // Explicit event columns keep the append-only port auditable.
    fun appendGuideReviewed(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        guideId: UUID,
        guideRevision: Long,
        contentText: String?,
        artifactJson: String?,
    )

    /** Called before the conversion body CAS, while [contentRevision] still names [contentText]. */
    @Suppress("LongParameterList") // Explicit event columns keep the append-only port auditable.
    fun appendInvalidatedByEdit(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        contentText: String?,
        artifactRevision: Long? = null,
        guideId: UUID? = null,
        artifactJson: String? = null,
    )
}

/** Existing deployments can keep R1/R2 mutations working while history is disabled. */
object NoOpReviewHistoryAppender : ReviewHistoryAppender {
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
    ) = Unit

    override fun appendGuideReviewed(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        guideId: UUID,
        guideRevision: Long,
        contentText: String?,
        artifactJson: String?,
    ) = Unit

    override fun appendInvalidatedByEdit(
        ownerId: UUID,
        conversionId: UUID,
        contentRevision: Long,
        contentText: String?,
        artifactRevision: Long?,
        guideId: UUID?,
        artifactJson: String?,
    ) = Unit
}

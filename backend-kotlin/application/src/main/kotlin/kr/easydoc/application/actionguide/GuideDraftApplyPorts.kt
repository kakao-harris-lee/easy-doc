package kr.easydoc.application.actionguide

import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.PlainBody
import java.util.UUID

data class GuideDraftApplyCommand(
    val draftId: UUID,
    val requestId: UUID,
    val expectedContentRevision: Long,
    val expectedAnalysisRevision: Long,
    val expectedDraftRevision: Long,
    val expectedReviewRevision: Long,
)

data class GuideDraftApplyView(
    val contentRevision: Long,
    val previousSnapshotId: UUID,
    val replayed: Boolean = false,
)

data class GuidePreviousBodyView(
    val snapshotId: UUID,
    val draftId: UUID,
    val previousContentRevision: Long,
    val appliedContentRevision: Long,
)

/** Runs under the conversion lock. The adapter must check mode, all revisions and unresolved signals. */
fun interface GuideDraftApplySource {
    fun requireApplicable(
        ownerId: UUID,
        conversionId: UUID,
        command: GuideDraftApplyCommand,
    ): PlainBody
}

data class StoredGuideDraftApplication(
    val snapshotId: UUID,
    val conversionId: UUID,
    val command: GuideDraftApplyCommand,
    val appliedContentRevision: Long,
    val previousBody: EncryptedContent,
)

/** Mandatory snapshots and the body CAS share the caller's transaction, independent of R5 flags. */
interface GuideDraftApplyRepository {
    fun listApplications(
        ownerId: UUID,
        conversionId: UUID,
    ): List<GuidePreviousBodyView>

    fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredGuideDraftApplication?

    fun findSnapshot(
        ownerId: UUID,
        conversionId: UUID,
        snapshotId: UUID,
    ): StoredGuideDraftApplication?

    fun insertSnapshot(
        ownerId: UUID,
        application: StoredGuideDraftApplication,
    ): Boolean

    fun saveUnreviewed(
        ownerId: UUID,
        expected: ConversionEnvelope,
        updated: ConversionEnvelope,
        expectedRevision: Long,
        updatedRevision: Long,
    ): Boolean
}

package kr.easydoc.application.document

import java.util.UUID

internal class RecordingReviewHistoryAppender : ReviewHistoryAppender {
    data class ItemCall(
        val conversionId: UUID,
        val contentRevision: Long,
        val assessmentId: UUID,
        val itemId: UUID,
        val reviewRevision: Long,
        val type: ReviewHistoryEventType,
        val contentText: String?,
        val artifactJson: String?,
    )

    data class GuideCall(
        val conversionId: UUID,
        val contentRevision: Long,
        val guideId: UUID,
        val guideRevision: Long,
        val contentText: String?,
        val artifactJson: String?,
    )

    data class InvalidationCall(
        val conversionId: UUID,
        val contentRevision: Long,
        val contentText: String?,
        val artifactRevision: Long?,
        val guideId: UUID?,
        val artifactJson: String?,
    )

    val itemCalls = mutableListOf<ItemCall>()
    val guideCalls = mutableListOf<GuideCall>()
    val invalidationCalls = mutableListOf<InvalidationCall>()

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
        itemCalls +=
            ItemCall(
                conversionId,
                contentRevision,
                assessmentId,
                itemId,
                reviewRevision,
                type,
                contentText,
                artifactJson,
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
        guideCalls += GuideCall(conversionId, contentRevision, guideId, guideRevision, contentText, artifactJson)
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
        invalidationCalls +=
            InvalidationCall(conversionId, contentRevision, contentText, artifactRevision, guideId, artifactJson)
    }
}

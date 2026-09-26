package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.actionguide.GuideOutputMode
import java.time.Instant
import java.util.UUID

data class GuideDraftBlock(
    val id: String,
    val actionId: String,
    val text: String,
    val cautions: List<String>,
    val evidence: List<ActionGuideSourceAnchor>,
) {
    override fun toString(): String = "GuideDraftBlock(id=$id)"
}

data class GuideDraft(
    val draftId: UUID,
    val analysisId: UUID,
    val analysisRevision: Long,
    val analysisReviewRevision: Long,
    val basedOnContentRevision: Long,
    val draftRevision: Long,
    val mode: GuideOutputMode,
    val body: String,
    val blocks: List<GuideDraftBlock>,
    val reviewed: Boolean,
    val createdAt: Instant,
) {
    override fun toString(): String = "GuideDraft(id=$draftId, revision=$draftRevision, mode=$mode)"
}

interface GuideDraftRepository {
    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        draftId: UUID,
    ): GuideDraft?

    fun listOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): List<GuideDraft>

    fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): GuideDraft?

    fun insertOwned(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        draft: GuideDraft,
    )

    fun replaceOwned(
        ownerId: UUID,
        conversionId: UUID,
        expectedDraftRevision: Long,
        draft: GuideDraft,
    ): Boolean
}

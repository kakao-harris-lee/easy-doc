package kr.easydoc.application.admin

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/** 관리자 권한을 확인한 HTTP 경계에서만 호출하는 의견 조회 포트. */
interface AdminFeedbackQuery {
    fun list(
        page: Int,
        size: Int,
    ): AdminFeedbackPage
}

data class AdminFeedbackPage(
    val items: List<AdminFeedbackItem>,
    val total: Long,
)

data class AdminFeedbackItem(
    val conversionId: UUID,
    val userId: UUID?,
    val ownerEmail: String?,
    val publishIntent: String,
    val qualityScore: Int,
    val minutesSpent: Int,
    val comment: PlainBody?,
    val commentUnreadable: Boolean,
    val submittedAt: Instant,
) {
    override fun toString(): String =
        "AdminFeedbackItem(conversionId=$conversionId, ownerEmail=$CONTENT_MASK, comment=$CONTENT_MASK)"
}

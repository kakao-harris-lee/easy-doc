package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.checkStyle

/** 보정 채택 판정의 결과. */
data class RepairDecision(
    val accepted: Boolean,
    val originalIssueCount: Int,
    val candidateIssueCount: Int,
    val lostPlaceholders: List<String>,
)

/** 보정 결과를 채택할지 판정한다. */
fun decideRepairAdoption(
    original: String,
    candidate: String,
    placeholders: List<String>,
): RepairDecision {
    val lost = placeholders.filter { it in original && it !in candidate }
    val before = checkStyle(original).issues.size
    val after = checkStyle(candidate).issues.size
    return RepairDecision(
        accepted = lost.isEmpty() && after <= before,
        originalIssueCount = before,
        candidateIssueCount = after,
        lostPlaceholders = lost,
    )
}

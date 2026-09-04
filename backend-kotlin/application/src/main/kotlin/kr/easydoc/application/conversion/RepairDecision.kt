package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.findMissingFacts

/** 보정 채택 판정의 결과. */
data class RepairDecision(
    val accepted: Boolean,
    val originalIssueCount: Int,
    val candidateIssueCount: Int,
    val lostPlaceholders: List<String>,
    val factsMissingBefore: Int,
    val factsMissingAfter: Int,
)

/**
 * 보정 결과를 채택할지 판정한다. 자리표시자를 지키고, 문체 위반이 늘지 않고, [findMissingFacts]
 * 로 잰 사실 누락도 늘지 않아야 채택한다.
 *
 * [maskedSource] 는 실제 LLM 에 나간 마스킹된 원문이다 — **기본값이 없다.** 잊고 안 넘기면
 * 컴파일이 막혀야 한다(리뷰 MEDIUM-3) — 기본값 빈 문자열은 조용히 사실 보존 게이트를 끄는
 * 효과라 실수로도 쉽게 켜질 수 있었다. 이 판정에서 정말 사실 보존을 재지 않으려면
 * 호출부가 빈 문자열을 **명시로** 넘겨 그 선택을 코드에 남겨야 한다.
 */
fun decideRepairAdoption(
    original: String,
    candidate: String,
    placeholders: List<String>,
    maskedSource: String,
): RepairDecision {
    val lost = placeholders.filter { it in original && it !in candidate }
    val before = checkStyle(original).issues.size
    val after = checkStyle(candidate).issues.size
    val factsBefore = findMissingFacts(maskedSource, original).size
    val factsAfter = findMissingFacts(maskedSource, candidate).size
    return RepairDecision(
        accepted = lost.isEmpty() && after <= before && factsAfter <= factsBefore,
        originalIssueCount = before,
        candidateIssueCount = after,
        lostPlaceholders = lost,
        factsMissingBefore = factsBefore,
        factsMissingAfter = factsAfter,
    )
}

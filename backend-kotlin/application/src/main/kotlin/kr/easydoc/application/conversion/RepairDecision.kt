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
 * 보정 결과를 채택할지 판정한다. 자리표시자를 지키고, 문체 위반이 늘지 않고, 보정문이 새로
 * 빠뜨린 사실이 없어야 채택한다.
 *
 * [maskedSource] 는 실제 LLM 에 나간 마스킹된 원문이다 — **기본값이 없다.** 잊고 안 넘기면
 * 컴파일이 막혀야 한다(리뷰 MEDIUM-3) — 기본값 빈 문자열은 조용히 사실 보존 게이트를 끄는
 * 효과라 실수로도 쉽게 켜질 수 있었다. 이 판정에서 정말 사실 보존을 재지 않으려면
 * 호출부가 빈 문자열을 **명시로** 넘겨 그 선택을 코드에 남겨야 한다.
 *
 * **사실 누락은 건수가 아니라 집합으로 비교한다**(리뷰 HIGH-3). 원문이 두 사실(예: 3명·4층)을
 * 담고 있는데 1차 결과가 3명을 빠뜨렸고, 보정문이 3명은 되살렸지만 4층을 새로 빠뜨렸다면
 * 건수는 1→1 로 같아 보이지만 실제로는 **다른 사실이 새로 사라진 것**이다. 그래서 후보를
 * 받아들이는 조건은 "후보가 아직도 빠뜨린 사실의 집합이 1차 결과가 빠뜨렸던 집합의
 * 부분집합"이다 — 새로 사라진 사실이 하나도 없어야 한다. 이 판단은 [FactIssue] 를 값으로
 * 비교하는데, 두 [findMissingFacts] 호출이 같은 [maskedSource] 에서 뽑은 원문 사실을 기준으로
 * 삼기 때문에(결정적 추출) 같은 원문 사실이면 항상 같은 [FactIssue] 값이 나온다.
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
    val factsMissingBefore = findMissingFacts(maskedSource, original)
    val factsMissingAfter = findMissingFacts(maskedSource, candidate)
    val noNewFactMissing = factsMissingBefore.toSet().containsAll(factsMissingAfter.toSet())
    return RepairDecision(
        accepted = lost.isEmpty() && after <= before && noNewFactMissing,
        originalIssueCount = before,
        candidateIssueCount = after,
        lostPlaceholders = lost,
        factsMissingBefore = factsMissingBefore.size,
        factsMissingAfter = factsMissingAfter.size,
    )
}

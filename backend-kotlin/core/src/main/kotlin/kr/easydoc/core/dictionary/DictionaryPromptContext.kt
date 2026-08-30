package kr.easydoc.core.dictionary

import kr.easydoc.core.document.charCountOf

// 프롬프트 컨텍스트의 **선별과 예산** — `lookup.py` 의 `build_prompt_context` 중 무엇을 실을지
// 정하는 절반이다. 실제 줄을 찍는 절반은 `DictionaryContextLines.kt` 에 있다.

/** 잘림에서 살아남는 순서 — 위험도 → priority 내림차순 (§7.2). */
private val BY_IMPORTANCE: Comparator<DictionaryMatch> =
    compareByDescending<DictionaryMatch> { it.entry.risk.weight }
        .thenByDescending { it.entry.priority }

/**
 * 매칭 결과를 프롬프트 컨텍스트 블록으로 렌더링한다 (§7.2).
 *
 * 전체 사전이 아니라 **이 문서에 실제로 등장한 용어만** 담고, 전략별로 세 구역(바꿔 쓰세요 /
 * 원래 말은 남기고… / 절대 바꾸지 마세요)으로 나누며, 같은 엔트리가 여러 번 나와도 한 번만
 * 싣는다. 표기는 §6.6 대로 **표제어 원형**이다 — 활용은 LLM 이 처리한다.
 *
 * ## 예산이 모자랄 때
 *
 * ① 예문을 먼저 줄이고 ② 그래도 넘치면 중요도가 낮은 항목부터 **통째로 제거**한다.
 * **각 항목의 상세도는 절대 낮추지 않는다**(§7.2.1 불변식) — 상세도를 낮추면 고위험 항목의
 * `caution` 이 가장 먼저 사라져, 이 기능이 막으려던 사고를 예산 부족 상황에서 스스로
 * 재발시킨다. 무엇이든 잘렸으면 마지막 줄에 그 사실을 명시한다. 조용히 자르면 LLM 이 사전을
 * 완전한 것으로 오인한다.
 */
internal fun renderDictionaryPromptContext(
    text: String,
    matches: List<DictionaryMatch>,
    policy: DictionaryContextPolicy,
): String {
    // 최초 등장분만 남긴다 — 이 시점의 순서가 곧 문서 등장 순서다.
    val unique = matches.distinctBy { it.entryId }
    val reservedIds = reservedSubstituteIds(unique, policy.minSubstitute)
    val truncatedByTerms = unique.size > policy.maxTerms
    val kept = if (truncatedByTerms) keepTopTerms(unique, reservedIds, policy.maxTerms) else unique

    // 예약석을 앞쪽에 몰아 둔다. 아래 항목 제거가 이 리스트의 **끝**부터 잘라내므로,
    // 이렇게 세워 두면 비예약석이 전부 소진된 뒤에야 예약석에 닿는다.
    val ranked =
        kept.filter { it.entryId in reservedIds }.sortedWith(BY_IMPORTANCE) +
            kept.filterNot { it.entryId in reservedIds }.sortedWith(BY_IMPORTANCE)

    return fitToBudget(
        ranked = ranked,
        totalFound = unique.size,
        termTruncated = truncatedByTerms,
        budget = effectiveMaxChars(text, policy),
        maxExamples = policy.maxExamples,
    )
}

/**
 * 실제 문자 상한. `maxCharsRatio` 는 `maxChars` 와 같은 파이프라인을 타는 **또 하나의 후보
 * 상한**일 뿐이라, 지정된 것들 중 가장 작은 값이 실제 상한이 된다.
 */
private fun effectiveMaxChars(
    text: String,
    policy: DictionaryContextPolicy,
): Int? {
    val ratioChars = policy.maxCharsRatio?.let { (charCountOf(text) * it).toInt() }
    return listOfNotNull(policy.maxChars, ratioChars).minOrNull()
}

/**
 * 문서에서 매칭된 `substitute` 중 priority 상위 `minSubstitute` 건 — 잘림에서 마지막까지
 * 보호할 "예약석"이다.
 */
private fun reservedSubstituteIds(
    unique: List<DictionaryMatch>,
    minSubstitute: Int,
): Set<Int> =
    if (minSubstitute <= 0) {
        emptySet()
    } else {
        unique
            .filter { it.entry.strategy == ReplaceStrategy.SUBSTITUTE }
            .sortedByDescending { it.entry.priority }
            .take(minSubstitute)
            .mapTo(HashSet()) { it.entryId }
    }

/**
 * `maxTerms` 상한에 맞춰 남길 항목을 고른다.
 *
 * 예약석을 먼저 채우고 **남는 슬롯**을 기존 위험도 → priority 순으로 채운다. 상한 자체는 여전히
 * 지키므로, 예약석이 상한보다 많으면 예약석 안에서도 중요도 순으로 잘린다.
 */
private fun keepTopTerms(
    unique: List<DictionaryMatch>,
    reservedIds: Set<Int>,
    maxTerms: Int,
): List<DictionaryMatch> {
    val reserved = unique.filter { it.entryId in reservedIds }.sortedWith(BY_IMPORTANCE)
    val others = unique.filterNot { it.entryId in reservedIds }.sortedWith(BY_IMPORTANCE)
    return if (reserved.size >= maxTerms) {
        reserved.take(maxTerms)
    } else {
        reserved + others.take(maxTerms - reserved.size)
    }
}

/**
 * 문자 예산에 들 때까지 후퇴하며 렌더링한다.
 *
 * 예산을 못 맞추는 채로 후퇴가 끝나면 **마지막 시도**를 그대로 돌려준다 — 머리말과 구역 제목,
 * 잘림 안내만으로도 넘는 물리적 하한이 있어서, 그보다 작은 예산은 항목을 0개로 줄여도 충족할
 * 수 없다. 그때는 빈 문자열보다 하한짜리 블록이 낫다.
 */
private fun fitToBudget(
    ranked: List<DictionaryMatch>,
    totalFound: Int,
    termTruncated: Boolean,
    budget: Int?,
    maxExamples: Int,
): String {
    val first = renderContextBlock(ranked, maxExamples, termTruncated, totalFound)
    if (budget == null || charCountOf(first) <= budget) return first

    // 여기부터는 예산 때문에 반드시 뭔가 잘리므로 잘림 안내를 항상 켠다.
    var best = first
    for (fallback in budgetFallbacks(ranked, totalFound, maxExamples)) {
        best = fallback
        if (charCountOf(fallback) <= budget) break
    }
    return best
}

/** 후퇴 순서 — ① 예문 감축, ② 중요도가 낮은 항목부터(ranked 의 끝) 제거. 지연 평가한다. */
private fun budgetFallbacks(
    ranked: List<DictionaryMatch>,
    totalFound: Int,
    maxExamples: Int,
): Sequence<String> =
    sequence {
        for (limit in (maxExamples - 1) downTo 0) {
            yield(renderContextBlock(ranked, limit, showNotice = true, totalFound = totalFound))
        }
        var selected = ranked
        while (selected.isNotEmpty()) {
            selected = selected.dropLast(1)
            yield(renderContextBlock(selected, 0, showNotice = true, totalFound = totalFound))
        }
    }

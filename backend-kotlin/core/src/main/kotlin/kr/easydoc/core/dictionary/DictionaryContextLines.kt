package kr.easydoc.core.dictionary

import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.text.unicodeRegex

// 사전 후보의 뜻과 문맥을 확인할 정보를 렌더링한다.
// 2026-09-11: Kotlin 제품 정책으로 개정했다. Python 문자열 동등성은 유지하지 않는다.
// 변경한 프롬프트의 품질은 과거 측정으로 입증되지 않으며 고정 문서 회귀 픽스처를 함께 갱신한다.

private const val CONTEXT_HEADER =
    "## 어려운 말 참고 (문맥에 맞는 뜻만 사용하세요)"

private const val SUBSTITUTE_SECTION_TITLE = "### 쉬운 표현 후보"

private const val GLOSS_SECTION_TITLE =
    "### 뜻풀이 참고"

/**
 * GLOSS 구역 제목 바로 다음 줄에 무조건 찍는 안내 한 줄 (2026-09-09).
 *
 * 5차 유료 측정에서 easy-dictionary의 `환수`(`easy_term=되거둠`, `risk=high`)가 항목 줄
 * (`- 환수 — 뜻: 되거둠`)을 치환표로 오독한 모델에 의해 원형을 하나도 남기지 않고 전부
 * "되거두다"로 치환됐다(문서 022 8회, 문서 045 4회) — 「되거두다」는 표준 국어 낱말이 아니다.
 * 구역 제목은 이미 "원래 말을 지우거나 괄호로 붙이지 마세요"라고 말하지만, 실패 지점은
 * 항목 줄 자체였다: `— 뜻: {easy_term}` 형태가 갈아 끼울 문자열을 그대로 쥐여 준다. 게다가
 * `easy_term`이 어색한 말일 수 있다는 것도 프롬프트가 말하지 않았다. 이 안내는 그 두 가지를
 * 보완한다 — "뜻:"은 힌트일 뿐이고, 힌트 자체가 어색하면 자연스럽게 풀어 쓰라고 명시한다.
 */
private const val GLOSS_SECTION_NOTE =
    "「뜻:」은 설명에 쓸 힌트일 뿐입니다. 그 말을 원래 말 자리에 넣지 마세요. " +
        "힌트가 어색한 말이면 자연스러운 다른 표현으로 풀어 쓰세요."

/**
 * [GLOSS_SECTION_NOTE] 한 줄이 렌더링 문자열에서 차지하는 길이(안내 문장 + 줄바꿈 1개).
 *
 * 상수를 하드코딩하지 않고 안내 문장 자체에서 계산한다 — 문구가 바뀌면 면제 길이도 함께
 * 움직여야지, 둘이 따로 놀면 예산 판정이 조용히 틀어진다. [budgetedCharCount] 가 이 값을 뺀다.
 */
private val GLOSS_SECTION_NOTE_BUDGET_EXEMPTION: Int = charCountOf(GLOSS_SECTION_NOTE) + 1

/**
 * 문자 예산 **판정**에 쓸 길이 (2026-09-09, 사용자 결정).
 *
 * [GLOSS_SECTION_NOTE]는 지시문이지 사전에서 뽑은 낱말 정보가 아니다. 렌더링에는 그대로
 * 남기되(구역 제목과 같은 조건으로 무조건 찍히므로 이 차감이 항상 정확하다), `DictionaryPromptContext`
 * 의 예산 비교는 이 함수로 재서 안내 줄의 길이를 뺀 값과 예산을 견준다.
 *
 * 그러지 않으면 안내 한 줄이 예산 상한에 걸려 있던 문서에서 낱말 항목 자리를 빼앗는다 —
 * 이 안내를 처음 추가했을 때 재생성한 참조 픽스처 58건 중 18건에서 낱말 28개가 실제로
 * 빠졌었다(2026-09-09 실측). 안내 줄은 지시문일 뿐 사전이 문서에서 실제로 찾아낸 내용이
 * 아니므로, 그 낱말들이 밀려나는 것은 이 기능(§7.2 계층적 상세도)의 목적 자체를 예산 부족
 * 상황에서 스스로 훼손하는 것과 같다. 렌더링 문자열(`renderContextBlock`)은 손대지 않고
 * 예산 비교 쪽에서만 보정한다.
 */
internal fun budgetedCharCount(text: String): Int = charCountOf(text) - GLOSS_SECTION_NOTE_BUDGET_EXEMPTION

private const val KEEP_SECTION_TITLE = "### 공식 이름 참고"

private const val EXAMPLE_SECTION_TITLE = "### 참고 예문"

/**
 * "설명:" 줄 중복 판정용 정규화. 공백·마침표 차이만 있는 문자열도 같다고 본다 —
 * 한국어기초사전 유래 엔트리는 `definition` 에 문장부호가 붙어 있곤 해서, 그 차이만으로
 * "다른 문장"이라 오판하면 head 에 이미 나온 말을 바로 아래에 또 싣게 된다.
 */
private val DEDUP_STRIP = unicodeRegex("""[\s.]+""")

/** 선별이 끝난 항목들을 블록 한 장으로 찍는다. */
internal fun renderContextBlock(
    selected: List<DictionaryMatch>,
    exampleLimit: Int,
    showNotice: Boolean,
    totalFound: Int,
    markReviewedDefinitions: Boolean = false,
): String {
    val ordered = selected.sortedBy { it.start }
    val lines = ArrayList<String>()
    lines += CONTEXT_HEADER
    lines += ""
    appendSection(
        lines,
        SUBSTITUTE_SECTION_TITLE,
        ordered,
        ReplaceStrategy.SUBSTITUTE,
        markReviewedDefinitions = markReviewedDefinitions,
    )
    appendSection(
        lines,
        GLOSS_SECTION_TITLE,
        ordered,
        ReplaceStrategy.GLOSS,
        sectionNote = GLOSS_SECTION_NOTE,
        markReviewedDefinitions = markReviewedDefinitions,
    )
    appendSection(
        lines,
        KEEP_SECTION_TITLE,
        ordered,
        ReplaceStrategy.KEEP,
        markReviewedDefinitions = markReviewedDefinitions,
    )
    appendExamples(lines, selected, exampleLimit)
    if (showNotice) lines += truncationNotice(totalFound, selected.size)

    // 마지막 구역이 비어 있으면 빈 줄이 꼬리에 남는다. 그것만 걷어내고 개행 하나로 끝낸다.
    return lines.joinToString("\n").trimEnd('\n') + "\n"
}

@Suppress("LongParameterList")
private fun appendSection(
    lines: MutableList<String>,
    title: String,
    ordered: List<DictionaryMatch>,
    strategy: ReplaceStrategy,
    sectionNote: String? = null,
    markReviewedDefinitions: Boolean = false,
) {
    lines += title
    if (sectionNote != null) lines += sectionNote
    ordered
        .filter { it.entry.strategy == strategy }
        .forEach { lines += renderTermLine(it, markReviewedDefinitions) }
    lines += ""
}

private fun appendExamples(
    lines: MutableList<String>,
    selected: List<DictionaryMatch>,
    limit: Int,
) {
    val examples = collectExamples(selected, limit)
    if (examples.isEmpty()) return
    lines += EXAMPLE_SECTION_TITLE
    examples.forEach { example ->
        lines += "- 전: ${example.before}"
        lines += "  후: ${example.after}"
    }
    lines += ""
}

private fun truncationNotice(
    totalFound: Int,
    shown: Int,
): String =
    "(용어 ${totalFound}개 중 ${shown}개만 표시했습니다. " +
        "위험도·우선순위가 높은 항목을 우선했으며, 일부가 생략되었습니다.)"

/**
 * 매칭 한 건을 항목 하나로 렌더링한다.
 *
 * `definition` 이 `easy_term` 과 실질적으로 같으면 "설명:" 줄을 생략한다 — head 에 이미
 * `easy_term` 이 나와 있는데 바로 아래에 같은 문장을 또 보여주면 토큰 낭비이고 LLM 에게도
 * 같은 말의 반복이라 혼란만 준다. 값이 없으면 그 줄 자체를 만들지 않는다(빈 "설명:"/"주의:"
 * 줄로 토큰을 쓰지 않는다).
 */
private fun renderTermLine(
    match: DictionaryMatch,
    markReviewedDefinition: Boolean = false,
): String {
    val entry = match.entry
    val head =
        when (entry.strategy) {
            ReplaceStrategy.SUBSTITUTE -> "- ${entry.term} — 뜻: ${entry.easyTerm}"
            ReplaceStrategy.GLOSS -> "- ${entry.term} — 뜻: ${entry.easyTerm}"
            ReplaceStrategy.KEEP -> "- ${entry.term}"
        }

    val extra = ArrayList<String>()
    val definition = entry.definition
    if (!definition.isNullOrEmpty() && normalizeForDedup(definition) != normalizeForDedup(entry.easyTerm)) {
        extra +=
            if (markReviewedDefinition) {
                "  설명(검수된 정의): $definition"
            } else {
                "  설명: $definition"
            }
    }
    // caution은 사람을 위한 검수 메모다. 다른 사업의 조건·금액이 섞여 있어 생성에는 싣지 않는다.
    // 원본 데이터와 조회 응답에서는 보존한다. 일반적인 뜻은 definition으로 제공한다.
    return (listOf(head) + extra).joinToString("\n")
}

/**
 * 예문을 `limit` 개 고른다.
 *
 * `isGolden`(사람 검수 완료) 예문만 사용하고, 엔트리 priority 가 높은 쪽을
 * 먼저 채택한다. 엔트리별 예문 수는 색인이 이미 캡을 씌워 배포하므로 여기서는 상위 `limit` 개만
 * 고르면 된다.
 *
 * **`gloss` 엔트리의 예문은 풀에서 아예 뺀다**(§7.2.2). 그 예문은 `원어(easy_term)` 괄호 병기
 * 형식으로 합성돼 있는데, 이는 gloss 구역 제목의 "괄호로 붙이지 마세요" 지시와 정반대인
 * few-shot 이 된다 — 지시문보다 강한 예문이 지시문과 모순되면 실측에서 예문이 이겼다.
 * `substitute` 예문은 형식이 문제되지 않으므로 그대로 둔다.
 */
private fun collectExamples(
    selected: List<DictionaryMatch>,
    limit: Int,
): List<DictionaryExample> =
    if (limit <= 0) {
        emptyList()
    } else {
        selected
            .distinctBy { it.entryId }
            .filter { it.entry.strategy != ReplaceStrategy.GLOSS }
            .flatMap { match -> match.entry.examples.map { match to it } }
            .filter { it.second.isGolden }
            .sortedByDescending { it.first.entry.priority }
            .take(limit)
            .map { it.second }
    }

private fun normalizeForDedup(value: String): String = DEDUP_STRIP.replace(value, "")

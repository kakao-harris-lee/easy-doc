package kr.easydoc.core.dictionary

import kr.easydoc.core.text.unicodeRegex

// 프롬프트 컨텍스트의 **줄 찍기** — 무엇을 실을지는 `DictionaryPromptContext.kt` 가 정한다.
//
// **여기의 출력 문자열은 계약이다.** 구역 제목·항목 형식·잘림 안내 문구를 바꾸면 프롬프트가
// 바뀌고, 그 순간 A/B 로 재 둔 스타일 통과율·사실 보존율의 근거가 사라진다. 문구를 손볼 때는
// 참조 구현(`lookup.py`)과 `DictionaryPromptContextTest` 를 함께 고친다.
//
// ## gloss_style 은 "sentence" 하나만 이식한다
//
// 참조 구현에는 `paren`(`{term} → {term}({easy_term})`)도 있지만 **이식하지 않는다.** 그 형식은
// head 자체가 `원문(설명)` 괄호 템플릿을 few-shot 으로 보여줘서 easy-doc 의 별도 스타일 규칙
// ("괄호는 풀어 쓰라")과 정면으로 충돌했고, 실측에서 스타일 통과율이 83.9% → 51.8% 로 무너지며
// 보정 패스가 56/56 발동했다. 폐기된 형식을 옵션으로 남기면 언젠가 누가 켠다.

private const val CONTEXT_HEADER = "## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)"

private const val SUBSTITUTE_SECTION_TITLE = "### 바꿔 쓰세요"

private const val GLOSS_SECTION_TITLE =
    "### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)"

private const val KEEP_SECTION_TITLE = "### 절대 바꾸지 마세요"

private const val EXAMPLE_SECTION_TITLE = "### 참고 예문"

/**
 * "이유:" 줄 중복 판정용 정규화. 공백·마침표 차이만 있는 문자열도 같다고 본다 —
 * 한국어기초사전 유래 엔트리는 `definition` 에 문장부호가 붙어 있곤 해서, 그 차이만으로
 * "다른 문장"이라 오판하면 head 에 이미 나온 말을 바로 아래에 또 싣게 된다.
 */
private val DEDUP_STRIP = unicodeRegex("""[\s.]+""")

/** 항목의 상세도 등급. **예산이 모자라도 이 등급은 낮추지 않는다**(§7.2.1 불변식). */
private enum class DetailTier { MIN, MID, MAX }

/** 선별이 끝난 항목들을 블록 한 장으로 찍는다. */
internal fun renderContextBlock(
    selected: List<DictionaryMatch>,
    exampleLimit: Int,
    showNotice: Boolean,
    totalFound: Int,
): String {
    val ordered = selected.sortedBy { it.start }
    val lines = ArrayList<String>()
    lines += CONTEXT_HEADER
    lines += ""
    appendSection(lines, SUBSTITUTE_SECTION_TITLE, ordered, ReplaceStrategy.SUBSTITUTE)
    appendSection(lines, GLOSS_SECTION_TITLE, ordered, ReplaceStrategy.GLOSS)
    appendSection(lines, KEEP_SECTION_TITLE, ordered, ReplaceStrategy.KEEP)
    appendExamples(lines, selected, exampleLimit)
    if (showNotice) lines += truncationNotice(totalFound, selected.size)

    // 마지막 구역이 비어 있으면 빈 줄이 꼬리에 남는다. 그것만 걷어내고 개행 하나로 끝낸다.
    return lines.joinToString("\n").trimEnd('\n') + "\n"
}

private fun appendSection(
    lines: MutableList<String>,
    title: String,
    ordered: List<DictionaryMatch>,
    strategy: ReplaceStrategy,
) {
    lines += title
    ordered
        .filter { it.entry.strategy == strategy }
        .forEach { lines += renderTermLine(it) }
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
 * `definition` 이 `easy_term` 과 실질적으로 같으면 "이유:" 줄을 생략한다 — head 에 이미
 * `easy_term` 이 나와 있는데 바로 아래에 같은 문장을 또 보여주면 토큰 낭비이고 LLM 에게도
 * 같은 말의 반복이라 혼란만 준다. 값이 없으면 그 줄 자체를 만들지 않는다(빈 "이유:"/"주의:"
 * 줄로 토큰을 쓰지 않는다).
 */
private fun renderTermLine(match: DictionaryMatch): String {
    val entry = match.entry
    val head =
        when (entry.strategy) {
            ReplaceStrategy.SUBSTITUTE -> "- ${entry.term} → ${entry.easyTerm}"
            ReplaceStrategy.GLOSS -> "- ${entry.term} — 뜻: ${entry.easyTerm}"
            ReplaceStrategy.KEEP -> "- ${entry.term}"
        }

    val tier = detailTierFor(entry)
    if (tier == DetailTier.MIN) return head

    val extra = ArrayList<String>()
    val definition = entry.definition
    if (!definition.isNullOrEmpty() && normalizeForDedup(definition) != normalizeForDedup(entry.easyTerm)) {
        extra += "  이유: $definition"
    }
    val caution = entry.caution
    if (tier == DetailTier.MAX && !caution.isNullOrEmpty()) {
        extra += "  주의: $caution"
    }
    return (listOf(head) + extra).joinToString("\n")
}

/**
 * 전략·위험도로 상세도를 정한다.
 *
 * 전략별 기본값은 substitute(안전, 설명 불필요)=최소, gloss(중간 설명)=중간, keep(가장 위험,
 * 최대 설명)=최대다. 여기에 `risk=high` 는 전략과 무관하게 최대로 끌어올린다 — 위험한 용어는
 * gloss 든 substitute 든 근거와 주의사항이 빠지면 안 된다. 반대로 `substitute` + `risk=low` 는
 * 최소에 그대로 둔다: substitute 자체가 "지워도 안전"이라는 판정이라, `high` 만큼의 명백한 위험
 * 신호가 아니면 최소 상세를 유지해 토큰을 아낀다.
 */
private fun detailTierFor(entry: DictionaryEntry): DetailTier {
    val base =
        when (entry.strategy) {
            ReplaceStrategy.SUBSTITUTE -> DetailTier.MIN
            ReplaceStrategy.GLOSS -> DetailTier.MID
            ReplaceStrategy.KEEP -> DetailTier.MAX
        }
    return if (entry.risk == RiskLevel.HIGH) DetailTier.MAX else base
}

/**
 * 예문을 `limit` 개 고른다.
 *
 * `isGolden`(사람 검수 완료)을 우선하고, 같은 우선순위 안에서는 엔트리 priority 가 높은 쪽을
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
            .sortedWith(compareBy({ !it.second.isGolden }, { -it.first.entry.priority }))
            .take(limit)
            .map { it.second }
    }

private fun normalizeForDedup(value: String): String = DEDUP_STRIP.replace(value, "")

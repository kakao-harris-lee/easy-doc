package kr.easydoc.core.easyread

/**
 * 보정 대상으로 삼을 문체 지적이다. 원문과 정확히 같은 첨부파일 이름은 편집할 수 없는
 * 이름으로 취급하고, 그 바깥의 설명은 기존 문체 규칙으로 검사한다. 파일 이름이 바뀌거나
 * 빠진 경우는 사실 검사에서 따로 검출한다. 일반 용어를 설명했다는 추측으로 면제하지 않는다.
 */
fun checkRepairStyle(
    source: String,
    draft: String,
): StyleCheckResult {
    val names =
        extractFacts(source)
            .filter { it.kind == FactKind.DOCUMENT_NAME }
            .map { it.displayValue.trimStart('-', '*', '•').trim() }
            .distinct()
    if (names.isEmpty()) return checkStyle(draft)
    val sentences = splitSentences(draft)
    val issues =
        sentences.flatMap { sentence ->
            val editable = names.fold(sentence) { text, name -> text.replace(name, "서류") }
            checkStyle(editable).issues.map { it.copy(sentence = sentence) }
        }
    return StyleCheckResult(sentences.size, issues)
}

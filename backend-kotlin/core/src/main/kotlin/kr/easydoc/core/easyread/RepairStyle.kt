package kr.easydoc.core.easyread

/**
 * 자동 보정 대상으로 삼을 문체 지적이다. 2026-09-12 실측에서 길이·낱말 지적에 따른
 * 전체 재서술이 정확한 조건 문장을 흐리는 사례를 확인했다. 길이·쉼표·어휘는 [checkStyle]
 * 진단으로 남기고, 뜻풀이 충돌과 이중 피동만 보정을 요청한다. 사실 누락 검사는 별도다.
 * 원문과 같은 첨부파일 이름은 편집하지 않는다. 파일 이름 누락도 사실 검사에서 검출한다.
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
    val sentences = splitSentences(draft)
    val issues =
        sentences.flatMap { sentence ->
            val editable = names.fold(sentence) { text, name -> text.replace(name, "서류") }
            checkStyle(editable)
                .issues
                .filter { it.kind == StyleRuleKind.GLOSS_COLLISION || it.kind == StyleRuleKind.DOUBLE_PASSIVE }
                .map { it.copy(sentence = sentence) }
        }
    return StyleCheckResult(sentences.size, issues)
}

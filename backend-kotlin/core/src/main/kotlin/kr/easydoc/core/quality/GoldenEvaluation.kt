package kr.easydoc.core.quality

import kr.easydoc.core.easyread.StyleCheckResult
import kr.easydoc.core.easyread.checkStyle

/** 한 문서의 스타일 규칙 평가. 외부 API 없이 [checkStyle] 만 쓴다. */
class StyleEvaluation(
    val documentId: String,
    val result: StyleCheckResult,
) {
    val passed: Boolean get() = result.passed

    override fun toString(): String =
        "StyleEvaluation(id=$documentId, passed=$passed, sentences=${result.totalSentences}, " +
            "issues=${result.issues.size})"
}

/** 필수 사실이 변환문에 남았는지. */
class FactEvaluation(
    val documentId: String,
    val missing: List<RequiredFact>,
) {
    val passed: Boolean get() = missing.isEmpty()

    override fun toString(): String = "FactEvaluation(id=$documentId, missing=${missing.size})"
}

/** 골든 문서를 스타일 규칙으로 채점한다. LLM 을 부르지 않는다. */
fun evaluateStyle(
    documentId: String,
    easyText: String,
): StyleEvaluation = StyleEvaluation(documentId, checkStyle(easyText))

/** [facts] 의 각 항목이 [text] 에 하나 이상의 표기로 남아 있는지 본다. */
fun evaluateFacts(
    documentId: String,
    text: String,
    facts: List<RequiredFact>,
): FactEvaluation = FactEvaluation(documentId, facts.filterNot { it.presentIn(text) })

/** 스키마가 요구하는 자리와 중복 id 를 검사한다. */
fun validateCorpus(corpus: GoldenCorpus): List<GoldenSchemaIssue> {
    val issues = mutableListOf<GoldenSchemaIssue>()
    val seen = linkedSetOf<String>()
    corpus.documents.forEach { document ->
        issues += validateDocument(document)
        if (!seen.add(document.id)) {
            issues += GoldenSchemaIssue(document.id, "id 가 중복이다")
        }
    }
    return issues
}

/** 기록된 기준선과 현재 코퍼스를 비교한다. 다르면 승인 없는 갱신이다. */
fun baselineMismatch(
    current: GoldenBaseline,
    recorded: GoldenBaseline,
): String? {
    if (current.documentCount == recorded.documentCount &&
        current.requiredFactCount == recorded.requiredFactCount
    ) {
        return null
    }
    return "골든 기준선이 바뀌었다 — 현재 documents=${current.documentCount} " +
        "facts=${current.requiredFactCount}, 기록 documents=${recorded.documentCount} " +
        "facts=${recorded.requiredFactCount}. 기준선 파일은 리뷰 승인 후에만 고친다."
}

internal fun RequiredFact.presentIn(text: String): Boolean = variants.any { it.isNotEmpty() && it in text }

private fun validateDocument(document: GoldenDocument): List<GoldenSchemaIssue> {
    val id = document.id.ifBlank { null }
    return buildList {
        if (document.id.isBlank()) add(GoldenSchemaIssue(id, "id 가 비었다"))
        if (document.title.isBlank()) add(GoldenSchemaIssue(id, "title 이 비었다"))
        if (document.category.isBlank()) add(GoldenSchemaIssue(id, "category 가 비었다"))
        if (document.sourceText.isBlank()) add(GoldenSchemaIssue(id, "source_text 가 비었다"))
        if (document.requiredFacts.isEmpty()) add(GoldenSchemaIssue(id, "required_facts 가 비었다"))
        document.requiredFacts.forEachIndexed { index, fact ->
            if (fact.canonical.isBlank()) {
                add(GoldenSchemaIssue(id, "required_facts[$index].canonical 이 비었다"))
            }
        }
    }
}

package kr.easydoc.core.quality

/**
 * `data/golden/documents/` 한 건의 평가 입력.
 *
 * JSON 파싱은 테스트 쪽이 맡고, 여기 있는 것은 **스키마가 요구하는 값**이다.
 * 원문 본문은 로그에 남기지 않는다.
 */
class GoldenDocument(
    val id: String,
    val title: String,
    val category: String,
    val synthetic: Boolean,
    val sourceText: String,
    val requiredFacts: List<RequiredFact>,
) {
    override fun toString(): String =
        "GoldenDocument(id=$id, category=$category, synthetic=$synthetic, " +
            "title=${title.length}자, source=${sourceText.length}자, facts=${requiredFacts.size})"
}

/** 변환문이 빠뜨리면 안 되는 사실 하나. [canonical] 또는 [accept] 중 하나라도 있으면 잔존으로 본다. */
class RequiredFact(
    val canonical: String,
    val accept: List<String> = emptyList(),
) {
    val variants: List<String> get() = listOf(canonical) + accept

    override fun toString(): String = "RequiredFact(canonical=${canonical.length}자, accept=${accept.size})"
}

/** 승인된 골든 문서 묶음. 중복 id 는 스키마 위반이다. */
class GoldenCorpus(val documents: List<GoldenDocument>) {
    val baseline: GoldenBaseline
        get() =
            GoldenBaseline(
                documentCount = documents.size,
                requiredFactCount = documents.sumOf { it.requiredFacts.size },
            )

    override fun toString(): String = "GoldenCorpus(documents=${documents.size}, facts=${baseline.requiredFactCount})"
}

/** 커밋된 기준선. 일반 테스트 실행이 이 파일을 다시 쓰지 않는다. */
class GoldenBaseline(
    val documentCount: Int,
    val requiredFactCount: Int,
) {
    override fun toString(): String =
        "GoldenBaseline(documentCount=$documentCount, requiredFactCount=$requiredFactCount)"
}

/** 스키마·중복 id 검사에서 발견한 결함. */
class GoldenSchemaIssue(
    val documentId: String?,
    val reason: String,
) {
    override fun toString(): String = "GoldenSchemaIssue(id=$documentId, reason=$reason)"
}

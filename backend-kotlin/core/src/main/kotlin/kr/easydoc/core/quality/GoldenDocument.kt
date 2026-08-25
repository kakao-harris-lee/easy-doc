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
class GoldenCorpus(
    val documents: List<GoldenDocument>,
    val files: List<String> = documents.map { it.id }.sorted(),
    val contentDigest: String = fieldDigest(documents),
) {
    val baseline: GoldenBaseline
        get() =
            GoldenBaseline(
                documentCount = documents.size,
                requiredFactCount = documents.sumOf { it.requiredFacts.size },
                files = files,
                ids = documents.map { it.id }.sorted(),
                contentDigest = contentDigest,
            )

    override fun toString(): String =
        "GoldenCorpus(documents=${documents.size}, files=${files.size}, facts=${baseline.requiredFactCount})"
}

/** 커밋된 기준선. 일반 테스트 실행이 이 파일을 다시 쓰지 않는다. */
class GoldenBaseline(
    val documentCount: Int,
    val requiredFactCount: Int,
    val files: List<String>,
    val ids: List<String>,
    val contentDigest: String,
    val quality: QualityCounts = QualityCounts.EMPTY,
) {
    fun withQuality(quality: QualityCounts): GoldenBaseline =
        GoldenBaseline(
            documentCount = documentCount,
            requiredFactCount = requiredFactCount,
            files = files,
            ids = ids,
            contentDigest = contentDigest,
            quality = quality,
        )

    override fun toString(): String =
        "GoldenBaseline(documentCount=$documentCount, requiredFactCount=$requiredFactCount, " +
            "files=${files.size}, ids=${ids.size}, contentDigest=${contentDigest.length}자, quality=$quality)"
}

/** 변환 결과 채점 집계. 본문은 들지 않는다. */
class QualityCounts(
    val stylePassCount: Int,
    val factPassCount: Int,
) {
    override fun toString(): String = "QualityCounts(stylePass=$stylePassCount, factPass=$factPassCount)"

    companion object {
        val EMPTY: QualityCounts = QualityCounts(stylePassCount = 0, factPassCount = 0)
    }
}

/** 필드 값만으로 만든 digest. 파일 JSON 정규화 digest 와는 경로가 다르다. */
internal fun fieldDigest(documents: List<GoldenDocument>): String {
    val canonical =
        documents
            .sortedWith(compareBy({ it.id }, { it.title }))
            .joinToString("\u001e") { document ->
                listOf(
                    document.id,
                    document.title,
                    document.category,
                    document.synthetic.toString(),
                    document.sourceText,
                    document.requiredFacts.joinToString("\u001f") { fact ->
                        fact.canonical + "\u001d" + fact.accept.joinToString("\u001d")
                    },
                ).joinToString("\u001f")
            }
    return sha256Hex(canonical)
}

internal fun sha256Hex(text: String): String {
    val bytes =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
    return "sha256:" +
        java.util.HexFormat
            .of()
            .formatHex(bytes)
}

/** 스키마·중복 id 검사에서 발견한 결함. */
class GoldenSchemaIssue(
    val documentId: String?,
    val reason: String,
) {
    override fun toString(): String = "GoldenSchemaIssue(id=$documentId, reason=$reason)"
}

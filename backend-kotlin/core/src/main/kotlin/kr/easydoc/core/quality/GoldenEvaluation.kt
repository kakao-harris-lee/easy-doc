package kr.easydoc.core.quality

import kr.easydoc.core.easyread.StyleCheckResult
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.normalizeFullWidthDigits

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

/** 한 문서의 변환 결과 채점. 본문은 들지 않는다. */
class DocumentQualityReport(
    val documentId: String,
    val style: StyleEvaluation,
    val facts: FactEvaluation,
    val judge: JudgeScore? = null,
) {
    override fun toString(): String = "DocumentQualityReport(id=$documentId, style=$style, facts=$facts, judge=$judge)"
}

/** 골든 코퍼스 변환 결과 채점. */
class CorpusEvaluation(
    val reports: List<DocumentQualityReport>,
    val missingConversionIds: List<String>,
) {
    val quality: QualityCounts
        get() =
            QualityCounts(
                stylePassCount = reports.count { it.style.passed },
                factPassCount = reports.count { it.facts.passed },
            )

    override fun toString(): String =
        "CorpusEvaluation(reports=${reports.size}, missing=${missingConversionIds.size}, quality=$quality)"
}

/**
 * 골든 문서마다 변환 결과에 스타일 규칙과 필수 사실을 적용한다.
 * [conversions] 키는 문서 id 다. 없는 id 는 [CorpusEvaluation.missingConversionIds] 로 남긴다.
 */
fun evaluateConvertedCorpus(
    corpus: GoldenCorpus,
    conversions: Map<String, String>,
    judge: GoldenJudge? = null,
): CorpusEvaluation {
    val missing = corpus.documents.map { it.id }.filterNot { it in conversions }
    val reports =
        corpus.documents.mapNotNull { document ->
            val converted = conversions[document.id] ?: return@mapNotNull null
            DocumentQualityReport(
                documentId = document.id,
                style = evaluateStyle(document.id, converted),
                facts = evaluateFacts(document.id, converted, document.requiredFacts),
                judge = judge?.score(document, converted),
            )
        }
    return CorpusEvaluation(reports = reports, missingConversionIds = missing)
}

/** 기록된 기준선과 현재 코퍼스를 비교한다. 다르면 승인 없는 갱신이다. */
fun baselineMismatch(
    current: GoldenBaseline,
    recorded: GoldenBaseline,
): String? {
    val parts = mutableListOf<String>()
    if (current.documentCount != recorded.documentCount ||
        current.requiredFactCount != recorded.requiredFactCount
    ) {
        parts +=
            "documents=${current.documentCount}/${recorded.documentCount} " +
            "facts=${current.requiredFactCount}/${recorded.requiredFactCount}"
    }
    if (current.files != recorded.files) {
        parts += "files"
    }
    if (current.ids != recorded.ids) {
        parts += "ids"
    }
    if (current.contentDigest != recorded.contentDigest) {
        parts += "contentDigest=${current.contentDigest}"
    }
    if (current.quality.stylePassCount != recorded.quality.stylePassCount ||
        current.quality.factPassCount != recorded.quality.factPassCount
    ) {
        parts += "quality=${current.quality}/${recorded.quality}"
    }
    if (parts.isEmpty()) {
        return null
    }
    return "골든 기준선이 바뀌었다 — ${parts.joinToString("; ")}. 기준선 파일은 리뷰 승인 후에만 고친다."
}

internal fun RequiredFact.presentIn(text: String): Boolean {
    val normalizedText = normalizeForFactMatch(text)
    return variants.any { it.isNotEmpty() && normalizeForFactMatch(it) in normalizedText }
}

/**
 * 사실 존재 여부 비교 전에 변환문과 variant 양쪽에 거는 **표기 별칭** 정규화.
 *
 * 8차 유료 회차에서 `required_facts` 누락 오탐 4건이 전부 이 세 겹으로 걷혔다 —
 * 사실은 옮겨졌는데 표기만 쉬운 말로 바뀌어 정확 문자열 매칭에 걸린 경우다
 * (`1.6%` → "1.6퍼센트", `２５%` 같은 전각 숫자, `40일이내` → "40일 이내"의 공백).
 * 적용 순서는 선언 순서(전각→반각 → %→퍼센트 → 공백 제거)와 같다.
 *
 * 딱 이 셋만 두고 늘리지 않는다 — 그 이상은 오탐 방지가 아니라 **문장 재구성**을
 * 흡수하려는 시도가 된다. 같은 회차에서 `049`(9세 이상 → 9세부터 24세까지)와
 * `092`(월 최대 60만원 → 한 달에 최대 60만원까지)는 표기 변주가 아니라 재구성이었고,
 * 이걸 규칙으로 흡수하려면 "이상"·"이내" 같은 상한·하한 제약어를 정규화가 무시해야
 * 한다. 그런데 같은 회차의 `063`은 원문 "1회 연장가능(40일이내)"의 "이내"를 변환문이
 * 실제로 빠뜨린 **진짜 손실**이었다 — 제약어를 무시하는 정규화는 그 손실을 놓친다.
 * `049`·`092`를 흡수할지는 골든 코퍼스 `accept` 목록을 사람이 늘릴 **데이터 결정**이고
 * 이 함수의 범위 밖이다.
 *
 * **남은 위험(기록, 미차단)**: 공백 제거가 **개행까지** 지운다. 이론상 한 줄이 "…2022년"으로
 * 끝나고 다음 줄이 "1월…"로 시작하면 "2022년1월"이 있다고 읽어, 줄 경계를 넘어 우연히
 * 이어 붙은 문자열이 실제로는 없는 사실을 있다고 통과시킬 수 있다. 게이트 판정에서는
 * 이 방향(진짜 손실을 통과시키는 미탐)이 오탐(멀쩡한 변환을 실패로 잡는 것)보다 나쁘다.
 * **`FactPreservation.kt` 의 비대칭 원칙과 반대라는 점이 중요하다** — 그 파일의
 * `findMissingFacts` 는 제품 보정 경로라 오탐이 나쁘고(유료 호출을 태우고 잘못된 복원을
 * 민다) 미탐은 현상 유지지만, 이 함수는 게이트 ⓪ 판정 입력이라 뒤집힌다: 통과시키면
 * 게이트가 있을 이유가 없어지고, 실패로 잡히는 쪽은 사람이 값을 열어 보면 걸러진다.
 * 이 함수가 세 겹에서 멈추고 더 늘리지 않는 이유가 그 뒤집힘이다. 57건
 * 실측에서 관측 0건이고, 실측으로 걷힌 사례는 전부 가로 공백이라 개행 제거가 필요하지도
 * 않았다 — 근거는 `docs/plans/2026-09-09-content-loss.md` §10.4. 다음 회차에서 "이상하게
 * 통과한 사실"이 보이면 여기부터 본다.
 */
private fun normalizeForFactMatch(text: String): String =
    normalizeFullWidthDigits(text)
        .replace("%", "퍼센트")
        .filterNot { it.isWhitespace() }

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

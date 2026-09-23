package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.quality.GoldenDocument

/**
 * 이번 유료 측정이 **어떤 문서를** 돌 것인가.
 *
 * `GoldenCorpusLlmEvaluationTest` 는 승인 코퍼스 전건을 돈다 — 그것이 기본이고, 이 노브를 쓰지
 * 않으면 지금까지와 완전히 같다. 다만 표본 몇 건만 승인받은 실행(예: `docs/plans/` 의 개발용
 * 6건)에서도 전건 값을 치르게 되는 자리가 있었다. 승인 범위보다 많이 사는 것은 [LaneRuns] 가
 * 막으려는 것과 같은 종류의 사고라, 같은 방식으로 **유료 호출을 시작하기 전에** 정한다.
 *
 * 반복 횟수는 이 노브가 정하지 않는다 — 같은 문서를 여러 번 도는 것은 [LaneRuns] 의 몫이라
 * 여기서는 같은 id 를 두 번 적는 것을 오타로 보고 거절한다.
 *
 * R2 레인의 `EASYDOC_R2_LANE_DOCUMENTS`(`ActionGuideR2Lane.DOCUMENTS_ENV`) 와 의미는 같지만
 * 거기는 코호트 상수 집합 안에서만 고르고 여기는 로드된 코퍼스 안에서 고른다 — 두 레인이 보는
 * 문서 목록의 출처가 다르다.
 */
internal object LaneDocuments {
    /**
     * 읽는 환경변수 이름. `EASYDOC_LLM_*` 이 아니라 `EASYDOC_LANE_*` 을 쓰는 이유는
     * [LaneRuns.RUNS_ENV] 와 같다 — provider 설정이 아니라 레인 실행 자체의 노브다.
     */
    const val ENV: String = "EASYDOC_LANE_DOCUMENTS"

    /** 골든 문서 id 는 파일명 `NNN-제목.json` 의 세 자리 접두사다. */
    private val ID_PATTERN = Regex("""\d{3}""")

    /**
     * [ENV] 미설정·공백은 코퍼스 전건. 그 밖에는 쉼표로 나눈 문서 id 목록이며 항목마다 앞뒤
     * 공백을 다듬는다. 세 자리 숫자가 아니거나, 같은 id 가 두 번 이상 있거나, 코퍼스에 없는
     * id 가 하나라도 있으면 [ConfigurationException] — 조용히 무시하면 운영자가 고른 줄 알았던
     * 문서가 빠진 채로 유료 호출을 다 쓰게 된다([LaneRuns.of] 와 같은 판단).
     *
     * 고른 문서는 환경변수에 적은 순서가 아니라 **코퍼스 순서**로 돌려준다 — 레인은 문서를
     * 순차로 돌고 그 순서가 리포트·변환문 파일에 남으므로, 적은 순서에 따라 같은 표본의 실행이
     * 서로 달라 보이지 않게 한다.
     */
    fun select(
        env: (String) -> String?,
        corpus: List<GoldenDocument>,
    ): LaneDocumentSelection {
        val raw =
            env(ENV)?.takeIf(String::isNotBlank)
                ?: return LaneDocumentSelection(corpus, "documents=all(${corpus.size})")
        val requested = raw.split(',').map(String::trim)
        rejectionOf(requested, corpus)?.let { throw ConfigurationException(it) }
        val selected = corpus.filter { it.id in requested }
        val ids = selected.joinToString(",", transform = GoldenDocument::id)
        return LaneDocumentSelection(selected, "documents=${selected.size}/${corpus.size}[$ids]")
    }

    /**
     * 거절 사유 한 줄, 문제가 없으면 `null`. 세 검사를 한 자리에 모아 두어 유료 호출 전에
     * 한 번만 판정한다 — 먼저 걸린 것부터 알린다(형식 → 중복 → 미존재).
     */
    private fun rejectionOf(
        requested: List<String>,
        corpus: List<GoldenDocument>,
    ): String? {
        val known = corpus.mapTo(mutableSetOf(), GoldenDocument::id)
        val checks =
            listOf(
                requested.filterNot(ID_PATTERN::matches) to "문서 id 는 세 자리 숫자여야 한다",
                requested.duplicates() to "같은 문서 id 가 두 번 이상 있다 — 반복은 ${LaneRuns.RUNS_ENV} 가 정한다",
                requested.filterNot(known::contains) to "골든 코퍼스에 없는 문서 id 다",
            )
        val failed = checks.firstOrNull { (offenders, _) -> offenders.isNotEmpty() } ?: return null
        val (offenders, reason) = failed
        return "$ENV: $reason — ${offenders.joinToString(", ") { "'$it'" }}"
    }

    private fun List<String>.duplicates(): List<String> {
        val counts = groupingBy { it }.eachCount()
        return counts.filterValues { it > 1 }.keys.toList()
    }
}

/**
 * [LaneDocuments.select] 가 고른 문서와, 그 선택을 측정 조건 줄에 남길 한 줄.
 *
 * [description] 에는 id 만 실린다 — 제목도 본문도 싣지 않는다(CLAUDE.md 관측 규칙).
 */
internal class LaneDocumentSelection(
    val documents: List<GoldenDocument>,
    val description: String,
)

package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader

/**
 * 이번 유료 측정이 **어떤 문서를** 돌 것인가.
 *
 * `GoldenCorpusLlmEvaluationTest` 는 승인 코퍼스 전건을 돈다 — 그것이 기본이다. 이 노브를 쓰지
 * 않으면 문서 선택은 이전과 똑같이 코퍼스 전건이다 — 다만 측정 조건 한 줄([LaneDocumentSelection.description])
 * 과 `conditions.txt` 에는 그때도 `documents=all(N)` 이 새로 붙는다([select] KDoc).
 *
 * `EASYDOC_LANE_MAX_USD` 는 필수이고 [LaneSpendLimit] 이 그 상한에서 하드 스톱한다 — 그래서 이
 * 노브가 없던 시절의 실제 위험은 "얼마든지 쓴다" 가 아니라, 표본 몇 건만 승인받은 실행(예:
 * `docs/reports/2026-09-18-easy-read-r0-baseline.md` §2 의 개발용 6건)에서도 **승인 범위 밖
 * 문서에 예산을 쓰고 코퍼스 중간에 멈추는 것**이었다. 2026-09-23 실행에서 상한 7.5달러가 문서
 * 023 호출 6건 만에 소진되어 그 지점에서 멈췄다 — 승인받은 표본 대신 코퍼스 순서상 먼저 오는
 * 문서에 예산을 다 쓴 사례다. 승인 범위보다 많이 사는 것은 [LaneRuns] 가 막으려는 것과 같은
 * 종류의 사고라, 같은 방식으로 **유료 호출을 시작하기 전에** 정한다.
 *
 * 반복 횟수는 이 노브가 정하지 않는다 — 같은 문서를 여러 번 도는 것은 [LaneRuns] 의 몫이라
 * 여기서는 같은 id 를 두 번 적는 것을 오타로 보고 거절한다.
 *
 * R2 레인의 `EASYDOC_R2_LANE_DOCUMENTS`(`ActionGuideR2Lane.DOCUMENTS_ENV`) 와 의미는 같지만
 * 두 가지가 다르다. 첫째, 거기는 코호트 상수 집합 안에서만 고르고 여기는 로드된 코퍼스 안에서
 * 고른다 — 두 레인이 보는 문서 목록의 출처가 다르다. 둘째, 거기(`ActionGuideR2Lane.parseIds`)는
 * 고른 문서를 **환경변수에 적은 순서**로 돌려주고 여기는 **코퍼스 순서**로 돌려준다([select]
 * KDoc) — 그래서 같은 승인 표본 문자열(예: `"087,023"`)을 두 레인에 똑같이 넣어도 두 레인의
 * 리포트·변환문 파일에는 서로 다른 순서로 남는다. 어느 한쪽이 옳고 다른 쪽이 틀린 것이
 * 아니라, 레인마다 정한 규칙이 다르다는 뜻이다 — 리포트를 비교할 때 순서 차이를 버그로 읽지
 * 않는다.
 */
internal object LaneDocuments {
    /**
     * 읽는 환경변수 이름. `EASYDOC_LLM_*` 이 아니라 `EASYDOC_LANE_*` 을 쓰는 이유는
     * [LaneRuns.RUNS_ENV] 와 같다 — provider 설정이 아니라 레인 실행 자체의 노브다.
     */
    const val ENV: String = "EASYDOC_LANE_DOCUMENTS"

    /**
     * 문서 id 의 안전한 문법 — [GoldenDocumentLoader.SAFE_DOCUMENT_ID] 를 그대로 쓴다. 골든
     * 문서 id 는 코퍼스 JSON 의 `id` 필드에서 온다([GoldenDocumentLoader.loadFile]) — 현재
     * 코퍼스가 전부 파일명 `NNN-제목.json` 의 세 자리 접두사를 id 로 쓰는 것은 관례이지
     * 계약이 아니다. 문법을 여기 따로 적으면 로더가 문법을 넓히거나 좁힐 때 이 검사만 조용히
     * 어긋난다([GoldenDocumentLoader.SAFE_DOCUMENT_ID] KDoc이 소비자에게 요구하는 것과 같다).
     */
    private val SAFE_DOCUMENT_ID: Regex = GoldenDocumentLoader.SAFE_DOCUMENT_ID

    /**
     * [ENV] 미설정은 코퍼스 전건. **설정했는데 공백이면 [ConfigurationException]** — [LaneRuns]
     * 와 달리 공백을 기본값으로 접지 않는다. [LaneRuns] 의 기본값(1회 반복)은 이 노브를 쓰지
     * 않는 실행과 같아 가장 싼 값이지만, 이 노브의 "전건" 은 가장 비싼 값이다 — 표본 몇 건만
     * 승인받은 실행에서 값을 지우려다 공백만 남기면 조용히 코퍼스 전체를 사게 된다. 그 밖에는
     * 쉼표로 나눈 문서 id 목록이며 항목마다 앞뒤 공백을 다듬는다. [SAFE_DOCUMENT_ID] 문법을
     * 어기거나, 같은 id 가 두 번 이상 있거나, 코퍼스에 없는 id 가 하나라도 있으면
     * [ConfigurationException] — 조용히 무시하면 운영자가 고른 줄 알았던 문서가 빠진 채로
     * 유료 호출을 다 쓰게 된다([LaneRuns.of] 와 같은 판단).
     *
     * 고른 문서는 환경변수에 적은 순서가 아니라 **코퍼스 순서**로 돌려준다 — 레인은 문서를
     * 순차로 돌고 그 순서가 리포트·변환문 파일에 남으므로, 적은 순서에 따라 같은 표본의 실행이
     * 서로 달라 보이지 않게 한다. 고른 id 가 코퍼스 전체와 같으면(순서만 다를 뿐 빠짐도
     * 겹침도 없으면) [LaneDocumentSelection.description] 은 미설정 때와 같은
     * `documents=all(N)` 로 접힌다 — 부분 선택이 아닌데도 `documents=N/N[...]` 처럼 달리
     * 보이지 않게 한다.
     */
    fun select(
        env: (String) -> String?,
        corpus: List<GoldenDocument>,
    ): LaneDocumentSelection {
        val raw = env(ENV)
        return when {
            raw == null -> {
                allSelection(corpus)
            }

            raw.isBlank() -> {
                throw ConfigurationException(
                    "$ENV: 비어 있으면 전건이 아니라 오류다 — 의도치 않게 전건을 사지 않게. 표본 전체를 " +
                        "돌리려면 이 환경변수를 미설정으로 두고, 일부만 돌리려면 문서 id 를 쉼표로 적어라.",
                )
            }

            else -> {
                selectionFor(raw, corpus)
            }
        }
    }

    private fun selectionFor(
        raw: String,
        corpus: List<GoldenDocument>,
    ): LaneDocumentSelection {
        val requested = raw.split(',').map(String::trim)
        rejectionOf(requested, corpus)?.let { throw ConfigurationException(it) }
        val selected = corpus.filter { it.id in requested }
        return if (selected.size == corpus.size) {
            allSelection(corpus)
        } else {
            val ids = selected.joinToString(",", transform = GoldenDocument::id)
            LaneDocumentSelection(selected, "documents=${selected.size}/${corpus.size}[$ids]")
        }
    }

    private fun allSelection(corpus: List<GoldenDocument>): LaneDocumentSelection =
        LaneDocumentSelection(corpus, "documents=all(${corpus.size})")

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
                requested.filterNot(SAFE_DOCUMENT_ID::matches) to
                    "문서 id 문법에 맞지 않는다(GoldenDocumentLoader.SAFE_DOCUMENT_ID)",
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

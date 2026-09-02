package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException

/**
 * 문서 하나를 몇 번 반복해서 잴 것인가.
 *
 * 다음 유료 측정(`GoldenCorpusLlmEvaluationTest`)은 승인된 문서 각각을 여러 번 돌려 팽창비·
 * 스타일 위반 밀도가 같은 문서 안에서도 얼마나 흔들리는지를 본다 — 그 반복 횟수를 정하는
 * 노브다. 기본은 1회(반복 없음)이고, 그때는 기존 단일 실행과 완전히 같게 동작한다
 * ([LaneTranscript] 가 접미사 없는 파일명을 그대로 쓰고, [LaneReport] 의 「문서별 반복 집계」
 * 섹션이 아예 나오지 않는다).
 */
internal object LaneRuns {
    /**
     * 읽는 환경변수 이름. `EASYDOC_LLM_*` 이 아니라 `EASYDOC_LANE_*` 을 쓴다 — provider 설정이
     * 아니라 이 레인이 문서를 몇 번 도는지를 정하는, 레인 실행 자체의 노브이기 때문이다
     * ([GoldenLlmLane] 최상단 KDoc 「이 파일이 하는 일은 환경변수를 LlmProperties 로 옮기는
     * 것뿐이다」와 책임을 가른다).
     */
    const val RUNS_ENV: String = "EASYDOC_LANE_RUNS"

    private const val DEFAULT_RUNS: Int = 1

    /**
     * [RUNS_ENV] 미설정·빈 값은 [DEFAULT_RUNS]. 정수가 아니거나 1 미만이면 [ConfigurationException]
     * — 조용히 기본값으로 접으면 운영자가 잘못 넣은 값(오타, 0, 음수)을 모르고 지나간다
     * (`GoldenLlmLane.maxOutputTokensOf` 와 같은 판단). 0회·음수는 "N 번 돈다"는 말이 되지
     * 않으므로 유료 호출을 시작하기 전에 막는다.
     */
    fun of(env: (String) -> String?): Int {
        val raw = env(RUNS_ENV)?.takeIf(String::isNotBlank) ?: return DEFAULT_RUNS
        val parsed =
            raw.toIntOrNull()
                ?: throw ConfigurationException("$RUNS_ENV='$raw' 은 정수가 아니다")
        if (parsed < 1) {
            throw ConfigurationException("$RUNS_ENV=$parsed 는 1 이상이어야 한다")
        }
        return parsed
    }
}

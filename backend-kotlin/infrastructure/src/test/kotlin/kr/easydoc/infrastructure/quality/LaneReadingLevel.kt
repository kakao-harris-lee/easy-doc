package kr.easydoc.infrastructure.quality

import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.exceptions.ConfigurationException

/**
 * 이번 유료 측정이 **어느 독해 수준으로** 변환하고 채점할 것인가.
 *
 * 레인은 지금까지 제품 기본값 [ReadingLevel.GRADE_5_6] 으로만 돌았다. 「더 쉽게」(`grade_3_4`)
 * 는 크레딧을 1.2배 받으므로 그 수준의 provider 원가가 1.2배 안에 드는지를 같은 레인으로 재야
 * 한다(`docs/plans/2026-10-22-grade-3-4-cost-measurement.md` 2단계) — 그 수준을 고르는 노브다.
 * 미설정이면 기본 수준이라 이 노브를 쓰지 않는 실행은 이전과 완전히 같다.
 *
 * 값은 계약(`contracts/easy-doc-v1.yaml`)의 wire 이름을 그대로 쓴다 — 레인 리포트의 조건 줄
 * ([LaneConditions])에 찍히는 값이 운영자가 환경변수에 적은 값과 같아야 대조할 수 있다.
 */
internal object LaneReadingLevel {
    /** 읽는 환경변수 이름. provider 설정이 아니라 레인 실행의 노브라 `EASYDOC_LANE_*` 이다([LaneRuns.RUNS_ENV]). */
    const val ENV: String = "EASYDOC_LANE_READING_LEVEL"

    /**
     * 미설정·빈 값은 [ReadingLevel.GRADE_5_6]. 그 밖의 값은 [ConfigurationException] —
     * 조용히 기본값으로 접으면 `grade_3_4` 를 재려던 유료 실행이 `grade_5_6` 을 재고도
     * 쟀다고 적는다([GoldenLaneVariant.from] 과 같은 판단).
     */
    fun from(env: (String) -> String?): ReadingLevel {
        val raw = env(ENV)?.trim()?.takeIf(String::isNotEmpty) ?: return ReadingLevel.GRADE_5_6
        return ReadingLevel.entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) }
            ?: throw ConfigurationException(
                "$ENV='$raw' 은 지원하지 않는 수준이다 — 허용값: ${ReadingLevel.entries.joinToString { it.wireName }}",
            )
    }
}

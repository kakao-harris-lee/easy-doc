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
 * **계약의 세 수준 중 둘만 받는다([MEASURED]).** `middle_school` 은 judge 프롬프트
 * (`LlmPrompt.forJudge`)에 중학교 기준 문구가 없어, 변환만 중학교 수준으로 가고 채점은
 * 초등학교 5~6학년 기준으로 남는다 — 그렇게 나온 값은 수준별 원가도 품질도 아니므로 유료
 * 호출 전에 거절한다.
 *
 * 값은 계약(`contracts/easy-doc-v1.yaml`)의 wire 이름을 **대소문자까지 그대로** 쓴다 —
 * 계약 경로 [ReadingLevel.ofWireName] 와 같은 해석이라야, 운영자가 환경변수에 적은 값과
 * 레인 리포트의 조건 줄([LaneConditions])에 찍히는 값을 대조할 수 있다.
 */
internal object LaneReadingLevel {
    /** 읽는 환경변수 이름. provider 설정이 아니라 레인 실행의 노브라 `EASYDOC_LANE_*` 이다([LaneRuns.RUNS_ENV]). */
    const val ENV: String = "EASYDOC_LANE_READING_LEVEL"

    /** 이 레인이 재는 수준 — judge 프롬프트가 기준 학년을 구분하는 둘이다. */
    private val MEASURED: List<ReadingLevel> = listOf(ReadingLevel.GRADE_5_6, ReadingLevel.GRADE_3_4)

    /**
     * 미설정·빈 값은 [ReadingLevel.GRADE_5_6]. 그 밖의 값은 [ConfigurationException] —
     * 조용히 기본값으로 접으면 `grade_3_4` 를 재려던 유료 실행이 `grade_5_6` 을 재고도
     * 쟀다고 적는다([GoldenLaneVariant.from] 과 같은 판단).
     */
    fun from(env: (String) -> String?): ReadingLevel {
        val raw = env(ENV)?.trim()?.takeIf(String::isNotEmpty) ?: return ReadingLevel.GRADE_5_6
        return MEASURED.firstOrNull { it.wireName == raw } ?: throw ConfigurationException(rejection(raw))
    }

    /** 중학교와 그 밖의 값을 가려 적는다 — 전자는 계약에는 있지만 이 레인이 재지 못하는 수준이다. */
    private fun rejection(raw: String): String {
        val allowed = MEASURED.joinToString { it.wireName }
        return if (raw == ReadingLevel.MIDDLE_SCHOOL.wireName) {
            "$ENV='$raw' 은 이 레인이 재지 못한다 — judge 프롬프트에 중학교 기준이 없어 " +
                "변환만 중학교 수준으로 가고 채점은 초등학교 5~6학년 기준으로 남는다. 허용값: $allowed"
        } else {
            "$ENV='$raw' 은 지원하지 않는 수준이다 — 허용값: $allowed"
        }
    }
}

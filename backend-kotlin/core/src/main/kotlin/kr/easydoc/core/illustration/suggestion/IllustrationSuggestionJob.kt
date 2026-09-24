package kr.easydoc.core.illustration.suggestion

import kr.easydoc.core.exceptions.InvalidInputException

/**
 * R7 ER-17 그림 제안 분석 작업의 외부 상태 — `illustration_suggestion_jobs.status`(V35) 와 같은
 * 어휘다. 행동 안내 작업(`ActionGuideJobStatus`)과 값이 같지만 타입을 나눈다: 두 기능은 각자의
 * 표·수명주기를 가지며, 한 타입을 공유하면 한쪽의 상태가 늘 때 다른 쪽이 조용히 따라 넓어진다
 * (명세 §2 「공통화는 이번 범위의 필수 조건이 아니다」).
 */
enum class IllustrationSuggestionJobStatus(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SUPERSEDED("superseded"),
    ;

    val active: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        fun ofWireName(value: String): IllustrationSuggestionJobStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 그림 제안 작업 상태입니다: $value")
    }
}

/**
 * provider 나 내부 구현을 노출하지 않는 실패 코드(명세 §2 「실패 코드는 R2와 같은 세 가지」).
 *
 * - [GENERATION_FAILED]: provider 호출 자체가 실패했거나 입력을 만들지 못했다.
 * - [RESULT_INVALID]: 응답은 받았지만 구조가 어긋났거나 모든 제안이 원문 대조에서 탈락했다.
 * - [OUTCOME_UNKNOWN]: 호출을 시작한 뒤 결과를 확인하지 못했다 — 다시 부르지 않는다.
 */
enum class IllustrationSuggestionJobFailureCode(val wireName: String) {
    GENERATION_FAILED("generation_failed"),
    RESULT_INVALID("result_invalid"),
    OUTCOME_UNKNOWN("outcome_unknown"),
    ;

    companion object {
        fun ofWireName(value: String): IllustrationSuggestionJobFailureCode =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 그림 제안 작업 실패 코드입니다: $value")
    }
}

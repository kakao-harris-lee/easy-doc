package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException

/** 별도 행동 안내문 생성 작업의 외부 상태. */
enum class ActionGuideJobStatus(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SUPERSEDED("superseded"),
    ;

    val active: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        fun ofWireName(value: String): ActionGuideJobStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 행동 안내문 작업 상태입니다: $value")
    }
}

/** provider나 내부 구현을 노출하지 않는 작업 실패 코드. */
enum class ActionGuideJobFailureCode(val wireName: String) {
    GENERATION_FAILED("generation_failed"),
    RESULT_INVALID("result_invalid"),
    OUTCOME_UNKNOWN("outcome_unknown"),
    ;

    companion object {
        fun ofWireName(value: String): ActionGuideJobFailureCode =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 행동 안내문 작업 실패 코드입니다: $value")
    }
}

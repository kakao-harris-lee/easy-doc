package kr.easydoc.application.conversion

/** 변환 1건이 쓸 수 있는 **완성 요청** 수의 상한. */
const val MAX_LLM_CALLS_PER_CONVERSION: Int = 2

/** 완성 요청 예산. 초과 시도를 **터뜨린다**. */
internal class CompletionBudget(private val limit: Int = MAX_LLM_CALLS_PER_CONVERSION) {
    /** 지금까지 **시작한** 완성 요청 수. 실패로 끝난 호출도 포함한다. */
    var spent: Int = 0
        private set

    /** 예산을 하나 쓰고 호출한다. */
    fun <T> spend(call: () -> T): T {
        check(spent < limit) {
            "변환 1건의 완성 요청 상한 $limit 을 넘겼다 (${spent + 1}번째 시도). " +
                "보정은 조건부 1회이고 루프가 아니다 — 호출 자리가 늘었는지 확인하라. " +
                "3회 이상은 계획 §5 Phase 7 즉시 중단 기준이다."
        }
        spent++
        return call()
    }
}

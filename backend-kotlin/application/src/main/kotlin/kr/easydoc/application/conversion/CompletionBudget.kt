package kr.easydoc.application.conversion

/**
 * 변환 1건이 쓸 수 있는 **완성 요청** 수의 상한.
 *
 * 원본: `app/services/conversion.py::MAX_LLM_CALLS_PER_CONVERSION`.
 *
 * 값이 2인 이유는 구성이 정확히 둘이기 때문이다 — ① 변환 1건, ② 기계 검출된 규칙 위반이
 * 있을 때만 표적 보정 1건. 이 수가 흔들리면 크레딧 원가 산정(master-plan 5장)이 함께
 * 흔들리고, 3회 이상은 계획 §5 Phase 7 의 **즉시 중단 기준**이다.
 */
const val MAX_LLM_CALLS_PER_CONVERSION: Int = 2

/**
 * 완성 요청 예산. 초과 시도를 **터뜨린다**.
 *
 * ## 이것이 상한을 강제하는 방식은 "세는 것"이 아니다
 *
 * 요구는 "보통 2회"가 아니라 **구조적으로 2회**다(인벤토리 §3.1 (가) 2). 검사→호출을
 * 반복하는 구조에 사후 카운터를 붙이면 그것은 상한이 아니라 기대값일 뿐이다 — 조건이
 * 바뀌면 조용히 늘어난다. 그래서 [ConvertDocumentUseCase] 는 **루프를 쓰지 않고**
 * 직선 코드로 두 자리만 두고, 이 예산은 그 구조가 깨졌을 때 **즉시 드러내는 2차 방어선**이다.
 *
 * ## 도메인 예외가 아니라 [IllegalStateException] 인 이유
 *
 * 예산 초과는 사용자 입력 문제가 아니라 **코드 구조가 바뀐 것**이다. 도메인 예외로 감싸면
 * HTTP 응답으로 번역돼 "요청을 처리하지 못했습니다"가 되고, 그 순간 즉시 중단 기준에
 * 해당하는 사건이 평범한 5xx 로 묻힌다.
 */
internal class CompletionBudget(private val limit: Int = MAX_LLM_CALLS_PER_CONVERSION) {
    /** 지금까지 **시작한** 완성 요청 수. 실패로 끝난 호출도 포함한다. */
    var spent: Int = 0
        private set

    /**
     * 예산을 하나 쓰고 호출한다.
     *
     * 예산을 **호출 전에** 깎는다 — 응답 없이 끝난 호출도 세야 하기 때문이다. 비용·지연은
     * 이미 발생했고, 요점은 "다시 부르지 않는다"이다(인벤토리 §3.1 (가) 5).
     */
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

package kr.easydoc.application.subscription

/**
 * 주문·세션 행에 저장되고 설정의 `easydoc.payment.provider` 로도 고르는 결제 환경 식별자.
 *
 * 코드와 함께 바뀌어야 하는 불변식이라 구성값이 아니라 상수로 둔다. 허용 집합과 실결제 값이
 * 따로 적히면 한쪽만 바뀌어도 아무도 모르므로 한곳에서만 정의한다.
 */
object PaymentEnvironments {
    const val STUB = "stub"
    const val TOSS_TEST = "toss_test"
    const val TOSS_LIVE = "toss_live"

    val ALLOWED: Set<String> = setOf(STUB, TOSS_TEST, TOSS_LIVE)
}

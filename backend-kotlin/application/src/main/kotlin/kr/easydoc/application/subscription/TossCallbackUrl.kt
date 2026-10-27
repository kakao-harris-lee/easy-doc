package kr.easydoc.application.subscription

/**
 * 서버 카드 등록 콜백 URL의 한 정의.
 *
 * 경로는 계약(`contracts/easy-doc-v1.yaml` 의 `callback_url`)이 고정하므로 코드와 함께
 * 바뀌는 불변식으로 두고 테스트로 묶는다. 기동 검증(`SubscriptionConfiguration`)과 실제
 * 응답(`TossController`)이 각자 문자열을 이어 붙이면, 검증이 통과한 출처와 사용자에게
 * 내보내는 콜백이 달라질 수 있다 — 두 곳이 같은 식을 쓰게 한다.
 */
object TossCallbackUrl {
    /** 공개 출처를 기준으로 한 콜백 경로. */
    const val PATH = "/api/payments/toss/billing/callback"

    /** 공개 기준 URL의 끝 슬래시를 겹치지 않게 이어 붙인 절대 콜백 URL. */
    fun from(publicBaseUrl: String): String = publicBaseUrl.trimEnd('/') + PATH
}

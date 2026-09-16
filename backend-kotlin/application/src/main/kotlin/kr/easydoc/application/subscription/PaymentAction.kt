package kr.easydoc.application.subscription

import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.PhoneNotVerifiedException
import kr.easydoc.core.user.User

/** 결제 진입점이 요구하는 행동 — 문구의 뒷부분만 다르다. */
enum class PaymentAction(val phrase: String) {
    CHECKOUT("결제하세요"),
    CARD_REGISTRATION("카드를 등록하세요"),
}

/**
 * 이메일 → 휴대폰 순서로 인증을 요구한다. 새 결제 진입점은 반드시 이 함수를 거친다.
 * [user] 가 `null` 이면 이메일 미인증으로 취급한다([SubscriptionService.checkout]·
 * [TossBillingService.begin] 이 `users.findById` 로 읽은 값을 그대로 넘기던 것과 같은 의미다).
 */
fun requirePaymentEligible(
    user: User?,
    action: PaymentAction,
) {
    if (user?.emailVerifiedAt == null) {
        throw EmailNotVerifiedException("이메일 인증 후 ${action.phrase}")
    }
    if (user.phoneVerifiedAt == null) {
        throw PhoneNotVerifiedException("휴대폰 인증 후 ${action.phrase}")
    }
}

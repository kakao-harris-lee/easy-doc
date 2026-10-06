package kr.easydoc.application.subscription

import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.security.Secret
import java.time.Instant
import java.util.UUID

internal fun validateRegistrationRequest(
    purpose: String,
    consent: String?,
    environment: String,
    purchaseAllowed: Boolean,
) {
    if (purpose == "purchase" && !purchaseAllowed) throw ConflictException("신규 카드 등록이 중단되어 있습니다")
    if (purpose !in listOf("purchase", "replace_card")) throw InvalidInputException("잘못된 카드 등록 목적입니다")
    requirePurchaseConsent(purpose, consent, environment)
}

private fun requirePurchaseConsent(
    purpose: String,
    consent: String?,
    environment: String,
) {
    if (environment == "toss_live" && purpose == "purchase" && consent != "start-monthly-v1") {
        throw InvalidInputException("정기결제 조건에 동의해 주세요")
    }
}

internal fun validateRegistrationSubscription(
    subscription: Subscription?,
    purpose: String,
    planId: String,
    environment: String,
    now: Instant,
) {
    if (subscription?.status == "active" && purpose != "replace_card") throw ConflictException("이미 이용 중인 구독입니다")
    if (purpose == "replace_card") requireReplaceableSubscription(subscription, planId, environment)
    val samePlan = subscription?.planId == planId && subscription.provider == environment
    if (subscription?.status == "canceling" && subscription.cycleEndsAt > now && !samePlan) {
        throw ConflictException("현재 이용 기간에는 같은 플랜의 카드만 변경할 수 있습니다")
    }
}

private fun requireReplaceableSubscription(
    subscription: Subscription?,
    planId: String,
    environment: String,
) {
    val samePlan = subscription?.provider == environment && subscription.planId == planId
    if (!samePlan || subscription.status !in listOf("active", "canceling", "past_due")) {
        throw ConflictException("카드를 변경할 구독이 없습니다")
    }
}

@Suppress("ThrowsCount") // Each persisted state has a distinct recovery instruction.
internal fun validatePreviousSession(previous: BillingSession?, purpose: String, environment: String) {
    if (previous?.authKey != null && previous.state == "issuing") {
        throw ConflictException("처리 중인 카드 등록 결과를 먼저 확인하세요")
    }
    if (previous?.state == "manual_review") throw ConflictException("카드 등록 결과를 관리자에게 문의하세요")
    if (previous?.previousBillingKey != null) throw ConflictException("이전 카드 연결 해제를 처리 중입니다")
    if (previous?.billingKey != null && purpose != "replace_card") throw ConflictException("기존 카드가 연결되어 있습니다")
    if (previous != null && previous.environment != environment) throw ConflictException("결제 환경이 일치하지 않습니다")
}

// Callback binding and replay validation is atomic.
@Suppress("LongParameterList", "ThrowsCount", "CyclomaticComplexMethod")
internal fun validateBillingCallback(
    current: BillingSession,
    id: UUID,
    customer: UUID,
    auth: Secret,
    fail: Boolean,
    environment: String,
    now: Instant,
): BillingSession {
    if (current.environment != environment) throw ConflictException("결제 환경이 일치하지 않습니다")
    if (environment == "toss_live" && fail) throw InvalidInputException("실결제는 실패 시뮬레이션을 지원하지 않습니다")
    if (current.id != id || current.customer != customer) throw ConflictException("카드 등록 정보가 일치하지 않습니다")
    if (current.authKey != null && (current.authKey != auth || current.simulateFailure != fail)) {
        throw ConflictException("이미 처리한 카드 등록 요청입니다")
    }
    if (current.state == "active") return current
    if (current.state !in listOf("authorizing", "issuing") || (current.authKey == null && current.expiresAt < now)) {
        throw ConflictException("카드 등록 시간이 만료되었습니다. 다시 시작하세요")
    }
    if (auth.reveal().isBlank() || auth.reveal().length > AUTH_KEY_MAX_LENGTH) {
        throw InvalidInputException("잘못된 카드 등록 값입니다")
    }
    return current.copy(authKey = auth, simulateFailure = fail, state = "issuing")
}

private const val AUTH_KEY_MAX_LENGTH = 300

package kr.easydoc.application.subscription

import kr.easydoc.core.security.Secret
import java.util.UUID

/** Credentials and receipt links are never printable. */
data class TossPayment(
    val key: Secret,
    val orderId: UUID,
    val status: String,
    val amount: Int,
    val remainingAmount: Int,
    val receipt: Secret,
)

interface TossGateway {
    fun issue(
        authKey: Secret,
        customerKey: Secret,
        session: UUID,
    ): Secret

    @Suppress("LongParameterList")
    fun charge(
        billingKey: Secret,
        customerKey: Secret,
        order: UUID,
        amount: Int,
        plan: String,
        fail: Boolean,
    ): TossPayment

    fun find(order: UUID): TossPayment?

    fun refund(
        paymentKey: Secret,
        amount: Int,
        operation: UUID,
    ): TossPayment

    fun revoke(billingKey: Secret)
}

/** A definitive rejection, distinct from an uncertain network/server failure. */
class TossDeclined : RuntimeException("카드 결제가 거절되었습니다. 카드 정보를 확인해 주세요")

class TossUncertain : RuntimeException("결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요")

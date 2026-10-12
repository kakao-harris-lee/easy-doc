package kr.easydoc.infrastructure.retention

import kr.easydoc.application.retention.RetainedTransactionArchiver
import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import java.util.UUID

/** Only used by isolated legacy unit fixtures that do not create a retention store. */
class NoopRetainedTransactionArchiver : RetainedTransactionArchiver {
    override fun archiveForUser(userId: UUID) = Unit

    override fun archivePayment(
        order: BillingOrder,
        payment: TossPayment,
        previousRefundedAmount: Int,
        customerEmail: String?,
        consent: BillingSession?,
    ) = Unit
}

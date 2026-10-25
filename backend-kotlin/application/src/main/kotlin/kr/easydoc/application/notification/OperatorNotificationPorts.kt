package kr.easydoc.application.notification

import java.time.Instant
import java.util.UUID

sealed interface OperatorNotificationEvent {
    val userId: UUID
    val occurredAt: Instant
    val eventType: String

    data class MemberSignedUp(
        override val userId: UUID,
        override val occurredAt: Instant,
        val email: String,
        val route: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = "member_signed_up"
    }

    data class MemberDeleted(
        override val userId: UUID,
        override val occurredAt: Instant,
        val email: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = "member_deleted"
    }

    data class Payment(
        override val userId: UUID,
        override val occurredAt: Instant,
        val kind: OperatorPaymentKind,
        val workspaceId: UUID,
        val orderId: UUID,
        val planId: String,
        val amountKrw: Int,
        val environment: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = kind.wireValue
    }
}

enum class OperatorPaymentKind(val wireValue: String) {
    PAYMENT_APPROVED("payment_approved"),
    RENEWAL_APPROVED("renewal_approved"),
    PAYMENT_FAILED("payment_failed"),
    RENEWAL_FAILED("renewal_failed"),
    REFUND_APPROVED("refund_approved"),
    SUBSCRIPTION_CANCELING("subscription_canceling"),
    SUBSCRIPTION_EXPIRED("subscription_expired"),
    SUBSCRIPTION_PAST_DUE("subscription_past_due"),
    SUBSCRIPTION_SUSPENDED("subscription_suspended"),
    PAYMENT_MANUAL_REVIEW("payment_manual_review"),
}

fun interface OperatorNotifications {
    fun notify(event: OperatorNotificationEvent)
}

fun interface OperatorNotificationPort {
    fun send(message: String)
}

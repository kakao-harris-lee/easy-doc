package kr.easydoc.application.subscription

import kr.easydoc.core.security.Secret
import java.time.Instant
import java.util.UUID

data class BillingSession(
    val workspaceId: UUID,
    val id: UUID,
    val customer: UUID,
    val planId: String,
    val expiresAt: Instant,
    val authKey: Secret? = null,
    val billingKey: Secret? = null,
    val state: String = "authorizing",
    val simulateFailure: Boolean = false,
)

data class BillingOrder(
    val id: UUID,
    val workspaceId: UUID,
    val planId: String,
    val amount: Int,
    val createdAt: Instant,
    val cycleEndsAt: Instant,
    val kind: String = "charge",
    val originalId: UUID? = null,
    val previousRemaining: Int = 0,
    val status: String = "pending",
    val payment: TossPayment? = null,
    val simulateFailure: Boolean = false,
)

/** Mutations occur under SubscriptionStore.lockOwned in a short transaction. */
@Suppress("TooManyFunctions") // Durable payment and session storage share the workspace transaction boundary.
interface TossBillingStore {
    fun session(workspace: UUID): BillingSession?

    fun saveSession(session: BillingSession)

    fun order(id: UUID): BillingOrder?

    fun saveOrder(order: BillingOrder)

    fun pending(workspace: UUID): Boolean

    fun claim(
        id: UUID,
        now: Instant,
    ): BillingOrder?

    fun release(
        id: UUID,
        nextAttempt: Instant,
    )

    fun candidates(now: Instant): List<UUID>

    fun requestSync(id: UUID)

    fun savePayment(
        order: BillingOrder,
        payment: TossPayment,
    )

    fun cleanupCandidates(): List<UUID>

    fun authorizationCandidates(): List<UUID>
}

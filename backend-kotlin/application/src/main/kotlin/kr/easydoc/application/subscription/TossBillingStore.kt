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
    val environment: String = "toss_test",
    val purpose: String = "purchase",
    val consentVersion: String? = null,
    val consentAt: Instant? = null,
    val previousBillingKey: Secret? = null,
    val previousCustomer: UUID? = null,
    val cardLastFour: String? = null,
    val previousCardLastFour: String? = null,
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
    val environment: String = "toss_test",
    val cycleId: UUID = id,
    val attempt: Int = 0,
    val firstFailureAt: Instant? = null,
    val nextAttemptAt: Instant = createdAt,
)

/** Mutations occur under SubscriptionStore.lockOwned in a short transaction. */
@Suppress("TooManyFunctions") // Durable payment and session storage share the workspace transaction boundary.
interface TossBillingStore {
    fun stopScheduled(workspace: UUID) = Unit

    fun replacementBlocked(workspace: UUID): Boolean = pending(workspace)

    fun expiredReplacements(now: Instant): List<UUID> = emptyList()

    fun sessionByBillingKey(
        key: Secret,
        environment: String,
    ): BillingSession? = null

    fun latestOrder(workspace: UUID): BillingOrder? = null

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

    /** Issuing sessions are eligible only while they remain inside the retry window. */
    fun authorizationCandidates(retryCutoff: Instant): List<UUID>
}

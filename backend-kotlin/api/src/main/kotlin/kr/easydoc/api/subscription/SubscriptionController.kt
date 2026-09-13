package kr.easydoc.api.subscription

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.subscription.SubscriptionOverview
import kr.easydoc.application.subscription.SubscriptionService
import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
class SubscriptionController(private val service: SubscriptionService) {
    @GetMapping("/workspaces/{workspace_id}/subscription")
    fun get(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
    ): SubscriptionResponse = SubscriptionResponse.of(service.read(user.id, workspace))

    @PostMapping("/workspaces/{workspace_id}/subscription/checkout", consumes = ["application/json"])
    fun checkout(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @RequestBody request: SubscriptionCheckoutRequest,
    ): SubscriptionResponse =
        SubscriptionResponse.of(
            service.checkout(user.id, workspace, request.planId, request.orderId, request.simulateFailure == true),
        )

    @DeleteMapping("/workspaces/{workspace_id}/subscription")
    fun cancel(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
    ): SubscriptionResponse = SubscriptionResponse.of(service.cancel(user.id, workspace))
}

@RestController
class AdminSubscriptionController(
    private val service: SubscriptionService,
    private val credits: CreditAccountRepository,
) {
    @GetMapping("/admin/workspaces/{workspace_id}/subscription")
    fun get(
        @PathVariable("workspace_id") workspace: UUID,
    ): SubscriptionResponse {
        val owner = credits.ownerOf(workspace) ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
        return SubscriptionResponse.of(service.read(owner, workspace))
    }
}

data class SubscriptionCheckoutRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("plan_id") val planId: String,
        @param:JsonProperty("order_id") val orderId: UUID,
        @param:JsonProperty("simulate_failure")
        @param:JsonSetter(nulls = Nulls.SET)
        val simulateFailure: Boolean? = null,
    )

data class SubscriptionPlanResponse(
    val id: String,
    val name: String,
    val allowance: Int,
    @get:JsonProperty("monthly_price") val monthlyPrice: Int,
) {
    override fun toString(): String =
        "SubscriptionPlanResponse(id=$id, allowance=$allowance, monthlyPrice=$monthlyPrice)"
}

data class SubscriptionStateResponse(
    @get:JsonProperty("plan_id") val planId: String,
    val allowance: Int,
    @get:JsonProperty("monthly_price") val monthlyPrice: Int,
    val status: String,
    @get:JsonProperty("cycle_ends_at") val cycleEndsAt: Instant,
)

data class SubscriptionPaymentResponse(
    val id: UUID,
    @get:JsonProperty("plan_id") val planId: String,
    val amount: Int,
    val status: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    val provider: String = "stub",
    @get:JsonProperty("refunded_amount") val refundedAmount: Int = 0,
)

data class SubscriptionResponse(
    @get:JsonProperty("mock_enabled") val mockEnabled: Boolean,
    val plans: List<SubscriptionPlanResponse>,
    val subscription: SubscriptionStateResponse?,
    val payments: List<SubscriptionPaymentResponse>,
    @get:JsonProperty("toss_enabled") val tossEnabled: Boolean = false,
    val pending: Boolean = false,
    @get:JsonProperty("billing_state") val billingState: String? = null,
) {
    companion object {
        fun of(view: SubscriptionOverview) =
            SubscriptionResponse(
                view.mockEnabled,
                view.plans.map { SubscriptionPlanResponse(it.id, it.name, it.allowance, it.monthlyPrice) },
                view.subscription?.let {
                    SubscriptionStateResponse(it.planId, it.allowance, it.monthlyPrice, it.status, it.cycleEndsAt)
                },
                view.payments.map {
                    SubscriptionPaymentResponse(
                        it.id,
                        it.planId,
                        it.amount,
                        it.status,
                        it.createdAt,
                        it.provider,
                        it.refundedAmount,
                    )
                },
                view.tossEnabled,
                view.pending,
                view.billingState,
            )
    }
}

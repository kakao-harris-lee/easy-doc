package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AdminBillingActionView
import kr.easydoc.application.admin.AdminBillingService
import kr.easydoc.application.admin.AdminRefundCommand
import kr.easydoc.application.admin.AdminRefundOperation
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@Profile("!migrate")
class AdminBillingController(
    private val service: AdminBillingService,
    private val query: kr.easydoc.application.admin.AdminOperationsQuery,
) {
    @GetMapping("/admin/workspaces/{workspace_id}/billing")
    fun read(
        @PathVariable("workspace_id") workspace: UUID,
    ): Map<String, Any?> {
        // Read the revision first: an intervening update can only make this token stale, never authorize stale data.
        val state = query.billingState(workspace)
        return mapOf(
            "orders" to
                service.orders(workspace).map {
                    mapOf(
                        "id" to it.id,
                        "kind" to it.kind,
                        "status" to it.status,
                        "amount" to it.amount,
                        "created_at" to it.createdAt,
                        "environment" to it.environment,
                        "needs_review" to it.needsReview,
                        "can_sync" to it.canSync,
                        "sync_blocked_reason" to it.syncBlockedReason,
                    )
                },
            "operations" to service.operations(workspace).map(AdminRefundResponse::of),
            "actions" to service.actions(workspace).map(AdminBillingActionResponse::of),
        ) + state
    }

    @PostMapping("/admin/workspaces/{workspace_id}/payments/{id}/refund", consumes = ["application/json"])
    fun refund(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @PathVariable id: UUID,
        @RequestBody request: AdminRefundRequest,
    ): AdminRefundResponse =
        AdminRefundResponse.of(
            service.refund(
                AdminRefundCommand(
                    request.operationId,
                    workspace,
                    id,
                    user.id,
                    request.amount,
                    request.recoveryCredits,
                    request.stopRenewal,
                    request.reason,
                    request.expectedRevision,
                ),
            ),
        )

    @PostMapping("/admin/workspaces/{workspace_id}/billing/orders/{id}/sync", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun sync(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @PathVariable id: UUID,
        @RequestBody request: AdminBillingActionRequest,
    ) {
        service.action(workspace, user.id, request.operationId, "sync", request.reason, id, request.expectedRevision)
    }

    @PostMapping("/admin/workspaces/{workspace_id}/billing/stop-renewal", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun stop(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @RequestBody request: AdminBillingActionRequest,
    ) {
        service.action(
            workspace,
            user.id,
            request.operationId,
            "stop_renewal",
            request.reason,
            expectedRevision = request.expectedRevision,
        )
    }

    @PostMapping("/admin/workspaces/{workspace_id}/billing/retry-card-deletion", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun retry(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @RequestBody request: AdminBillingActionRequest,
    ) {
        service.action(
            workspace,
            user.id,
            request.operationId,
            "retry_card_deletion",
            request.reason,
            expectedRevision = request.expectedRevision,
        )
    }
}

data class AdminRefundRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("operation_id") val operationId: UUID,
        @param:JsonProperty("amount") val amount: Int,
        @param:JsonProperty("recovery_credits") val recoveryCredits: BigDecimal,
        @param:JsonProperty("stop_renewal") val stopRenewal: Boolean,
        @param:JsonProperty("reason") val reason: String,
        @param:JsonProperty("expected_revision") val expectedRevision: Long,
    ) {
        override fun toString(): String = "AdminRefundRequest(operationId=$operationId, reason=[MASKED])"
    }

data class AdminBillingActionRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("operation_id") val operationId: UUID,
        @param:JsonProperty("reason") val reason: String,
        @param:JsonProperty("expected_revision")
        @param:com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SET)
        val expectedRevision: Long? = null,
    ) {
        override fun toString(): String = "AdminBillingActionRequest(operationId=$operationId, reason=[MASKED])"
    }

data class AdminRefundResponse(
    @get:JsonProperty("operation_id") val operationId: UUID,
    @get:JsonProperty("workspace_id") val workspaceId: UUID,
    @get:JsonProperty("payment_id") val paymentId: UUID,
    val amount: Int,
    @get:JsonProperty("recovery_credits") val recoveryCredits: BigDecimal,
    @get:JsonProperty("stop_renewal") val stopRenewal: Boolean,
    val reason: String,
    val status: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("updated_at") val updatedAt: Instant,
) {
    override fun toString(): String = "AdminRefundResponse(operationId=$operationId, status=$status, reason=[MASKED])"

    companion object {
        fun of(operation: AdminRefundOperation): AdminRefundResponse =
            operation.command.let {
                AdminRefundResponse(
                    it.operationId,
                    it.workspaceId,
                    it.paymentId,
                    it.amount,
                    it.recoveryCredits,
                    it.stopRenewal,
                    it.reason,
                    operation.status,
                    operation.createdAt,
                    operation.updatedAt,
                )
            }
    }
}

data class AdminBillingActionResponse(
    @get:JsonProperty("operation_id") val operationId: UUID,
    val action: String,
    val status: String,
    val reason: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("updated_at") val updatedAt: Instant,
) {
    override fun toString(): String = "AdminBillingActionResponse(operationId=$operationId, reason=[MASKED])"

    companion object {
        fun of(action: AdminBillingActionView): AdminBillingActionResponse =
            AdminBillingActionResponse(
                action.id,
                action.action,
                action.status,
                action.reason,
                action.createdAt,
                action.updatedAt,
            )
    }
}

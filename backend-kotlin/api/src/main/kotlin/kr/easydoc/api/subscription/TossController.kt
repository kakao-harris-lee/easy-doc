package kr.easydoc.api.subscription

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.subscription.SubscriptionService
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossDeclined
import kr.easydoc.application.subscription.TossUncertain
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@RestController
@Profile("!migrate")
class TossController(
    private val toss: TossBillingService,
    private val subscriptions: SubscriptionService,
    private val accounts: CreditAccountRepository,
) {
    @PostMapping("/workspaces/{workspace_id}/subscription/billing", consumes = ["application/json"])
    fun begin(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @RequestBody request: TossBeginRequest,
    ): TossSessionResponse {
        val session = toss.begin(user.id, workspace, request.planId)
        return TossSessionResponse(session.id, session.customer, toss.clientKey.reveal())
    }

    @PostMapping("/workspaces/{workspace_id}/subscription/billing/complete", consumes = ["application/json"])
    fun complete(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @RequestBody request: TossCompleteRequest,
    ): SubscriptionResponse {
        try {
            toss.complete(
                user.id,
                workspace,
                request.sessionId,
                request.customerKey,
                Secret(request.authKey),
                request.simulateFailure == true,
            )
        } catch (_: TossDeclined) {
            throw ConflictException("카드 등록이 거절되었습니다. 다시 시도하세요")
        } catch (_: TossUncertain) {
            throw ConflictException("카드 등록 결과를 확인하지 못했습니다. 같은 요청을 다시 시도하세요")
        }
        return SubscriptionResponse.of(subscriptions.read(user.id, workspace))
    }

    @GetMapping("/workspaces/{workspace_id}/payments/{id}/receipt")
    fun receipt(
        user: AuthenticatedUser,
        @PathVariable("workspace_id") workspace: UUID,
        @PathVariable id: UUID,
    ): TossReceiptResponse = TossReceiptResponse(toss.receipt(user.id, workspace, id).reveal())

    @PostMapping("/admin/workspaces/{workspace_id}/payments/{id}/refund", consumes = ["application/json"])
    fun refund(
        @PathVariable("workspace_id") workspace: UUID,
        @PathVariable id: UUID,
        @RequestBody request: TossRefundRequest,
    ): SubscriptionResponse {
        val owner = accounts.ownerOf(workspace) ?: throw NotFoundException("작업 공간을 찾을 수 없습니다")
        toss.refund(owner, workspace, id, request.operationId, request.amount)
        return SubscriptionResponse.of(subscriptions.read(owner, workspace))
    }

    @PostMapping("/payments/toss/webhook", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun webhook(
        @RequestBody request: JsonNode,
    ) {
        if (request.path("eventType").asString() !in setOf("PAYMENT_STATUS_CHANGED", "CANCEL_STATUS_CHANGED")) return
        val order = request.path("data").path("orderId").asString()
        val id = runCatching { UUID.fromString(order) }.getOrNull() ?: return
        toss.requestSync(id)
    }
}

data class TossBeginRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("plan_id") val planId: String,
    )

data class TossCompleteRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("session_id") val sessionId: UUID,
        @param:JsonProperty("customer_key") val customerKey: UUID,
        @param:JsonProperty("auth_key") val authKey: String,
        @param:JsonProperty(
            "simulate_failure",
        ) @param:JsonSetter(nulls = Nulls.SET) val simulateFailure: Boolean? = null,
    ) {
        override fun toString(): String = "TossCompleteRequest(sessionId=$sessionId, authKey=[REDACTED])"
    }

data class TossRefundRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("operation_id") val operationId: UUID,
        @param:JsonProperty("amount") val amount: Int,
    )

data class TossSessionResponse(
    @get:JsonProperty("session_id") val sessionId: UUID,
    @get:JsonProperty("customer_key") val customerKey: UUID,
    @get:JsonProperty("client_key") val clientKey: String,
) {
    override fun toString(): String = "TossSessionResponse(sessionId=$sessionId)"
}

data class TossReceiptResponse(
    @get:JsonProperty("receipt_url") val receiptUrl: String,
) {
    override fun toString(): String = "TossReceiptResponse([REDACTED])"
}

package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.retention.ComplaintDecisionCommand
import kr.easydoc.application.retention.RefundDecisionCommand
import kr.easydoc.application.retention.RetainedRecordType
import kr.easydoc.application.retention.RetainedTransactionAdminService
import kr.easydoc.application.retention.RetainedTransactionDecision
import kr.easydoc.application.retention.RetainedTransactionDetail
import kr.easydoc.application.retention.RetainedTransactionFilter
import kr.easydoc.application.retention.RetainedTransactionSummary
import kr.easydoc.core.exceptions.InvalidInputException
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 법정 보존 원장의 조회와 심사 결과 기록. 실제 환불은 기존 결제 API에서 별도로 실행한다. */
@RestController
@Profile("!migrate")
@RequestMapping("/admin/retained-transactions")
class AdminRetainedTransactionsController(
    private val service: RetainedTransactionAdminService,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
        @RequestParam(name = "type", required = false) type: String?,
        @RequestParam(name = "from", required = false) from: Instant?,
        @RequestParam(name = "to", required = false) to: Instant?,
    ): ResponseEntity<Map<String, Any?>> {
        requirePage(page, size)
        requireRange(from, to)
        val result = service.list(RetainedTransactionFilter(parseType(type), from, to), page, size)
        return adminResponse(HttpStatus.OK).body(result)
    }

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: UUID,
    ): ResponseEntity<Map<String, Any?>> {
        val result = service.find(id)
        return adminResponse(HttpStatus.OK).body(detailBody(result))
    }

    @PostMapping("/{id}/refund-decision", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun refundDecision(
        user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody request: RetainedRefundDecisionRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val result =
            service.refundDecision(
                id,
                user.id,
                RefundDecisionCommand(
                    operationId = request.operationId,
                    status = request.status,
                    amount = request.amount,
                    recoveryCredits = request.recoveryCredits,
                    reason = request.reason,
                    stopRenewal = request.stopRenewal,
                    receivedAt = request.receivedAt,
                ),
            )
        return adminResponse(HttpStatus.OK).body(detailBody(result))
    }

    @PostMapping("/{id}/complaint-decision", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun complaintDecision(
        user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody request: RetainedComplaintDecisionRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val result =
            service.complaintDecision(
                id,
                user.id,
                ComplaintDecisionCommand(
                    operationId = request.operationId,
                    status = request.status,
                    reason = request.reason,
                    receivedAt = request.receivedAt,
                ),
            )
        return adminResponse(HttpStatus.OK).body(detailBody(result))
    }

    private fun detailBody(detail: RetainedTransactionDetail): Map<String, Any?> =
        summaryBody(detail.summary) +
            mapOf(
                "payload" to objectMapper.readTree(detail.payloadJson),
                "decisions" to detail.decisions.map(::decisionBody),
            )

    private fun summaryBody(summary: RetainedTransactionSummary): Map<String, Any?> =
        mapOf(
            "id" to summary.id,
            "type" to summary.type.wireName,
            "occurred_at" to summary.occurredAt,
            "retained_until" to summary.retainedUntil,
            "payment_id" to summary.paymentId,
            "environment" to summary.environment,
            "manual_review" to summary.manualReview,
        )

    private fun decisionBody(decision: RetainedTransactionDecision): Map<String, Any?> =
        mapOf(
            "operation_id" to decision.operationId,
            "type" to decision.type,
            "actor_user_id" to decision.actorUserId,
            "status" to decision.status,
            "amount" to decision.amount,
            "recovery_credits" to decision.recoveryCredits,
            "reason" to decision.reason,
            "stop_renewal" to decision.stopRenewal,
            "received_at" to decision.receivedAt,
            "created_at" to decision.createdAt,
            "updated_at" to decision.updatedAt,
        )

    private fun parseType(value: String?): RetainedRecordType? =
        value?.let {
            try {
                RetainedRecordType.fromWire(it)
            } catch (_: IllegalStateException) {
                throw InvalidInputException("보존 원장 유형을 확인하세요")
            }
        }

    private fun requirePage(
        page: Int,
        size: Int,
    ) {
        if (page < 0 || page > MAX_PAGE || size !in 1..MAX_SIZE) {
            throw InvalidInputException("페이지와 크기를 확인하세요")
        }
    }

    private fun requireRange(
        from: Instant?,
        to: Instant?,
    ) {
        if (from != null && to != null && from >= to) {
            throw InvalidInputException("조회 기간을 확인하세요")
        }
    }

    private companion object {
        const val MAX_PAGE = 100_000
        const val MAX_SIZE = 100
    }
}

data class RetainedRefundDecisionRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("operation_id") val operationId: UUID,
        @param:JsonProperty("status") val status: String,
        @param:JsonProperty("amount") val amount: Int,
        @param:JsonProperty("recovery_credits") val recoveryCredits: BigDecimal,
        @param:JsonProperty("reason") val reason: String,
        @param:JsonProperty("stop_renewal") val stopRenewal: Boolean,
        @param:JsonProperty("received_at") val receivedAt: Instant,
    ) {
        override fun toString(): String =
            "RetainedRefundDecisionRequest(operationId=$operationId, status=$status, reason=[MASKED])"
    }

data class RetainedComplaintDecisionRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("operation_id") val operationId: UUID,
        @param:JsonProperty("status") val status: String,
        @param:JsonProperty("reason") val reason: String,
        @param:JsonProperty("received_at") val receivedAt: Instant,
    ) {
        override fun toString(): String =
            "RetainedComplaintDecisionRequest(operationId=$operationId, status=$status, reason=[MASKED])"
    }

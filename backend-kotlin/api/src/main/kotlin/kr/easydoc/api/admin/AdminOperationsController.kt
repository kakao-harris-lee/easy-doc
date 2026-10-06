package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AdminNotificationCommand
import kr.easydoc.application.admin.AdminOperationsQuery
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@Profile("!migrate")
class AdminOperationsController(private val query: AdminOperationsQuery) {
    @GetMapping("/admin/operations")
    fun operations(
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) environment: String?,
    ): Map<String, Any?> =
        query.operations(filters("kind" to kind, "state" to state, "environment" to environment), page, size)

    @Suppress("LongParameterList") // Independent HTTP list filters.
    @GetMapping("/admin/notifications")
    fun notifications(
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(required = false) @Min(1) id: Long?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) environment: String?,
        @RequestParam("event_type", required = false) eventType: String?,
    ): Map<String, Any?> =
        query.notifications(
            filters("id" to id?.toString(), "state" to state, "environment" to environment, "event_type" to eventType),
            page,
            size,
        )

    @PostMapping("/admin/notifications/{id}/resolve", consumes = ["application/json"])
    fun resolve(
        user: AuthenticatedUser,
        @PathVariable id: Long,
        @RequestBody request: AdminNotificationRequest,
    ): Map<String, Any?> = query.notificationAction(id, user.id, request.command(), false)

    @PostMapping("/admin/notifications/{id}/retry", consumes = ["application/json"])
    fun retry(
        user: AuthenticatedUser,
        @PathVariable id: Long,
        @RequestBody request: AdminNotificationRequest,
    ): Map<String, Any?> = query.notificationAction(id, user.id, request.command(), true)

    @GetMapping("/admin/workspaces/{workspace_id}/billing/requests/{operation_id}")
    fun request(
        @PathVariable("workspace_id") workspace: UUID,
        @PathVariable("operation_id") operation: UUID,
    ): Map<String, Any?> = query.request(workspace, operation)

    @GetMapping("/admin/errors/events")
    fun errors(
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam("failure_code", required = false) failureCode: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): Map<String, Any?> =
        query.errors(
            filters("failure_code" to failureCode, "from" to from?.toString(), "to" to to?.toString()),
            page,
            size,
        )

    private fun filters(vararg values: Pair<String, String?>): Map<String, String> =
        values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}

data class AdminNotificationRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(value = "operation_id", required = true) val operationId: UUID,
        @param:JsonProperty(value = "expected_revision", required = true) val expectedRevision: Long,
        @param:JsonProperty(value = "reason", required = true) val reason: String,
        @param:JsonProperty("resolution")
        @param:com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SET)
        val resolution: String? = null,
    ) {
        fun command(): AdminNotificationCommand =
            AdminNotificationCommand(operationId, expectedRevision, reason, resolution)

        override fun toString(): String = "AdminNotificationRequest(operationId=$operationId, reason=[MASKED])"
    }

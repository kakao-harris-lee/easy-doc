package kr.easydoc.application.admin

import java.util.UUID

/** Explicit, safe projection only; implementations never return provider payloads or message bodies. */
interface AdminOperationsQuery {
    fun operations(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?>

    fun notifications(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?>

    fun notificationAction(
        id: Long,
        actor: UUID,
        command: AdminNotificationCommand,
        retry: Boolean,
    ): Map<String, Any?>

    fun request(
        workspace: UUID,
        operation: UUID,
    ): Map<String, Any?>

    fun billingState(workspace: UUID): Map<String, Any?>

    fun errors(
        filters: Map<String, String>,
        page: Int,
        size: Int,
    ): Map<String, Any?>
}

data class AdminNotificationCommand(
    val operationId: UUID,
    val expectedRevision: Long,
    val reason: String,
    val resolution: String?,
) {
    override fun toString(): String = "AdminNotificationCommand(operationId=$operationId, reason=[MASKED])"
}
